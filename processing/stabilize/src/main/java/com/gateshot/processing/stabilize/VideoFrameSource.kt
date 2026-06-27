package com.gateshot.processing.stabilize

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Decodes a video's frames to downsampled grayscale (luma) for the analysis
 * passes, and exposes metadata. Decode is done in ByteBuffer mode reading the
 * Y plane only (luma == grayscale), so no GL context is needed here. The warp/
 * encode pass uses the GL path in [VideoTranscoder] instead.
 */
internal class VideoFrameSource(private val path: String) {

    data class Meta(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val fps: Float,
        val durationUs: Long,
        val mime: String,
        val isHdr: Boolean
    )

    fun readMeta(): Meta {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        try {
            val track = selectVideoTrack(extractor)
            val fmt = extractor.getTrackFormat(track)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else 30f
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val transfer = if (fmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            // 6 = HLG, 7 = ST2084/PQ in MediaFormat.COLOR_TRANSFER_*
            val isHdr = transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084
            return Meta(w, h, rotation, fps, durationUs, mime, isHdr)
        } finally {
            extractor.release()
        }
    }

    /**
     * Decode every frame, downsample its luma to [n] x [n], and invoke [cb].
     * Returns the per-frame presentation timestamps in nanoseconds (the frame
     * clock used by the calibrator's sync search).
     */
    fun forEachLuma(n: Int, cb: (luma: FloatArray, index: Int) -> Unit): LongArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val ptsNs = ArrayList<Long>(1024)
        var codec: MediaCodec? = null
        try {
            val track = selectVideoTrack(extractor)
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var frameIndex = 0
            val timeoutUs = 10_000L

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val luma = readLuma(codec, outIdx, w, h, n)
                        if (luma != null) {
                            ptsNs.add(info.presentationTimeUs * 1000)
                            cb(luma, frameIndex)
                            frameIndex++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            extractor.release()
        }
        return ptsNs.toLongArray()
    }

    /** Read the Y plane of the decoded frame and box-downsample to n x n. */
    private fun readLuma(codec: MediaCodec, outIdx: Int, w: Int, h: Int, n: Int): FloatArray? {
        val image = try { codec.getOutputImage(outIdx) } catch (_: Throwable) { null } ?: return null
        try {
            val plane = image.planes[0]
            val buf: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val cropW = image.width
            val cropH = image.height
            val out = FloatArray(n * n)
            // Nearest-block area sample: average a small box per output texel.
            val sx = cropW.toFloat() / n
            val sy = cropH.toFloat() / n
            for (oy in 0 until n) {
                val y0 = (oy * sy).toInt().coerceIn(0, cropH - 1)
                for (ox in 0 until n) {
                    val x0 = (ox * sx).toInt().coerceIn(0, cropW - 1)
                    val idx = y0 * rowStride + x0 * pixStride
                    out[oy * n + ox] = (buf.get(idx).toInt() and 0xFF).toFloat()
                }
            }
            return out
        } catch (_: Throwable) {
            return null
        } finally {
            image.close()
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw IllegalArgumentException("No video track in $path")
    }
}
