package com.gateshot.processing.stabilize

import kotlin.math.PI
import kotlin.math.abs

/**
 * Real-time (causal) electronic stabilizer.
 *
 * The offline [StabilizerPipeline] (removed) was non-causal: it Gaussian-smoothed
 * the camera path across the *whole clip*, so every output frame depended on
 * future frames. Live, future frames don't exist. This class is the causal
 * replacement — it integrates the gyro stream incrementally into a per-axis
 * orientation and low-passes that path with a **critically-damped second-order
 * filter** (no overshoot, one time-constant). The per-frame correction is
 * `smoothed − raw`: the rotation/translation that pulls the real (shaky)
 * orientation back onto the smooth virtual-camera path. Correction is clamped to
 * the crop margin so the warp never samples outside the frame.
 *
 * Zero added latency: the correction for a frame uses only gyro samples up to that
 * frame. An optional small look-ahead (handled by the caller via a frame buffer)
 * can trade latency for smoothness, but is not required here.
 *
 * Gyro → image mapping is configured via [Config] (axis/sign/scale per image axis),
 * seeded from camera intrinsics and optionally refined online by the Stage-2
 * optical residual through [submitResidual]. All scales are in **normalized image
 * fraction per radian** (so the result plugs straight into the GL warp's
 * normalized translation), except [Config.sRoll] which is radians-per-radian.
 *
 * Public (not `internal`) because the platform `SurfaceProcessor` in another module
 * drives it.
 */
class LiveStabilizer(cfg: Config = Config()) {

    @Volatile private var cfg: Config = cfg

    /** Swap calibration (axis/sign/scale/crop/cutoff) without losing integrator
     *  state — e.g. when the zoom ratio or lens changes. */
    fun updateConfig(newCfg: Config) { cfg = newCfg }

    data class Config(
        /** Low-pass cutoff (Hz) for the virtual-camera path. Below this passes
         *  through (intended panning); above is rejected as shake. */
        val cutoffHz: Float = 0.8f,
        /** Crop margin per side, as a fraction of the dimension. Correction is
         *  clamped to ±cropFrac so the warp stays inside the source frame. */
        val cropFrac: Float = 0.12f,
        /** Gyro axis (0/1/2) and signed scale (norm-fraction per rad) → image X. */
        val axX: Int = 0, val sx: Float = 1f,
        /** Gyro axis and signed scale (norm-fraction per rad) → image Y. */
        val axY: Int = 1, val sy: Float = 1f,
        /** Gyro axis and signed scale (rad per rad) → image roll. */
        val axRoll: Int = 2, val sRoll: Float = 1f,
        val enableRoll: Boolean = true,
        /** Largest gyro gap to integrate; longer gaps (sensor hiccups) are clamped
         *  to avoid a huge single integration step. */
        val maxGapSec: Float = 0.05f,
        /** Time-constant for easing in the Stage-2 residual so it never jumps. */
        val residualTau: Float = 0.5f,
    )

    /** Per-frame correction in normalized image space ([-cropFrac, cropFrac]) + roll (rad). */
    data class Correction(val txNorm: Float, val tyNorm: Float, val rollRad: Float)

    // Raw integrated orientation per gyro axis (rad).
    private val rawAng = DoubleArray(3)
    // Smoothed (virtual-camera) orientation + its velocity, per axis.
    private val smAng = DoubleArray(3)
    private val smVel = DoubleArray(3)
    private var lastTsNs = Long.MIN_VALUE

    // Stage-2 optical residual: target (set by submitResidual) eased toward by the
    // gyro loop. Volatile because submitResidual runs off the gyro/GL thread.
    @Volatile private var resTargetX = 0f
    @Volatile private var resTargetY = 0f
    private var resX = 0f
    private var resY = 0f

    /**
     * Feed one gyro sample. [tsNs] is the BOOTTIME/elapsedRealtimeNanos clock.
     * `@Synchronized` (with [correction]) is load-bearing, not just for atomicity:
     * the gyro arrives on the sensor thread while [correction] is read on the GL
     * render thread, and the integrator state ([rawAng]/[smAng]) is plain arrays.
     * Without the happens-before edge the GL thread can keep reading the initial
     * zeros forever → the warp is a no-op → the app looks fully unstabilized.
     */
    @Synchronized
    fun onGyro(tsNs: Long, gx: Float, gy: Float, gz: Float) {
        val c = cfg
        val g0 = gx.toDouble(); val g1 = gy.toDouble(); val g2 = gz.toDouble()
        if (lastTsNs == Long.MIN_VALUE) {
            lastTsNs = tsNs
            return
        }
        var dt = (tsNs - lastTsNs) / 1e9
        lastTsNs = tsNs
        if (dt <= 0.0) return
        if (dt > c.maxGapSec) dt = c.maxGapSec.toDouble()

        // Integrate raw orientation (left Riemann; gyro is fast vs. the dynamics).
        rawAng[0] += g0 * dt
        rawAng[1] += g1 * dt
        rawAng[2] += g2 * dt

        // Critically-damped 2nd-order low-pass toward the raw path (semi-implicit
        // Euler: update velocity from current position, then position from new
        // velocity — stable for w·dt well below 1).
        val w = 2.0 * PI * c.cutoffHz
        val w2 = w * w
        for (a in 0 until 3) {
            smVel[a] += dt * (w2 * (rawAng[a] - smAng[a]) - 2.0 * w * smVel[a])
            smAng[a] += dt * smVel[a]
        }

        // Ease the optical residual toward its target on the same clock.
        val alpha = (dt / (c.residualTau + dt)).toFloat()
        resX += (resTargetX - resX) * alpha
        resY += (resTargetY - resY) * alpha
    }

    /**
     * Current correction (the rotation/translation to apply to the live frame).
     * Pure read of filter state — call it once per video frame.
     */
    @Synchronized
    fun correction(): Correction {
        val c = cfg
        val dx = smAng[c.axX] - rawAng[c.axX]
        val dy = smAng[c.axY] - rawAng[c.axY]
        var tx = (c.sx * dx).toFloat() + resX
        var ty = (c.sy * dy).toFloat() + resY
        val roll = if (c.enableRoll)
            (c.sRoll * (smAng[c.axRoll] - rawAng[c.axRoll])).toFloat() else 0f
        val m = c.cropFrac
        tx = tx.coerceIn(-m, m)
        ty = ty.coerceIn(-m, m)
        return Correction(tx, ty, roll)
    }

    /**
     * Submit a Stage-2 optical-residual offset (normalized image fraction). Eased
     * in over [Config.residualTau]; safe to call from a background thread.
     */
    fun submitResidual(dxNorm: Float, dyNorm: Float) {
        val m = cfg.cropFrac
        resTargetX = dxNorm.coerceIn(-m, m)
        resTargetY = dyNorm.coerceIn(-m, m)
    }

    /** Current gyro→image config (axis/sign/scale/crop). */
    fun config(): Config = cfg

    /** Reset all integrator/filter state (e.g. on rebind or a long pause). */
    @Synchronized
    fun reset() {
        rawAng.fill(0.0); smAng.fill(0.0); smVel.fill(0.0)
        lastTsNs = Long.MIN_VALUE
        resTargetX = 0f; resTargetY = 0f; resX = 0f; resY = 0f
    }

    /** Diagnostics: how much shake (rad, single-axis abs) the smoother is removing. */
    fun residualMagnitudeRad(): Float {
        val c = cfg
        return (abs(smAng[c.axX] - rawAng[c.axX]) + abs(smAng[c.axY] - rawAng[c.axY])).toFloat()
    }

    /** Raw integrated orientation of [axis] (rad). Diagnostics / tests. */
    fun rawAngle(axis: Int): Double = rawAng[axis]

    /** Smoothed (virtual-camera) orientation of [axis] (rad). Diagnostics / tests. */
    fun smoothedAngle(axis: Int): Double = smAng[axis]

    companion object {
        /**
         * Build a [Config] from camera intrinsics. [fxPx]/[fyPx] are the focal
         * lengths in pixels (already multiplied by the digital-zoom ratio);
         * [width]/[height] the processed frame size. For small angles a yaw of
         * θ rad shifts the image by `fx·θ` px, i.e. `fx/width·θ` of the frame.
         *
         * Axis/sign default to the common rear-camera mounting (yaw→X about the
         * device Y axis, pitch→Y about X, roll about Z); Stage-2 verifies/flips.
         */
        fun fromIntrinsics(
            fxPx: Float, fyPx: Float, width: Int, height: Int,
            cropFrac: Float = 0.12f, cutoffHz: Float = 0.8f,
            axX: Int = 1, signX: Float = -1f,
            axY: Int = 0, signY: Float = 1f,
            axRoll: Int = 2, signRoll: Float = 1f,
            enableRoll: Boolean = true,
        ): Config = Config(
            cutoffHz = cutoffHz,
            cropFrac = cropFrac,
            axX = axX, sx = signX * fxPx / width,
            axY = axY, sy = signY * fyPx / height,
            axRoll = axRoll, sRoll = signRoll,
            enableRoll = enableRoll,
        )
    }
}
