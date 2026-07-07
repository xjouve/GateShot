package com.gateshot.processing.stabilize

import android.util.Log
import kotlin.math.abs

/**
 * On-device auto-calibration of the gyro→image mapping for the live stabilizer.
 *
 * The intrinsic-derived scale is physically correct in magnitude, but **which**
 * gyro axis drives image-X vs image-Y, and the signs, depend on device
 * orientation (GateShot records landscape), sensor mounting, and the periscope
 * 180° flip — too many unknowns to hardcode. This is what made the offline
 * stabilizer work: regress measured optical motion against the gyro and let the
 * data pick axis/sign/scale.
 *
 * Flow (a small state machine driven by the GL readback + gyro feed):
 *  1. COLLECTING — the warp is held at identity (preview is a static crop), so the
 *     readback measures *raw* camera motion. Phase-correlate consecutive frames
 *     and regress that motion against per-axis integrated-gyro deltas. Once the
 *     fit is confident, apply it ([LiveStabilizer.updateConfig]) and turn the warp
 *     on.
 *  2. VERIFYING — with the warp on, measure the *residual* motion. If it didn't
 *     drop below the raw baseline, the only remaining unknown — the warp-vs-optical
 *     sign convention — is wrong: flip the signs once and re-check.
 *  3. DONE — hand frames to the residual tracker for ongoing refinement.
 *
 * Threading: [onGyro] runs on the sensor thread, [submitFrame] on the readback
 * background thread; the gyro history is the only shared state (guarded).
 */
class OnlineCalibrator(
    private val stabilizer: LiveStabilizer,
    private val cropFrac: Float,
    private val cutoffHz: Float,
    private val n: Int = 256,
    enableResidual: Boolean = true,
) {
    /** Lets the calibrator hold the warp at identity while measuring raw motion. */
    interface WarpController { fun setWarpActive(active: Boolean) }

    private var controller: WarpController? = null
    fun setController(c: WarpController) {
        controller = c
        c.setWarpActive(false)   // start in COLLECTING → raw readback
    }

    private val corr = PhaseCorrelator(n)
    private val residual =
        if (enableResidual) OpticalResidualTracker(stabilizer, n,
            OpticalResidualTracker.Config(clamp = cropFrac * 0.5f)) else null

    // ── Gyro history (ts → integrated angle), written on the sensor thread ──
    private val gLock = Any()
    private val gHist = ArrayDeque<DoubleArray>(1024)   // each: [ts, a0, a1, a2]
    private val curAng = DoubleArray(3)
    private var lastGyroTs = Long.MIN_VALUE

    fun onGyro(tsNs: Long, gx: Float, gy: Float, gz: Float) {
        synchronized(gLock) {
            if (lastGyroTs != Long.MIN_VALUE) {
                var dt = (tsNs - lastGyroTs) / 1e9
                if (dt in 0.0..0.05) {
                    curAng[0] += gx * dt; curAng[1] += gy * dt; curAng[2] += gz * dt
                }
            }
            lastGyroTs = tsNs
            gHist.addLast(doubleArrayOf(tsNs.toDouble(), curAng[0], curAng[1], curAng[2]))
            val cutoff = tsNs - 2_000_000_000L   // keep ~2 s
            while (gHist.isNotEmpty() && gHist.first()[0] < cutoff) gHist.removeFirst()
        }
    }

    private fun angleAt(tsNs: Long): DoubleArray? = synchronized(gLock) {
        val sz = gHist.size
        if (sz < 2) return null
        val t = tsNs.toDouble()
        // Each entry is [ts, a0, a1, a2]; return only the 3 angle components.
        if (t <= gHist[0][0]) return angleOf(gHist[0])
        if (t >= gHist[sz - 1][0]) return angleOf(gHist[sz - 1])
        // Linear scan from the end (frames are recent).
        var hi = gHist[sz - 1]
        for (i in sz - 2 downTo 0) {
            val lo = gHist[i]
            if (lo[0] <= t) {
                val f = (t - lo[0]) / (hi[0] - lo[0])
                return DoubleArray(3) { a -> lo[1 + a] + f * (hi[1 + a] - lo[1 + a]) }
            }
            hi = lo
        }
        angleOf(gHist[0])
    }

    private fun angleOf(e: DoubleArray) = doubleArrayOf(e[1], e[2], e[3])

    // ── Sample accumulation (readback thread only) ──
    private enum class State { COLLECTING, VERIFYING, DONE }
    private var state = State.COLLECTING

    private var prevLuma: FloatArray? = null
    private var prevTs = 0L
    private var prevAng: DoubleArray? = null

    private val dA = ArrayList<DoubleArray>(256)
    private val mdx = ArrayList<Double>(256)
    private val mdy = ArrayList<Double>(256)
    private var rawEnergy = 0.0
    private var collectFrames = 0

    // verify
    private var verifyResidual = 0.0
    private var verifyMotion = 0.0
    private var verifyFrames = 0
    private var signFlipped = false

    // result
    private var axX = 1; private var sxN = 0f
    private var axY = 0; private var syN = 0f

    /** Total readback frames seen while COLLECTING — bounds the raw-measurement
     *  window by wall-clock so the warp always engages (see [collect]). */
    private var totalFrames = 0

    /** True once a confident data-driven fit was applied (not the intrinsic
     *  fallback). Lets the platform preserve the learned axis/sign across zoom
     *  changes instead of reverting to the intrinsic default. */
    @Volatile private var fitted = false
    fun hasFit(): Boolean = fitted

    fun submitFrame(luma: FloatArray, tsNs: Long) {
        if (luma.size != n * n) return
        when (state) {
            State.COLLECTING -> collect(luma, tsNs)
            State.VERIFYING -> verify(luma, tsNs)
            State.DONE -> residual?.submitFrame(luma, tsNs)
        }
    }

    private fun motion(prev: FloatArray, cur: FloatArray): FloatArray? {
        val r = corr.correlate(prev, cur)
        if (r[2] < MIN_PEAK) return null
        if (abs(r[0]) > MAX_STEP * n || abs(r[1]) > MAX_STEP * n) return null
        return r
    }

    private fun collect(luma: FloatArray, tsNs: Long) {
        totalFrames++
        // Wall-clock bound on the raw-measurement window, checked on *every*
        // readback frame. In gentle handheld filming few frames clear
        // MIN_GYRO_STEP, so this must precede the informative-sample gating below
        // (which early-returns on near-still frames) — otherwise the warp could
        // stay at identity indefinitely, which was the "fully unstabilized" bug.
        if (totalFrames >= ACTIVATE_AFTER) { fallbackDone(); return }
        val ang = angleAt(tsNs)
        val pl = prevLuma; val pa = prevAng
        prevLuma = luma; prevTs = tsNs; if (ang != null) prevAng = ang
        if (pl == null || pa == null || ang == null) {
            if (dbgFrames++ % 20 == 0) {
                val (lo, hi) = gyroRangeNs() ?: (0L to 0L)
                Log.i(TAG, "COLLECT seeding ang=${ang != null} gyroRange=[$lo..$hi] frameTs=$tsNs " +
                    "inRange=${tsNs in lo..hi}")
            }
            return
        }

        val cr = corr.correlate(pl, luma)
        val da = DoubleArray(3) { ang[it] - pa[it] }
        val gmot = abs(da[0]) + abs(da[1]) + abs(da[2])
        val tooBig = abs(cr[0]) > MAX_STEP * n || abs(cr[1]) > MAX_STEP * n
        if (dbgFrames++ % 20 == 0) {
            val (lo, hi) = gyroRangeNs() ?: (0L to 0L)
            Log.i(TAG, "COLLECT frames=$dbgFrames samples=${dA.size} peak=${"%.5f".format(cr[2])} " +
                "mdx=${"%.2f".format(cr[0])} mdy=${"%.2f".format(cr[1])} gyroMot=${"%.4f".format(gmot)} " +
                "clockInRange=${tsNs in lo..hi} tooBig=$tooBig")
        }

        if (cr[2] < MIN_PEAK) return
        if (tooBig) return
        // Require real rotation this frame, else the sample is uninformative.
        if (gmot < MIN_GYRO_STEP) return

        dA.add(da); mdx.add(cr[0].toDouble()); mdy.add(cr[1].toDouble())
        rawEnergy += cr[0] * cr[0] + cr[1].toDouble() * cr[1]
        collectFrames++

        if (dA.size >= MIN_SAMPLES) tryFit()
    }

    private var dbgFrames = 0
    private fun gyroRangeNs(): Pair<Long, Long>? = synchronized(gLock) {
        if (gHist.isEmpty()) null else gHist.first()[0].toLong() to gHist.last()[0].toLong()
    }

    private fun tryFit() {
        val (ax0, s0, r20) = bestAxis(mdx)
        val (ax1, s1, r21) = bestAxis(mdy)
        if (r20 < R2_MIN || r21 < R2_MIN || ax0 == ax1) return

        axX = ax0; sxN = (s0 / n).toFloat()
        axY = ax1; syN = (s1 / n).toFloat()
        applyConfig(flip = false)
        fitted = true

        Log.i(TAG, "calibrated axX=$axX sx=$sxN (r2=$r20) axY=$axY sy=$syN (r2=$r21) " +
            "from ${dA.size} samples")

        // Move to on-device sign verification.
        verifyResidual = 0.0; verifyMotion = 0.0; verifyFrames = 0
        prevLuma = null; prevAng = null
        state = State.VERIFYING
        controller?.setWarpActive(true)
    }

    private fun verify(luma: FloatArray, tsNs: Long) {
        val ang = angleAt(tsNs)
        val pl = prevLuma; val pa = prevAng
        prevLuma = luma; if (ang != null) prevAng = ang
        if (pl == null || pa == null || ang == null) return

        val r = motion(pl, luma) ?: return
        verifyResidual += r[0] * r[0] + r[1].toDouble() * r[1]
        verifyMotion += abs(ang[0] - pa[0]) + abs(ang[1] - pa[1]) + abs(ang[2] - pa[2])
        verifyFrames++
        if (verifyFrames < VERIFY_FRAMES) return

        // Need enough motion to judge; if the user held still, keep verifying.
        if (verifyMotion < MIN_GYRO_STEP * VERIFY_FRAMES) {
            verifyFrames = 0; verifyResidual = 0.0; verifyMotion = 0.0
            return
        }

        val rawPer = rawEnergy / maxOf(1, dA.size)
        val resPer = verifyResidual / verifyFrames
        Log.i(TAG, "verify residual/frame=$resPer baseline/frame=$rawPer flipped=$signFlipped")

        if (resPer > rawPer * IMPROVE_RATIO && !signFlipped) {
            // Warp made it no better → wrong overall sign. Flip once and re-check.
            signFlipped = true
            applyConfig(flip = true)
            verifyFrames = 0; verifyResidual = 0.0; verifyMotion = 0.0
            prevLuma = null; prevAng = null
            Log.i(TAG, "flipped warp sign; re-verifying")
            return
        }
        finishCalibration()
    }

    private fun applyConfig(flip: Boolean) {
        val k = if (flip) -1f else 1f
        stabilizer.updateConfig(
            LiveStabilizer.Config(
                cutoffHz = cutoffHz, cropFrac = cropFrac,
                axX = axX, sx = k * sxN,
                axY = axY, sy = k * syN,
                axRoll = 2, sRoll = 0f, enableRoll = false,   // roll left off until trusted
            )
        )
    }

    private fun finishCalibration() {
        state = State.DONE
        residual?.reset()
        prevLuma = null; prevAng = null
        Log.i(TAG, "calibration DONE")
    }

    private fun fallbackDone() {
        // The raw-measurement window closed without a confident fit (not enough
        // clean rotational motion). Rather than leave the preview unstabilized,
        // engage the intrinsic mapping (already set on the stabilizer by the
        // platform) and let the residual tracker refine from there.
        Log.w(TAG, "calibration window closed after $totalFrames frames; using intrinsic mapping")
        controller?.setWarpActive(true)
        // If we gathered a usable motion baseline (motion was present but the
        // per-axis fit never became confident), still verify the intrinsic
        // mapping's overall sign against that baseline and flip it once if the
        // warp isn't actually reducing motion — cheap insurance against a wrong
        // hardcoded sign amplifying shake. With little/no baseline there's nothing
        // to verify against (and little shake to get wrong), so just accept it.
        if (dA.size >= MIN_VERIFY_BASELINE && rawEnergy > 0.0) {
            seedResultFromConfig()
            verifyResidual = 0.0; verifyMotion = 0.0; verifyFrames = 0; signFlipped = false
            prevLuma = null; prevAng = null
            state = State.VERIFYING
        } else {
            finishCalibration()
        }
    }

    /** Seed the fitted-result fields from the stabilizer's current (intrinsic)
     *  config so [verify]'s sign-flip operates on the intrinsic mapping. */
    private fun seedResultFromConfig() {
        val c = stabilizer.config()
        axX = c.axX; sxN = c.sx
        axY = c.axY; syN = c.sy
    }

    /** Per-axis least-squares fit of [meas] onto each gyro-axis delta column.
     *  Returns (axis, signed slope px/rad, r²). Mirrors offline Calibrator. */
    private fun bestAxis(meas: List<Double>): Triple<Int, Double, Double> {
        var bestAxis = 0; var bestScale = 0.0; var bestR2 = -1.0
        val mean = meas.average()
        var ssTot = 0.0
        for (m in meas) ssTot += (m - mean) * (m - mean)
        ssTot = maxOf(ssTot, 1e-9)
        for (a in 0 until 3) {
            var dot = 0.0; var xx = 0.0
            for (i in meas.indices) { dot += dA[i][a] * meas[i]; xx += dA[i][a] * dA[i][a] }
            if (xx < 1e-12) continue
            val s = dot / xx
            var sse = 0.0
            for (i in meas.indices) { val r = meas[i] - s * dA[i][a]; sse += r * r }
            val r2 = 1.0 - sse / ssTot
            if (r2 > bestR2) { bestR2 = r2; bestScale = s; bestAxis = a }
        }
        return Triple(bestAxis, bestScale, bestR2)
    }

    fun reset() {
        synchronized(gLock) { gHist.clear(); lastGyroTs = Long.MIN_VALUE; curAng.fill(0.0) }
        state = State.COLLECTING
        prevLuma = null; prevAng = null
        dA.clear(); mdx.clear(); mdy.clear()
        rawEnergy = 0.0; collectFrames = 0; totalFrames = 0; fitted = false
        verifyResidual = 0.0; verifyMotion = 0.0; verifyFrames = 0; signFlipped = false
        residual?.reset()
        controller?.setWarpActive(false)
    }

    /** Diagnostics. */
    fun state(): String = state.name

    companion object {
        private const val TAG = "OnlineCalibrator"
        private const val MIN_SAMPLES = 20          // enough clean samples to fit axis+sign
        private const val ACTIVATE_AFTER = 45       // readback frames (~2-4.5 s) before engaging
                                                    // the intrinsic mapping regardless of motion
        private const val MIN_GYRO_STEP = 0.0008    // rad/frame — ignore near-still frames
        private const val MAX_STEP = 0.05f          // reject phase-corr mismatches
        private const val MIN_PEAK = 1e-4f
        private const val R2_MIN = 0.5
        private const val VERIFY_FRAMES = 40
        private const val IMPROVE_RATIO = 0.9       // residual must be < 90% of baseline to pass
        private const val MIN_VERIFY_BASELINE = 8   // samples needed to sign-verify the fallback
    }
}
