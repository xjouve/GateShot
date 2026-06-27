package com.gateshot.processing.stabilize

import kotlin.math.abs

/**
 * Stage-2 optical-residual refinement for the live stabilizer.
 *
 * The gyro path ([LiveStabilizer]) cancels most shake, but misses what the gyro
 * can't see: OIS lens motion, gyro bias, calibration error, and translational
 * (parallax) jitter. This tracker measures the **leftover** inter-frame motion of
 * the already-gyro-stabilized output via phase correlation on small downscaled
 * frames, and feeds a slow corrective offset back through
 * [LiveStabilizer.submitResidual]. It runs entirely off the GL render thread and
 * is best-effort: if frames don't arrive (GL/readback budget exceeded) it simply
 * contributes nothing and the system stays gyro-only.
 *
 * Closed-loop safety: because it reads back the *warped* output (which already
 * includes the correction it produced), the loop is self-correcting **only if the
 * sign convention matches**. A wrong sign would be positive feedback — the bias
 * would saturate at the crop limit while residual stays high. We detect exactly
 * that (sustained saturation without residual decreasing) and flip the sign, so
 * the tracker self-heals without needing the sign calibrated up front. Intended
 * panning is preserved by integrating only the high-frequency (AC) component and
 * leaking the bias slowly.
 *
 * Public so the platform `SurfaceProcessor` (other module) can feed it luma.
 */
class OpticalResidualTracker(
    private val stabilizer: LiveStabilizer,
    private val n: Int = 256,
    private val cfg: Config = Config(),
) {
    data class Config(
        /** Max correction magnitude (normalized), well inside the crop margin. */
        val clamp: Float = 0.06f,
        /** Integration gain on the AC residual per measurement. */
        val gain: Float = 0.5f,
        /** Per-measurement bias leak (→ time-constant ~ 1/(1-leak) samples). */
        val leak: Float = 0.995f,
        /** Slow-average smoothing for pan rejection (0..1 per measurement). */
        val panAlpha: Float = 0.08f,
        /** Reject a measurement whose per-frame motion exceeds this (normalized);
         *  almost always a phase-correlation mismatch, not real motion. */
        val maxStep: Float = 0.05f,
        /** Reject measurements with a weak correlation peak (low confidence). */
        val minPeak: Float = 1e-4f,
        /** Samples of sustained saturation+high-residual before flipping sign. */
        val flipAfter: Int = 24,
    )

    private val corr = PhaseCorrelator(n)
    private var prev: FloatArray? = null

    private var biasX = 0f
    private var biasY = 0f
    private var panX = 0f
    private var panY = 0f
    private var sign = 1f
    private var satCount = 0

    @Volatile private var dropped = 0L
    @Volatile private var measured = 0L

    /**
     * Feed one downscaled luma frame (row-major n×n, any scale) captured at [tsNs].
     * Call from a background thread; cheap-ish (one 2D FFT pair). The first call
     * just seeds the reference frame.
     */
    fun submitFrame(luma: FloatArray, tsNs: Long) {
        val p = prev
        if (p == null || luma.size != n * n) {
            if (luma.size == n * n) prev = luma
            return
        }
        val r = corr.correlate(p, luma)
        prev = luma
        measured++

        val peak = r[2]
        if (peak < cfg.minPeak) return
        // Inter-frame motion of the (warped) output, normalized to frame fraction.
        val mx = r[0] / n
        val my = r[1] / n
        if (abs(mx) > cfg.maxStep || abs(my) > cfg.maxStep) {
            dropped++
            return
        }

        // Pan rejection: integrate only the AC (jitter) part of the motion.
        panX += (mx - panX) * cfg.panAlpha
        panY += (my - panY) * cfg.panAlpha
        val acx = mx - panX
        val acy = my - panY

        // Drive the residual to zero. With the correct sign this is negative
        // feedback; the auto-flip below handles a wrong sign.
        biasX = (biasX - sign * cfg.gain * acx) * cfg.leak
        biasY = (biasY - sign * cfg.gain * acy) * cfg.leak
        biasX = biasX.coerceIn(-cfg.clamp, cfg.clamp)
        biasY = biasY.coerceIn(-cfg.clamp, cfg.clamp)

        // Sign safety: if the bias is pinned at the limit while residual stays
        // large, the loop is diverging → flip the sign and reset.
        val saturated = abs(biasX) > 0.95f * cfg.clamp || abs(biasY) > 0.95f * cfg.clamp
        val highResidual = abs(acx) > cfg.maxStep * 0.1f || abs(acy) > cfg.maxStep * 0.1f
        if (saturated && highResidual) {
            if (++satCount >= cfg.flipAfter) {
                sign = -sign
                biasX = 0f; biasY = 0f; satCount = 0
            }
        } else {
            satCount = 0
        }

        stabilizer.submitResidual(biasX, biasY)
    }

    fun reset() {
        prev = null
        biasX = 0f; biasY = 0f; panX = 0f; panY = 0f
        sign = 1f; satCount = 0
        dropped = 0; measured = 0
        stabilizer.submitResidual(0f, 0f)
    }

    /** Diagnostics: measurements processed / dropped, and the detected sign. */
    fun stats(): String = "measured=$measured dropped=$dropped sign=$sign bias=($biasX,$biasY)"

    /** Current detected sign (±1). Exposed for tests/diagnostics. */
    fun detectedSign(): Float = sign

    /** Current residual correction (normalized). Exposed for tests/diagnostics. */
    fun residual(): Pair<Float, Float> = biasX to biasY
}
