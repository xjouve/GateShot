package com.gateshot.videoenhance

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Derives a one-tap color correction for an imported clip. Samples a few
 * frames, then builds a ColorMatrix from three classic snow-footage fixes:
 *
 * - gray-world white balance (snow casts blue in shade, warm in low sun)
 * - exposure lift toward bright-but-unclipped snow (cameras underexpose
 *   snow scenes toward 18% gray)
 * - a touch of contrast and saturation against flat-light haze
 *
 * Applied at playback via a ColorMatrixColorFilter render effect — the video
 * file itself is never modified.
 */
class AutoColorAnalyzer {

    /** Returns the 4x5 color matrix, or null if the clip can't be sampled. */
    suspend fun analyze(videoPath: String): ColorMatrix? = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@withContext null

            var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
            val lumas = ArrayList<Float>(SAMPLE_FRAMES * SAMPLE_SIZE * SAMPLE_SIZE)

            var sampled = 0
            for (i in 0 until SAMPLE_FRAMES) {
                val tUs = durationMs * 1000L * (2 * i + 1) / (2 * SAMPLE_FRAMES)
                val frame = retriever.getFrameAtTime(
                    tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val scaled = Bitmap.createScaledBitmap(frame, SAMPLE_SIZE, SAMPLE_SIZE, true)
                if (scaled !== frame) frame.recycle()
                val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
                scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
                scaled.recycle()
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sumR += r; sumG += g; sumB += b
                    lumas.add(0.299f * r + 0.587f * g + 0.114f * b)
                }
                sampled++
            }
            if (sampled == 0 || lumas.isEmpty()) return@withContext null

            val n = lumas.size.toDouble()
            val meanR = (sumR / n).toFloat()
            val meanG = (sumG / n).toFloat()
            val meanB = (sumB / n).toFloat()

            // Gray-world white balance, clamped so a legitimately colorful
            // scene isn't washed out.
            val gray = (meanR + meanG + meanB) / 3f
            val wbR = (gray / meanR.coerceAtLeast(1f)).coerceIn(WB_MIN, WB_MAX)
            val wbG = (gray / meanG.coerceAtLeast(1f)).coerceIn(WB_MIN, WB_MAX)
            val wbB = (gray / meanB.coerceAtLeast(1f)).coerceIn(WB_MIN, WB_MAX)

            // Exposure: push the bright end (95th percentile ≈ snow) toward
            // near-white without clipping.
            lumas.sort()
            val p95 = lumas[(lumas.size * 95 / 100).coerceAtMost(lumas.size - 1)]
            val exposure = (EXPOSURE_TARGET / p95.coerceAtLeast(1f)).coerceIn(EXP_MIN, EXP_MAX)

            // Gentle contrast around mid-gray.
            val c = CONTRAST
            val offset = 128f * (1f - c)

            val matrix = ColorMatrix().apply { setSaturation(SATURATION) }
            matrix.postConcat(ColorMatrix(floatArrayOf(
                wbR * exposure * c, 0f, 0f, 0f, offset,
                0f, wbG * exposure * c, 0f, 0f, offset,
                0f, 0f, wbB * exposure * c, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
            matrix
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Color analysis failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    companion object {
        private const val TAG = "AutoColor"
        private const val SAMPLE_FRAMES = 5
        private const val SAMPLE_SIZE = 64
        private const val WB_MIN = 0.85f
        private const val WB_MAX = 1.2f
        private const val EXPOSURE_TARGET = 235f
        private const val EXP_MIN = 0.9f
        private const val EXP_MAX = 1.35f
        private const val CONTRAST = 1.05f
        private const val SATURATION = 1.1f
    }
}
