package com.gateshot.processing.stabilize

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One-shot offline analysis of an imported clip for playback-time smoothing:
 * decode frames → downscaled luma → phase-correlate consecutive frames →
 * integrate to a camera path → Gaussian-smooth → per-frame correction =
 * smoothed − actual, clamped to the crop margin.
 *
 * Everything is measured on the display-oriented frames MediaMetadataRetriever
 * returns, so corrections are directly in display space — no rotation math
 * (ticket 020, attempt 4).
 *
 * The correction SIGN is verified closed-loop: a sample of analysis frames is
 * warped by ±correction and the sign that actually reduces re-measured
 * inter-frame jitter wins. Ticket 020's hard lesson: the wrong sign doesn't
 * just fail, it doubles the shake.
 */
class PlaybackStabilizer {

    data class Track(
        /**
         * Per-analyzed-frame content correction as a fraction of frame
         * width/height, in CODED (unrotated) frame space — getFrameAtIndex
         * does not apply rotation metadata (unlike getFrameAtTime).
         */
        val dxFrac: FloatArray,
        val dyFrac: FloatArray,
        val frameIntervalMs: Float,
        val cropFactor: Float,
        /** Clockwise rotation applied to the coded frame for display. */
        val rotationDeg: Int,
        /** Measured jitter reduction on the verification sample, 0..1. */
        val jitterReduction: Float
    ) {
        /** Linearly interpolated coded-space correction at a playback position. */
        fun correctionAt(positionMs: Long): Pair<Float, Float> {
            if (dxFrac.isEmpty()) return 0f to 0f
            val f = (positionMs / frameIntervalMs).coerceIn(0f, (dxFrac.size - 1).toFloat())
            val i0 = f.toInt()
            val i1 = (i0 + 1).coerceAtMost(dxFrac.size - 1)
            val t = f - i0
            return (dxFrac[i0] + (dxFrac[i1] - dxFrac[i0]) * t) to
                   (dyFrac[i0] + (dyFrac[i1] - dyFrac[i0]) * t)
        }

        /**
         * Correction rotated into DISPLAY space (what a view translation
         * needs): a coded-space vector rotated by [rotationDeg] clockwise.
         */
        fun displayCorrectionAt(positionMs: Long): Pair<Float, Float> {
            val (dx, dy) = correctionAt(positionMs)
            return when (((rotationDeg % 360) + 360) % 360) {
                90 -> -dy to dx
                180 -> -dx to -dy
                270 -> dy to -dx
                else -> dx to dy
            }
        }
    }

    /**
     * Analyze a clip. Returns null if the video can't be decoded or is too
     * short to stabilize. Safe to cancel (checks the coroutine context).
     */
    suspend fun analyze(
        videoPath: String,
        onProgress: (Float) -> Unit = {}
    ): Track? = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val frameCount = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: return@withContext null
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toFloatOrNull() ?: return@withContext null
            val rotationDeg = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (frameCount < MIN_FRAMES || durationMs <= 0f) return@withContext null

            // Bound the analysis cost on long clips by striding frames.
            val stride = ((frameCount + MAX_ANALYZED_FRAMES - 1) / MAX_ANALYZED_FRAMES)
                .coerceAtLeast(1)
            val analyzed = frameCount / stride
            val frameIntervalMs = durationMs / frameCount * stride

            val correlator = PhaseCorrelator(ANALYSIS_SIZE)
            // Keep a sample of luma frames for the closed-loop sign check.
            val keptLumas = ArrayList<FloatArray>(SIGN_CHECK_FRAMES)

            // Pass 1: per-frame motion.
            val dxRaw = FloatArray(analyzed)
            val dyRaw = FloatArray(analyzed)
            var prev: FloatArray? = null
            for (i in 0 until analyzed) {
                coroutineContext.ensureActive()
                val frame = retriever.getFrameAtIndex(i * stride) ?: continue
                val luma = toLuma(frame)
                frame.recycle()
                prev?.let { p ->
                    val r = correlator.correlate(p, luma)
                    dxRaw[i] = r[0]
                    dyRaw[i] = r[1]
                }
                if (keptLumas.size < SIGN_CHECK_FRAMES) keptLumas.add(luma)
                prev = luma
                if (i % 16 == 0) onProgress(i.toFloat() / analyzed * 0.9f)
            }

            // Integrate to a cumulative path, smooth it, correction = smooth − raw.
            val pathX = FloatArray(analyzed)
            val pathY = FloatArray(analyzed)
            for (i in 1 until analyzed) {
                pathX[i] = pathX[i - 1] + dxRaw[i]
                pathY[i] = pathY[i - 1] + dyRaw[i]
            }
            val smoothX = PathSmoother.gaussian1d(pathX, SMOOTH_SIGMA_FRAMES)
            val smoothY = PathSmoother.gaussian1d(pathY, SMOOTH_SIGMA_FRAMES)
            val maxShiftPx = MAX_SHIFT_FRAC * ANALYSIS_SIZE
            val corrX = FloatArray(analyzed) {
                (smoothX[it] - pathX[it]).coerceIn(-maxShiftPx, maxShiftPx)
            }
            val corrY = FloatArray(analyzed) {
                (smoothY[it] - pathY[it]).coerceIn(-maxShiftPx, maxShiftPx)
            }

            // Closed-loop sign check on the kept sample.
            onProgress(0.92f)
            val (sign, reduction) = resolveSign(correlator, keptLumas, corrX, corrY)

            onProgress(1f)
            Track(
                dxFrac = FloatArray(analyzed) { sign * corrX[it] / ANALYSIS_SIZE },
                dyFrac = FloatArray(analyzed) { sign * corrY[it] / ANALYSIS_SIZE },
                frameIntervalMs = frameIntervalMs,
                cropFactor = CROP_FACTOR,
                rotationDeg = rotationDeg,
                jitterReduction = reduction
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Stabilization analysis failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /**
     * Warp the sample frames by ±correction, re-measure inter-frame jitter,
     * and return the sign that reduces it (with the achieved reduction).
     * If neither direction helps (already-smooth footage), corrections are
     * near zero anyway; default to +1.
     */
    private fun resolveSign(
        correlator: PhaseCorrelator,
        lumas: List<FloatArray>,
        corrX: FloatArray,
        corrY: FloatArray
    ): Pair<Float, Float> {
        if (lumas.size < 8) return 1f to 0f

        fun jitter(sign: Float): Float {
            var sum = 0f
            var prevWarped: FloatArray? = null
            for (i in lumas.indices) {
                val warped = if (sign == 0f) lumas[i] else shift(
                    lumas[i],
                    (sign * corrX[i]).roundToInt(),
                    (sign * corrY[i]).roundToInt()
                )
                prevWarped?.let { p ->
                    val r = correlator.correlate(p, warped)
                    sum += abs(r[0]) + abs(r[1])
                }
                prevWarped = warped
            }
            return sum
        }

        val raw = jitter(0f)
        val plus = jitter(1f)
        val minus = jitter(-1f)
        val best = minOf(plus, minus)
        if (raw <= 1e-3f || best >= raw) return 1f to 0f
        val sign = if (plus <= minus) 1f else -1f
        return sign to (1f - best / raw)
    }

    /** Integer shift with edge clamp, in analysis-resolution pixels. */
    private fun shift(src: FloatArray, dx: Int, dy: Int): FloatArray {
        if (dx == 0 && dy == 0) return src
        val n = ANALYSIS_SIZE
        val out = FloatArray(n * n)
        for (y in 0 until n) {
            val sy = (y - dy).coerceIn(0, n - 1)
            for (x in 0 until n) {
                val sx = (x - dx).coerceIn(0, n - 1)
                out[y * n + x] = src[sy * n + sx]
            }
        }
        return out
    }

    private fun toLuma(frame: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(frame, ANALYSIS_SIZE, ANALYSIS_SIZE, true)
        val pixels = IntArray(ANALYSIS_SIZE * ANALYSIS_SIZE)
        scaled.getPixels(pixels, 0, ANALYSIS_SIZE, 0, 0, ANALYSIS_SIZE, ANALYSIS_SIZE)
        if (scaled !== frame) scaled.recycle()
        return FloatArray(pixels.size) { i ->
            val p = pixels[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
    }

    companion object {
        private const val TAG = "PlaybackStabilizer"

        /** Working resolution for motion estimation (power of two for the FFT). */
        private const val ANALYSIS_SIZE = 128

        /** Native clips are already EIS-stabilized; this is residual polish,
         *  so a modest crop suffices (ticket 020 needed 1.30 on raw footage). */
        const val CROP_FACTOR = 1.15f

        /** Max view shift per axis = the margin the crop creates. */
        private const val MAX_SHIFT_FRAC = (1f - 1f / CROP_FACTOR) / 2f

        /** ~0.27 s at 30 fps: kills jitter, follows intentional panning. */
        private const val SMOOTH_SIGMA_FRAMES = 8f

        private const val MAX_ANALYZED_FRAMES = 600
        private const val MIN_FRAMES = 10
        private const val SIGN_CHECK_FRAMES = 90
    }
}
