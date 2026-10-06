package com.gateshot.ui.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * Gyro-driven electronic stabilizer for the periscope capture path.
 *
 * Written fresh for the Camera2 pipeline (the July CameraX live-EIS is not
 * reused): no online calibration, no guessed signs. The mapping comes from
 * rear-camera physics in the upright portrait image (y down):
 *  - rotation about device Y (yaw, turning left = +) moves the scene right: dx = +f * dThetaY
 *  - rotation about device X (pitch, tilting up = +) moves the scene down: dy = +f * dThetaX
 * The June handheld teleconverter clip confirmed both axes and signs (R^2 0.99)
 * and measured f = 35362 px/rad on the 3840 px side and a +8 ms gyro/frame sync.
 *
 * Each frame is corrected at its own sensor timestamp (not "latest gyro"), which
 * is what the June sync fit showed matters at telephoto.
 */
class GyroStabilizer(
    /** Focal length at 10x (zoom ratio 3.03), in units of the portrait image height, per radian. */
    private val baseFocalPerRad: Float = 35362f / 3840f,
    /** Gyro time relative to SENSOR_TIMESTAMP (start of exposure); fitted on
     *  camera 0 handheld clips (+6 ms twice; the June camera-6 clip gave +8). */
    private val syncOffsetNs: Long = 6_000_000L,
) {
    /** Output view (upright portrait) width in units of its height. */
    val viewAspect = 9f / 16f
    /** Camera frame (upright portrait) width in units of its height: 9/16 for a 16:9
     *  stream, 3/4 for the 4:3 stream, whose extra width is all stabilization margin. */
    @Volatile var srcAspect = 9f / 16f

    private val path = GyroPath()
    // Two paths from the same gyro, tuned on the 2026-09-28 handheld clips
    // (e2e replay, build/qa/stab_m3/ab4/combo5.py):
    // viewfinder: firm hold (0.05 Hz), steadiest live picture;
    // recording buffer: looser (0.1 Hz) so pans do not jerk the buffered frames;
    // the recording's look-ahead stage does the final holding.
    private val smX = PathFilter(holdHz = 0.05)
    private val smY = PathFilter(holdHz = 0.05)
    private val ringX = PathFilter(holdHz = 0.1)
    private val ringY = PathFilter(holdHz = 0.1)

    /** Camera (HAL) zoom relative to ratio 3.03: scales the source image. */
    @Volatile var zoomFactor = 1f
    val focalPerRad get() = baseFocalPerRad * zoomFactor

    /** Final digital crop of the source (1.2 at 10x = Oppo teleconverter video
     *  framing; 2.0 at 20x/30x, where the crop, not the camera, does most of the
     *  zoom so the stabilizer has ~25% margin per side). */
    @Volatile var cropZoom = 1.25f
        private set
    /** Crop of the recording buffer: 1 = it holds the WHOLE camera frame (2160x3840, an
     *  exact copy). The recording path is then bounded only by the real crop margin, with
     *  no self-centring of the buffer. Simulated on the 2026-10-02 handheld clips
     *  (build/qa/stab_m9/u1/sim_full.py): the path stays fixed (0 px) where the smaller
     *  self-centred buffer (20x) and the causal hold (10x) wandered by 40-180 px. */
    val gyroCropZoom get() = 1f

    /** The recording path is planned live with a 1 s look-ahead (L1) at every zoom level. */
    val l1Mode get() = true

    /** Set camera zoom (relative to 3.03) and crop together, for a zoom level. */
    fun setView(cameraZoomFactor: Float, crop: Float) {
        zoomFactor = cameraZoomFactor
        cropZoom = crop
    }

    @Volatile var enabled = true

    /**
     * Restart the recording path when Record is pressed. Otherwise the file
     * starts with whatever offset the hold carried from aiming the camera and
     * spends seconds drifting back from it: on the 2026-09-28 20x still clip that
     * drift was the whole slow wobble (5.85 px at 0.2-1 Hz; 1.16 px when started
     * clean, against 1.38 px for the Oppo camera). Applied on the GL thread.
     */
    fun restartRecordingPath() { restartRing = true }
    @Volatile private var restartRing = false

    /** Result of [correctionFor]: sample offsets in image-height units for the
     *  viewfinder (off*) and the recording buffer (ringOff*); dTheta and clamped
     *  describe the recording path (what the frame log records). */
    data class Correction(
        val offX: Float, val offY: Float, val dThetaX: Double, val dThetaY: Double, val clamped: Boolean,
        val ringOffX: Float = offX, val ringOffY: Float = offY,
        /** Rolling-shutter terms (image heights per unit of source width): the
         *  sampled column at source position u (-0.5..0.5) is shifted by rsX*u, rsY*u. */
        val rsX: Float = 0f, val rsY: Float = 0f,
        /** Raw integrated gyro angles at the frame (rad): pitch (x) and yaw (y), for the
         *  recording's L1 path planner (20x/30x). */
        val rawX: Double = 0.0, val rawY: Double = 0.0,
        /** True when the recording buffer holds the RAW view (L1 mode, 20x/30x). */
        val l1Mode: Boolean = false,
    ) {
        /** The same correction with the recording-buffer offsets in the viewfinder slots. */
        fun forRing() = copy(offX = ringOffX, offY = ringOffY)
    }

    fun onGyro(tNs: Long, gx: Float, gy: Float) = path.add(tNs, gx, gy)

    fun latestGyroNs(): Long = path.latestT()

    /**
     * Correction for the frame exposed at [frameTsNs]: where to sample the source
     * (offset in image-height units, portrait, y down) so the output follows the
     * smoothed path. Call once per frame, in order.
     */
    fun correctionFor(frameTsNs: Long): Correction {
        val raw = path.angleAt(frameTsNs + syncOffsetNs)
            ?: return Correction(0f, 0f, 0.0, 0.0, false)
        val f = focalPerRad
        // Margin (rad) the crop leaves on each side before the warp would leave the frame.
        val marginX = 0.5f * (srcAspect - viewAspect / cropZoom) / f
        val marginY = 0.5f * (1f - 1f / cropZoom) / f
        val vY = smY.step(frameTsNs, raw[1], marginX.toDouble())   // yaw -> image x
        val vX = smX.step(frameTsNs, raw[0], marginY.toDouble())   // pitch -> image y
        if (restartRing) { ringX.reset(); ringY.reset(); restartRing = false }
        val dY = ringY.step(frameTsNs, raw[1], marginX.toDouble())
        val dX = ringX.step(frameTsNs, raw[0], marginY.toDouble())
        val clamped = ringY.clamped || ringX.clamped
        // Debug-only test signal (adb shell setprop debug.gateshot.synth 1): a known
        // sub-pixel wobble, a function of the frame timestamp, so precision and the
        // optical measurement can be verified on a phone lying still.
        var sx = 0f; var sy = 0f
        if (synthEnabled(frameTsNs)) {
            val t = frameTsNs / 1e9
            val outPx = 1f / (1920f * cropZoom)           // one output pixel, in image heights
            sx = (SYNTH_X_PX * kotlin.math.sin(2 * PI * SYNTH_X_HZ * t)).toFloat() * outPx
            sy = (SYNTH_Y_PX * kotlin.math.sin(2 * PI * SYNTH_Y_HZ * t)).toFloat() * outPx
        }
        // 20x/30x: the recording buffer holds the raw view (rolling-shutter corrected
        // only); the recording stage plans the whole path with a 1 s look-ahead (L1).
        val l1 = l1Mode
        if (!enabled) return Correction(sx, sy, dX, dY, clamped, sx, sy, rawX = raw[0], rawY = raw[1], l1Mode = l1)
        // Rolling shutter: the sensor reads the portrait image column by column, so
        // column u was exposed readout*u later than the centre. Hand tremor during
        // the readout skews (pitch) and stretches (yaw) the frame; shift each
        // column by the rotation during its own delay. Calibrated on real pixels
        // (build/qa/stab_m5/ois/bench*.py): 20x jitter 0.90/1.20 -> 0.30/0.20 px.
        val tMid = frameTsNs + syncOffsetNs
        val a0 = path.angleAt(tMid - 1_000_000L); val a1 = path.angleAt(tMid + 1_000_000L)
        var rsX = 0f; var rsY = 0f
        if (a0 != null && a1 != null) {
            // s across the source width; the 4:3 stream reads 4/3 as many sensor rows
            val readout = READOUT_S_AT_BASE_ZOOM * (srcAspect / viewAspect) / zoomFactor
            rsX = (f * (a1[1] - a0[1]) / 2e-3 * readout).toFloat()   // yaw rate -> x stretch
            rsY = (f * (a1[0] - a0[0]) / 2e-3 * readout).toFloat()   // pitch rate -> y skew
        }
        return Correction((f * vY).toFloat() + sx, (f * vX).toFloat() + sy, dX, dY, clamped,
            if (l1) sx else (f * dY).toFloat() + sx, if (l1) sy else (f * dX).toFloat() + sy,
            rsX, rsY, raw[0], raw[1], l1)
    }

    private var synthCheckNs = Long.MIN_VALUE
    private var synth = false
    private fun synthEnabled(nowNs: Long): Boolean {
        if (synthCheckNs == Long.MIN_VALUE || nowNs - synthCheckNs > 1_000_000_000L) {
            synthCheckNs = nowNs
            synth = try {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.gateshot.synth") == "1"
            } catch (_: Exception) { false }
        }
        return synth
    }

    companion object {
        /** Sensor readout across the full source width at camera zoom 3.03: 2304 rows x
         *  3.25 us (S5KHP5 4x4-binned 16:9 mode in the oppo-source kernel driver; the HAL
         *  reports the same SENSOR_ROLLING_SHUTTER_SKEW, 7.488 ms, at every zoom). Scaled as
         *  1/zoom (the sensor reads its full height, the zoom crops). The pixel bench
         *  (stab_m5/ois/bench_T.py) prefers it to the 8.4 ms first fitted there. */
        const val READOUT_S_AT_BASE_ZOOM = 7.488e-3
        const val SYNTH_X_PX = 0.6; const val SYNTH_X_HZ = 2.3
        const val SYNTH_Y_PX = 0.8; const val SYNTH_Y_HZ = 3.1
    }

    fun reset() {
        smX.reset(); smY.reset(); ringX.reset(); ringY.reset()
    }
}

/** Integrated gyro angle (rad) about device X and Y, in a time-indexed ring. */
internal class GyroPath(private val capacity: Int = 8192) {
    private val t = LongArray(capacity)
    private val ax = DoubleArray(capacity)
    private val ay = DoubleArray(capacity)
    private var head = 0
    private var count = 0
    private var angX = 0.0
    private var angY = 0.0
    private var lastT = Long.MIN_VALUE
    private var lastGx = 0f
    private var lastGy = 0f

    @Synchronized
    fun add(tNs: Long, gx: Float, gy: Float) {
        if (lastT != Long.MIN_VALUE) {
            if (tNs <= lastT) return
            // Trapezoid; clamp gaps so a sensor hiccup can't inject a huge step.
            val dt = ((tNs - lastT) / 1e9).coerceAtMost(0.05)
            angX += 0.5 * (gx + lastGx) * dt
            angY += 0.5 * (gy + lastGy) * dt
        }
        lastT = tNs; lastGx = gx; lastGy = gy
        t[head] = tNs; ax[head] = angX; ay[head] = angY
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun latestT(): Long = lastT

    private fun idx(i: Int) = (head - count + i + capacity) % capacity

    /** Angles at [tNs] by linear interpolation; extrapolates past the newest sample. */
    @Synchronized
    fun angleAt(tNs: Long): DoubleArray? {
        if (count < 2) return null
        val first = idx(0)
        val last = idx(count - 1)
        if (tNs <= t[first]) return doubleArrayOf(ax[first], ay[first])
        if (tNs >= t[last]) {
            // Frame newer than the gyro (sensor batching): extend at the last rate.
            val dt = ((tNs - t[last]) / 1e9).coerceAtMost(0.03)
            return doubleArrayOf(ax[last] + lastGx * dt, ay[last] + lastGy * dt)
        }
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[idx(mid)] <= tNs) lo = mid else hi = mid
        }
        val a = idx(lo)
        val b = idx(hi)
        val f = (tNs - t[a]).toDouble() / (t[b] - t[a]).toDouble()
        return doubleArrayOf(ax[a] + f * (ax[b] - ax[a]), ay[a] + f * (ay[b] - ay[a]))
    }
}

/**
 * Causal virtual-camera path for one axis: a "hold" that behaves like a tripod
 * until the user really pans. Critically damped second-order filter at a low
 * cutoff; above [panStart]..[panFull] rad/s of smoothed rotation it fades back
 * to a 0.7 Hz follower with velocity feed-forward, so a racer is tracked
 * smoothly. It also stiffens as the correction nears the crop margin instead
 * of hitting it. Tuned on the 2026-09-28 handheld 10x/20x wall clips: still
 * sway 20 -> ~10 px (10x) and 41 -> ~4 px (20x); 3-8 deg/s pans followed without
 * jerks. Returns raw - smooth (rad).
 */
internal class PathFilter(
    holdHz: Double = 0.1,
    private val panStart: Double = Math.toRadians(0.5),
    private val panFull: Double = Math.toRadians(1.5),
) {
    private val wHold = 2.0 * PI * holdHz
    private val wPan = 2.0 * PI * 0.7
    // Pan-rate estimate: slow on purpose (0.1 Hz), so a hand pan's changing speed
    // is not mistaken for intent. At 0.7 Hz the real 20x pan kept 9 px of jitter;
    // at 0.1 Hz 2.6 px, with the still clips unchanged or better.
    private val rateTau = 1.0 / (2.0 * PI * 0.1)
    private var sm = 0.0
    private var vel = 0.0
    private var rate = 0.0
    private var prevRaw = 0.0
    private var prevT = Long.MIN_VALUE
    var clamped = false
        private set

    fun step(tNs: Long, raw: Double, margin: Double): Double {
        if (prevT == Long.MIN_VALUE || tNs <= prevT || tNs - prevT > 500_000_000L) {
            sm = raw; vel = 0.0; rate = 0.0; prevRaw = raw; prevT = tNs
            clamped = false
            return 0.0
        }
        val dt = (tNs - prevT) / 1e9
        prevT = tNs
        rate += (((raw - prevRaw) / dt) - rate) * (dt / (rateTau + dt))
        prevRaw = raw
        // Pan weight: smoothstep of the smoothed rotation rate.
        val x = ((abs(rate) - panStart) / (panFull - panStart)).coerceIn(0.0, 1.0)
        val k = x * x * (3 - 2 * x)
        val e = abs(raw - sm) / margin
        val w = (wHold + (wPan - wHold) * k) * (1 + 8 * e * e * e * e)
        vel += dt * (w * w * (raw - sm) + 2.0 * w * (k * rate - vel))
        sm += dt * vel
        var d = raw - sm
        clamped = abs(d) > margin
        if (clamped) {
            // Hold the view at the margin edge and adopt the pan rate.
            d = sign(d) * margin
            sm = raw - d
            vel = rate
        }
        return d
    }

    fun reset() { prevT = Long.MIN_VALUE }
}

/**
 * Hold of the viewfinder, one axis, on the measured image path. Unlike [PathFilter] it does
 * not pull the view back to the centre: once the correction is inside [rest] of the margin
 * the view stands still, and it only moves again when the correction passes [engage] of
 * the margin (then back to [rest], at [backHz]) or when the user pans.
 *
 * [PathFilter] with a slow return was tried first (2026-10-06): after a re-aim it overshot
 * by up to 745 px and then crept for more than 10 s, which the user saw as a viewfinder
 * that "keeps moving without my panning". Replayed on the same clips this hold stands
 * within 3 px from 1 s after the move ends (build/qa/stab_m9/g3/settle.py).
 * Returns raw - smooth (rad).
 */
internal class ViewHold(
    private val engage: Double = 0.45,
    private val rest: Double = 0.25,
    backHz: Double = 0.4,
    private val panStart: Double = Math.toRadians(0.5),
    private val panFull: Double = Math.toRadians(1.5),
) {
    private val wBack = 2.0 * PI * backHz
    private val wStill = 2.0 * PI * 0.002
    private val wPan = 2.0 * PI * 0.7
    private val rateTau = 1.0 / (2.0 * PI * 0.1)     // as PathFilter: a slow pan-rate estimate
    private var sm = 0.0
    private var vel = 0.0
    private var rate = 0.0
    private var prevRaw = 0.0
    private var prevT = Long.MIN_VALUE
    private var returning = false

    fun step(tNs: Long, raw: Double, margin: Double): Double {
        if (prevT == Long.MIN_VALUE || tNs <= prevT || tNs - prevT > 500_000_000L) {
            sm = raw; vel = 0.0; rate = 0.0; prevRaw = raw; prevT = tNs; returning = false
            return 0.0
        }
        val dt = (tNs - prevT) / 1e9
        prevT = tNs
        rate += (((raw - prevRaw) / dt) - rate) * (dt / (rateTau + dt))
        prevRaw = raw
        val x = ((abs(rate) - panStart) / (panFull - panStart)).coerceIn(0.0, 1.0)
        val k = x * x * (3 - 2 * x)
        var d = raw - sm
        val e = abs(d) / margin
        if (e > engage) returning = true else if (e <= rest) returning = false
        // Spring on what exceeds the rest zone only, so the view stops at its edge.
        val excess = if (returning) sign(d) * (abs(d) - rest * margin) else 0.0
        val stiff = (1 - k) * (wBack * wBack * excess + wStill * wStill * d) + k * wPan * wPan * d
        val w = (1 - k) * wBack + k * wPan
        vel += dt * (stiff + 2.0 * w * (k * rate - vel))
        sm += dt * vel
        d = raw - sm
        if (abs(d) > margin) {
            // Hold the view at the margin edge and adopt the pan rate.
            d = sign(d) * margin
            sm = raw - d
            vel = rate
        }
        return d
    }

    fun reset() { prevT = Long.MIN_VALUE }
}
