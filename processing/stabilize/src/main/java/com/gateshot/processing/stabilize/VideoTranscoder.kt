package com.gateshot.processing.stabilize

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Pass 2: decode the source -> GL warp (per-frame correction + crop) -> encode
 * (H.264 8-bit) -> mux, copying the source audio track unchanged. HDR (HLG/PQ)
 * input is tonemapped to SDR in the warp shader (v1 fallback). Single-threaded
 * decode/encode driving the [WarpGlPipeline], the canonical Android
 * decode-edit-encode flow.
 */
internal class VideoTranscoder {

    /**
     * @param angleRad per-frame roll correction (radians)
     * @param txNorm   per-frame x translation (pixels / width)
     * @param tyNorm   per-frame y translation (pixels / height)
     * @return number of frames written
     */
    fun transcode(
        srcPath: String,
        outPath: String,
        meta: VideoFrameSource.Meta,
        cropFrac: Float,
        angleRad: FloatArray,
        txNorm: FloatArray,
        tyNorm: FloatArray,
        progress: (Float) -> Unit
    ): Int {
        val outW = evenFloor((meta.width * (1f - 2f * cropFrac)).toInt())
        val outH = evenFloor((meta.height * (1f - 2f * cropFrac)).toInt())

        val extractor = MediaExtractor().apply { setDataSource(srcPath) }
        val videoTrack = firstTrack(extractor, "video/")
        val audioTrack = firstTrack(extractor, "audio/")
        extractor.selectTrack(videoTrack)
        val srcFormat = extractor.getTrackFormat(videoTrack)
        val mime = srcFormat.getString(MediaFormat.KEY_MIME)!!

        // Encoder (surface input).
        val encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(outW, outH, meta.fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, meta.fps.toInt().coerceAtLeast(24))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()

        val gl = WarpGlPipeline(encoderInputSurface, cropFrac, tonemapHdr = meta.isHdr)

        // Decoder renders onto the GL pipeline's SurfaceTexture-backed surface.
        srcFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(srcFormat, gl.decoderSurface, null, 0)

        val frameAvailable = Object()
        var frameReady = false
        gl.surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameAvailable) { frameReady = true; frameAvailable.notifyAll() }
        }

        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoMuxTrack = -1

        encoder.start()
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var frameIndex = 0
        val timeout = 10_000L
        val totalApprox = angleRad.size.coerceAtLeast(1)

        fun corr(i: Int): Int = i.coerceIn(0, angleRad.size - 1)

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
                            val c = corr(frameIndex)
                            gl.drawFrame(angleRad[c], txNorm[c], tyNorm[c], ptsNs)
                            drainEncoder(false)
                            frameIndex++
                            if (frameIndex % 15 == 0) progress(0.5f + 0.5f * frameIndex / totalApprox)
                        }
                        if (eos) decodeDone = true
                    }
                }
            }
            drainEncoder(true)
        } finally {
            try { decoder.stop() } catch (_: Throwable) {}
            try { decoder.release() } catch (_: Throwable) {}
            try { encoder.stop() } catch (_: Throwable) {}
            try { encoder.release() } catch (_: Throwable) {}
            gl.release()
            encoderInputSurface.release()
            extractor.release()
        }

        // Audio passthrough into the same muxer.
        if (audioTrack >= 0 && muxerStarted) {
            copyAudio(srcPath, audioTrack, muxer)
        }
        try { muxer.stop() } catch (_: Throwable) {}
        try { muxer.release() } catch (_: Throwable) {}
        return frameIndex
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
            // Audio is best-effort; a stabilized clip without audio still beats none.
        } finally {
            ex.release()
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
        return (w.toLong() * h * fps.toInt().coerceAtLeast(24) * bpp).toInt().coerceIn(4_000_000, 80_000_000)
    }

    private fun evenFloor(v: Int): Int = if (v % 2 == 0) v else v - 1
}
