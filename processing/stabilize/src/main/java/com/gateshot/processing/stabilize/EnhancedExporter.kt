package com.gateshot.processing.stabilize

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Bakes the playback enhancements into a shareable MP4: decode → GL warp
 * (per-frame stabilization correction + crop) + color grade → H.264 encode →
 * mux, copying the source audio unchanged. Adapted from the offline
 * stabilizer's VideoTranscoder (commit 5903127); HDR (HLG/PQ) input is
 * tonemapped to SDR in the shader.
 *
 * Stabilization corrections come from [PlaybackStabilizer.Track] and are in
 * CODED (unrotated) frame space — the same space the decoder surface delivers
 * — so they feed the warp directly; only the GL v-axis (which runs opposite
 * the buffer y-axis) needs flipping. Verified empirically with synthetic
 * shake at rotation 0 and 270 (jitter −80% both; an axis swap here reads
 * +50% instead).
 *
 * After export the result is self-checked: inter-frame jitter is re-measured
 * on both files so a wrong warp direction shows up as a jitter increase
 * instead of silently doubling the shake.
 */
class EnhancedExporter {

    data class Result(
        val outputPath: String,
        val framesWritten: Int,
        /** Jitter change on a sampled window: negative = smoother (good). */
        val jitterChangePercent: Int?
    )

    suspend fun export(
        srcPath: String,
        outPath: String,
        track: PlaybackStabilizer.Track?,
        colorMatrix: FloatArray?,
        onProgress: (Float) -> Unit = {}
    ): Result? = withContext(Dispatchers.Default) {
        try {
            val frames = transcode(srcPath, outPath, track, colorMatrix, onProgress)
            if (frames <= 0) return@withContext null
            // Self-check only makes sense when we actually warped.
            val jitterChange = if (track != null) {
                measureJitterChange(srcPath, outPath, track.cropFactor)
            } else null
            Result(outPath, frames, jitterChange)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Export failed: ${e.message}")
            try { java.io.File(outPath).delete() } catch (_: Exception) { }
            null
        }
    }

    private suspend fun transcode(
        srcPath: String,
        outPath: String,
        track: PlaybackStabilizer.Track?,
        colorMatrix: FloatArray?,
        onProgress: (Float) -> Unit
    ): Int = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor().apply { setDataSource(srcPath) }
        val videoTrack = firstTrack(extractor, "video/")
        val audioTrack = firstTrack(extractor, "audio/")
        require(videoTrack >= 0) { "No video track" }
        extractor.selectTrack(videoTrack)
        val srcFormat = extractor.getTrackFormat(videoTrack)
        val mime = srcFormat.getString(MediaFormat.KEY_MIME)!!

        val width = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = if (srcFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            srcFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else 30f
        val rotation = if (srcFormat.containsKey(MediaFormat.KEY_ROTATION))
            srcFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
        val durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION))
            srcFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val isHdr = srcFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
            srcFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let {
                it == MediaFormat.COLOR_TRANSFER_HLG || it == MediaFormat.COLOR_TRANSFER_ST2084
            }

        // Stabilization needs the crop margin; color-only keeps full frame.
        val cropFrac = if (track != null) (1f - 1f / track.cropFactor) / 2f else 0f
        val outW = evenFloor((width * (1f - 2f * cropFrac)).toInt())
        val outH = evenFloor((height * (1f - 2f * cropFrac)).toInt())

        val encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(outW, outH, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(24))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()

        val gl = WarpGlPipeline(encoderInputSurface, cropFrac, tonemapHdr = isHdr, colorMatrix = colorMatrix)

        srcFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(srcFormat, gl.decoderSurface, null, 0)

        val frameAvailable = Object()
        var frameReady = false
        gl.surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameAvailable) { frameReady = true; frameAvailable.notifyAll() }
        }

        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // Keep display orientation: frames stay coded, metadata carries rotation.
        muxer.setOrientationHint(rotation)
        var muxerStarted = false
        var videoMuxTrack = -1

        encoder.start()
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var frameIndex = 0
        val timeout = 10_000L
        val totalApprox = (durationUs / 1_000_000f * fps).toInt().coerceAtLeast(1)

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(info, timeout)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoMuxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIdx >= 0) {
                    val encoded = encoder.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(videoMuxTrack, encoded, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        try {
            while (!decodeDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(timeout)
                    if (inIdx >= 0) {
                        val ib = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(ib, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, timeout)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    outIdx >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0
                        val ptsNs = info.presentationTimeUs * 1000
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            awaitFrame(frameAvailable) { frameReady }.also {
                                synchronized(frameAvailable) { frameReady = false }
                            }
                            gl.updateTexImage()
                            var tx = 0f
                            var ty = 0f
                            if (track != null) {
                                // Track corrections are coded-space already;
                                // the warp's v axis runs opposite the buffer's
                                // y axis, so only the vertical flips.
                                val (cx, cy) = track.correctionAt(info.presentationTimeUs / 1000)
                                tx = cx; ty = -cy
                            }
                            gl.drawFrame(0f, tx, ty, ptsNs)
                            drainEncoder(false)
                            frameIndex++
                            if (frameIndex % 15 == 0) {
                                onProgress((frameIndex.toFloat() / totalApprox).coerceAtMost(0.98f))
                            }
                        }
                        if (eos) decodeDone = true
                    }
                }
            }
            drainEncoder(true)
        } finally {
            try { decoder.stop() } catch (_: Throwable) { }
            try { decoder.release() } catch (_: Throwable) { }
            try { encoder.stop() } catch (_: Throwable) { }
            try { encoder.release() } catch (_: Throwable) { }
            gl.release()
            encoderInputSurface.release()
            extractor.release()
        }

        // Audio passthrough into the same muxer.
        if (audioTrack >= 0 && muxerStarted) {
            copyAudio(srcPath, audioTrack, muxer)
        }
        try { muxer.stop() } catch (_: Throwable) { }
        try { muxer.release() } catch (_: Throwable) { }
        onProgress(1f)
        frameIndex
    }

    private fun copyAudio(srcPath: String, audioTrack: Int, muxer: MediaMuxer) {
        val ex = MediaExtractor().apply { setDataSource(srcPath) }
        try {
            ex.selectTrack(audioTrack)
            val fmt = ex.getTrackFormat(audioTrack)
            val muxTrack = muxer.addTrack(fmt)
            val maxSize = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sz = ex.readSampleData(buffer, 0)
                if (sz < 0) break
                info.offset = 0
                info.size = sz
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(muxTrack, buffer, info)
                ex.advance()
            }
        } catch (_: Throwable) {
            // Audio is best-effort; an enhanced clip without audio still beats none.
        } finally {
            ex.release()
        }
    }

    /**
     * Re-measure inter-frame jitter on a sampled window of both files
     * (MediaMetadataRetriever frames are display-oriented, matching the
     * space the corrections were computed in). Returns percent change.
     */
    private fun measureJitterChange(srcPath: String, outPath: String, cropFactor: Float): Int? {
        return try {
            val src = measureJitter(srcPath)
            // The output is zoomed by the crop, which magnifies identical
            // physical motion by the same factor — normalize it out.
            val out = measureJitter(outPath) / cropFactor
            if (src <= 1e-3f) null
            else (100f * (out - src) / src).toInt()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Jitter self-check failed: ${e.message}")
            null
        }
    }

    private fun measureJitter(path: String, maxFrames: Int = 60): Float {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frameCount = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: return 0f
            val n = frameCount.coerceAtMost(maxFrames)
            val correlator = PhaseCorrelator(CHECK_SIZE)
            var prev: FloatArray? = null
            var sum = 0f
            for (i in 0 until n) {
                val frame = retriever.getFrameAtIndex(i) ?: continue
                val scaled = Bitmap.createScaledBitmap(frame, CHECK_SIZE, CHECK_SIZE, true)
                if (scaled !== frame) frame.recycle()
                val pixels = IntArray(CHECK_SIZE * CHECK_SIZE)
                scaled.getPixels(pixels, 0, CHECK_SIZE, 0, 0, CHECK_SIZE, CHECK_SIZE)
                scaled.recycle()
                val luma = FloatArray(pixels.size) { idx ->
                    val p = pixels[idx]
                    0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                }
                prev?.let { p ->
                    val r = correlator.correlate(p, luma)
                    sum += abs(r[0]) + abs(r[1])
                }
                prev = luma
            }
            sum
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private inline fun awaitFrame(lock: Object, ready: () -> Boolean) {
        synchronized(lock) {
            var spins = 0
            while (!ready() && spins < 500) {
                lock.wait(10)
                spins++
            }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun bitrateFor(w: Int, h: Int, fps: Float): Int {
        val bpp = 0.12
        return (w.toLong() * h * fps.toInt().coerceAtLeast(24) * bpp).toInt()
            .coerceIn(4_000_000, 80_000_000)
    }

    private fun evenFloor(v: Int): Int = if (v % 2 == 0) v else v - 1

    companion object {
        private const val TAG = "EnhancedExporter"
        private const val CHECK_SIZE = 128
    }
}
