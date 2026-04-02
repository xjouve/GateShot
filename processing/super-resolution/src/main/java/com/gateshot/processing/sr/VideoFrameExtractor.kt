package com.gateshot.processing.sr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import java.io.File
import java.nio.ByteBuffer

/**
 * Hardware-accelerated video frame extraction using MediaCodec.
 *
 * Decodes individual frames from recorded 4K video files for feeding into
 * PoseEstimationModule during coaching replay. Uses the Dimensity 9500's
 * hardware video decoder for efficient extraction without re-encoding.
 *
 * At 4K@120fps, a 10-second clip contains 1200 frames. Extracting every
 * frame would be wasteful — the caller typically wants every Nth frame
 * (e.g., every 4th frame = 30 poses/sec, enough for angle analysis).
 */
class VideoFrameExtractor {

    data class ExtractedFrame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val timestampUs: Long,
        val frameIndex: Int
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = pixels.contentHashCode()
    }

    /**
     * Extract frames from a video file at specified time positions.
     *
     * @param videoPath Path to the MP4 video file
     * @param timestampsUs List of timestamps in microseconds to extract frames at
     * @return List of extracted frames with pixel data
     */
    fun extractFramesAt(videoPath: String, timestampsUs: List<Long>): List<ExtractedFrame> {
        val results = mutableListOf<ExtractedFrame>()
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(videoPath)

            // Find video track
            val videoTrackIndex = findVideoTrack(extractor) ?: return emptyList()
            extractor.selectTrack(videoTrackIndex)
            val format = extractor.getTrackFormat(videoTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)

            // Configure decoder
            val decoder = MediaCodec.createDecoderByType(mime)
            val outputFormat = MediaFormat.createVideoFormat(mime, width, height)
            decoder.configure(outputFormat, null, null, 0)
            decoder.start()

            val sortedTimestamps = timestampsUs.sorted()
            var timestampIdx = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameIndex = 0

            while (!outputDone && timestampIdx < sortedTimestamps.size) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIdx = decoder.dequeueInputBuffer(10_000)
                    if (inputBufferIdx >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIdx)!!

                        // Seek to the nearest keyframe before the target timestamp
                        extractor.seekTo(sortedTimestamps[timestampIdx], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufferIdx, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outputBufferIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val presentationTimeUs = bufferInfo.presentationTimeUs

                    // Check if this frame's timestamp is close to a requested timestamp
                    if (timestampIdx < sortedTimestamps.size) {
                        val targetUs = sortedTimestamps[timestampIdx]
                        val toleranceUs = 50_000L  // 50ms tolerance

                        if (kotlin.math.abs(presentationTimeUs - targetUs) <= toleranceUs) {
                            // Extract pixel data from this frame
                            val outputImage = decoder.getOutputImage(outputBufferIdx)
                            if (outputImage != null) {
                                val pixels = yuvImageToArgb(outputImage, width, height)
                                results.add(ExtractedFrame(
                                    pixels = pixels,
                                    width = width,
                                    height = height,
                                    timestampUs = presentationTimeUs,
                                    frameIndex = frameIndex
                                ))
                                outputImage.close()
                            }
                            timestampIdx++
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIdx, false)
                    frameIndex++
                }
            }

            decoder.stop()
            decoder.release()
        } catch (e: Exception) {
            // Return whatever frames we managed to extract
        } finally {
            extractor.release()
        }

        return results
    }

    /**
     * Extract frames at regular intervals throughout the video.
     *
     * @param videoPath Path to the MP4 video file
     * @param intervalMs Interval between frames in milliseconds
     * @param maxFrames Maximum number of frames to extract
     * @return List of extracted frames
     */
    fun extractFramesAtInterval(
        videoPath: String,
        intervalMs: Long = 100,
        maxFrames: Int = 300
    ): List<ExtractedFrame> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            val trackIdx = findVideoTrack(extractor) ?: return emptyList()
            val format = extractor.getTrackFormat(trackIdx)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            val intervalUs = intervalMs * 1000
            val timestamps = mutableListOf<Long>()
            var t = 0L
            while (t < durationUs && timestamps.size < maxFrames) {
                timestamps.add(t)
                t += intervalUs
            }

            extractor.release()
            return extractFramesAt(videoPath, timestamps)
        } catch (e: Exception) {
            extractor.release()
            return emptyList()
        }
    }

    /**
     * Get video metadata without extracting frames.
     */
    fun getVideoInfo(videoPath: String): VideoInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoPath)
            val trackIdx = findVideoTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(trackIdx)

            VideoInfo(
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000,
                frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    format.getInteger(MediaFormat.KEY_FRAME_RATE) else 30,
                mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            )
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val frameRate: Int,
        val mime: String
    )

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    /**
     * Convert a YUV_420_888 Image to ARGB pixel array.
     * MediaCodec outputs frames in YUV format; we need ARGB for processing.
     */
    private fun yuvImageToArgb(image: Image, width: Int, height: Int): IntArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIdx = row * yRowStride + col
                val uvRow = row / 2
                val uvCol = col / 2
                val uIdx = uvRow * uvRowStride + uvCol * uvPixelStride
                val vIdx = uvRow * uvRowStride + uvCol * uvPixelStride

                val y = (yBuffer.get(yIdx).toInt() and 0xFF).toFloat()
                val u = (uBuffer.get(uIdx).toInt() and 0xFF).toFloat() - 128f
                val v = (vBuffer.get(vIdx).toInt() and 0xFF).toFloat() - 128f

                // BT.601 YUV to RGB conversion
                var r = (y + 1.402f * v).toInt()
                var g = (y - 0.344f * u - 0.714f * v).toInt()
                var b = (y + 1.772f * u).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return pixels
    }
}
