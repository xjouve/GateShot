package com.gateshot.coaching.replay

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the course-reference frame set from an imported clip, replacing the
 * live panning-scan capture that was removed with the in-app camera.
 *
 * The coach films a slow pan across the blank course with the native camera
 * app (or the reference is taken from a wide static view). This class samples
 * the clip, measures horizontal panning between samples by matching
 * column-mean luma projections (the cheap estimator validated in ticket 020,
 * 0.94–0.98 agreement with phase correlation), and keeps one anchor frame
 * every ~70% of frame width of accumulated pan — exactly the fixed step
 * [CourseReferenceCapture]'s stitcher expects. A static clip yields a single
 * frame, which is a perfectly valid reference for fixed-position filming.
 *
 * Frames come from getScaledFrameAtTime, which applies rotation metadata, so
 * everything downstream (stitching, gate detection) is in display space.
 */
internal class VideoReferenceBuilder {

    fun selectFrames(
        videoPath: String,
        onFrameSelected: (Int) -> Unit = {}
    ): List<CourseReferenceCapture.CapturedFrame> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return emptyList()
            if (durationMs <= 0) return emptyList()

            val anchors = mutableListOf<CourseReferenceCapture.CapturedFrame>()
            var prevProjection: FloatArray? = null
            var cumulativePanPx = 0f
            var lastAnchorPanPx = 0f
            var frameWidth = 0

            var tMs = 0L
            while (tMs < durationMs && anchors.size < MAX_ANCHORS) {
                val bitmap = retriever.getScaledFrameAtTime(
                    tMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MAX_DIM, MAX_DIM
                )
                if (bitmap != null) {
                    val w = bitmap.width
                    val h = bitmap.height
                    if (frameWidth == 0) frameWidth = w
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()

                    val projection = columnMeans(pixels, w, h)
                    prevProjection?.let { prev ->
                        cumulativePanPx += bestShift(prev, projection)
                    }
                    prevProjection = projection

                    val isFirst = anchors.isEmpty()
                    val pannedEnough =
                        abs(cumulativePanPx - lastAnchorPanPx) >= frameWidth * ANCHOR_STEP_FRAC
                    if (isFirst || pannedEnough) {
                        anchors.add(
                            CourseReferenceCapture.CapturedFrame(
                                pixels = pixels,
                                width = w,
                                height = h,
                                // The stitcher keys off list order, not this value;
                                // keep it for diagnostics.
                                rotationDegrees = cumulativePanPx / frameWidth * 40f,
                                timestamp = tMs
                            )
                        )
                        lastAnchorPanPx = cumulativePanPx
                        onFrameSelected(anchors.size)
                    }
                }
                tMs += SAMPLE_INTERVAL_MS
            }

            // The stitcher concatenates left-to-right in list order. Camera
            // panning right (content shifts left, positive correlation shift)
            // sees the left of the scene first — time order is already scene
            // order. A leftward pan needs reversing.
            if (cumulativePanPx < 0) anchors.reverse()

            return anchors
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Reference frame selection failed: ${e.message}")
            return emptyList()
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /** Mean luma per column — a 1-D signature of the frame's horizontal layout. */
    private fun columnMeans(pixels: IntArray, width: Int, height: Int): FloatArray {
        val means = FloatArray(width)
        for (x in 0 until width) {
            var sum = 0f
            var y = 0
            while (y < height) {
                val p = pixels[y * width + x]
                sum += 0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)
                y += 2
            }
            means[x] = sum
        }
        // Zero-mean so correlation isn't dominated by overall brightness.
        val avg = means.average().toFloat()
        for (i in means.indices) means[i] -= avg
        return means
    }

    /**
     * Horizontal shift (in px) of [cur]'s content relative to [prev],
     * found by maximizing the overlap correlation over ±half the width.
     */
    private fun bestShift(prev: FloatArray, cur: FloatArray): Float {
        val w = minOf(prev.size, cur.size)
        val maxShift = w / 2
        var bestS = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (s in -maxShift..maxShift step 2) {
            var score = 0f
            var count = 0
            var x = maxOf(0, -s)
            val xEnd = minOf(w, w - s)
            while (x < xEnd) {
                score += prev[x + s] * cur[x]
                x += 2
                count++
            }
            if (count > w / 8) {
                val normalized = score / count
                if (normalized > bestScore) {
                    bestScore = normalized
                    bestS = s
                }
            }
        }
        return bestS.toFloat()
    }

    companion object {
        private const val TAG = "VideoRefBuilder"
        private const val SAMPLE_INTERVAL_MS = 400L
        private const val MAX_DIM = 640
        private const val MAX_ANCHORS = 12

        /** Matches the fixed 30%-overlap step in CourseReferenceCapture. */
        private const val ANCHOR_STEP_FRAC = 0.7f
    }
}
