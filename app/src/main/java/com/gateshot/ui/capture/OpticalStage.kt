package com.gateshot.ui.capture

import android.opengl.GLES20
import android.util.Log
import com.gateshot.processing.stabilize.GlobalShiftEstimator
import com.gateshot.processing.stabilize.L1PathPlanner
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp

/**
 * Second (optical) stabilization stage for the recording only. GL thread only,
 * except the shift estimation, which runs on its own worker thread.
 *
 * Every gyro-warped frame is kept in a small GPU ring with a slightly wider
 * crop than the output. A 256x256 luma copy of its centre is read back and the
 * leftover motion against the previous frame is measured ([GlobalShiftEstimator]).
 * A frame is encoded [LOOKAHEAD] frames later, shifted onto the path smoothed
 * over +/-[LOOKAHEAD] frames: this removes what the gyro cannot see (hand
 * translation, sync noise). On the handheld test clip this was simulated to take
 * 20x jitter from ~1.6 px to ~0.3 px. A live (causal) version made it worse,
 * which is why the viewfinder stays gyro-only.
 */
internal class OpticalStage(
    /** Draw the current camera frame, gyro-warped at [zoom], into the bound framebuffer. */
    /** Draw the camera frame into the bound framebuffer; offX/offY override the ring
     *  sample offsets (image heights), NaN = the gyro stage's own. */
    private val drawCamera: (width: Int, height: Int, offX: Float, offY: Float) -> Unit,
    /** gyroCropZoom (the buffer's crop of the source) and cropZoom (the output's). */
    private val crops: () -> Pair<Float, Float>,
    /** gyroCropZoom / cropZoom: the output window as a fraction of a ring frame. */
    private val windowFraction: () -> Float,
    /** Focal length per radian in ring-frame heights (focalPerRad * gyroCropZoom). */
    private val ringFocal: () -> Float,
    /** Stab OFF must switch this stage off too, or the A/B test is not fair. */
    private val enabled: () -> Boolean,
    /** Camera frame in the upright portrait image, px (2160 x 3840 for the 4K stream). */
    private val srcW: Int,
    private val srcH: Int,
) {
    private var ringTex = IntArray(0)
    private var ringFbo = IntArray(0)
    private var smallTex = 0
    private var smallFbo = 0
    private var progEnc = 0
    private var progLuma = 0
    // Ring frame size: the buffered part of the source at 1:1 (srcW/gyroCropZoom), so a
    // ring frame is an exact texel copy and the only resample is the one at encode.
    private var ringW = 0
    private var ringH = 0
    /** Per slot: the whole-pixel sample offset the ring frame was copied at (image heights). */
    private val slotOff = Array(RING) { FloatArray(2) }
    /** Per slot: that offset minus the wanted sub-pixel one (ring px); undone at encode. */
    private val slotErr = Array(RING) { FloatArray(2) }
    private var prevErr = FloatArray(2)               // GL thread
    private var warmed = false
    private val slotTs = LongArray(RING)
    private val slotGyro = arrayOfNulls<GyroStabilizer.Correction>(RING)
    private val warp = FloatArray(9)
    private val readBuf = ByteBuffer.allocateDirect(SMALL * SMALL * 4).order(ByteOrder.nativeOrder())
    private val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD); position(0) }

    // Each measurement needs only a pair of consecutive frames, so pairs run in
    // parallel: one estimate takes ~50 ms on the phone, a frame arrives every 33 ms.
    private val workers = Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "GateShotOptical") }
    private val estimator = GlobalShiftEstimator(SMALL)
    private var prevLuma: FloatArray? = null          // GL thread
    private val pending = AtomicInteger(0)
    /** Leftover motion of frame k relative to k-1, in ring pixels (dx, dy). */
    private val shifts = ConcurrentHashMap<Long, FloatArray>()

    private var next = 0L          // index the next added frame gets
    // Encode-side planning (GL thread): look-ahead smoothing + hold, per axis.
    // Pinned to the tuned simulation by RecordingPathPlannerReplayTest.

    // 20x/30x: live L1-optimal camera path (Grundmann 2011) over a 1 s look-ahead, one
    // planner per axis on its own thread (each call ~ms; sequential per axis because
    // it commits history). Validated: RecordingPathPlanner-free bench, build/qa/stab_m6.
    private val l1X = L1PathPlanner(LOOKAHEAD)
    private val l1Y = L1PathPlanner(LOOKAHEAD)
    private val l1ExecX = Executors.newSingleThreadExecutor { r -> Thread(r, "GateShotL1x") }
    private val l1ExecY = Executors.newSingleThreadExecutor { r -> Thread(r, "GateShotL1y") }
    private val l1PlanX = ConcurrentHashMap<Long, java.util.concurrent.Future<Double>>()
    private val l1PlanY = ConcurrentHashMap<Long, java.util.concurrent.Future<Double>>()
    /** Raw gyro angles by frame index (GL thread): yaw -> x, pitch -> y, in rad. */
    private val rawByIndex = HashMap<Long, DoubleArray>()
    /**
     * The camera path the recording is planned on, per frame index (ring px, x/y): the
     * gyro path plus the accumulated difference between the measured image motion and the
     * gyro's prediction. On handheld clips (build/qa/stab_m9/h1/fuse.py) the gyro alone
     * mis-predicts the slow image motion by 10-20% (34-100 px over 12 s) and a path held
     * still against it drifts visibly; the measured path follows the picture 3-4x closer.
     * A frame without a usable measurement advances by the gyro alone.
     */
    private val fusedByIndex = HashMap<Long, DoubleArray>()
    private var settled = -1L
    private val resid = DoubleArray(2)
    private val gainXY = DoubleArray(2)       // running sums for gain(): measured x predicted,
    private val gainXX = DoubleArray(2)       // predicted squared (px^2 per frame)
    /** Buffer centre S per frame index (ring px, x/y): where the ring frame is centred. */
    private val sByIndex = HashMap<Long, DoubleArray>()
    /** Latest tentative path per axis from the planners: window-end index, value, previous value. */
    private class Tentative { @Volatile var index = -1L; @Volatile var value = 0.0; @Volatile var prev = 0.0 }
    private val tentX = Tentative()
    private val tentY = Tentative()
    private fun centre(t: Tentative, c: Double, k: Long): Double {
        val j = t.index
        if (j < 0) return c
        return t.value + (t.value - t.prev) * (k - j)   // extrapolate to this frame
    }
    private var l1PxPerRad = 0.0
    private var nextEncode = 0L
    private var active = false

    // Diagnostics.
    private var statEst = 0
    private var statEstMs = 0.0
    private var statRejected = 0
    private var statDropped = 0
    private var statGated = 0
    private var statL1Missing = 0
    private var statPlanMs = 0.0
    private var statPlans = 0

    /**
     * Camera screen opened (GL thread): allocate the ~0.5 GB recording buffer and
     * JIT-warm the L1 planners now, not when Record is pressed (that stalled the
     * first seconds of the first recording to 6-15 fps).
     */
    fun prepare() {
        ensureRing()
        if (warmed) return
        warmed = true
        val rnd = java.util.Random(1)
        repeat(2) { axis ->
            val exec = if (axis == 0) l1ExecX else l1ExecY
            exec.execute {
                val warm = L1PathPlanner(LOOKAHEAD)
                var c = 0.0
                repeat(40) { warm.next(DoubleArray(LOOKAHEAD + 1) { c += rnd.nextGaussian() * 3; c }, 90.0) }
            }
        }
    }

    fun start() {
        ensureRing()
        next = 0L; nextEncode = 0L
        rawByIndex.clear(); sByIndex.clear(); l1PlanX.clear(); l1PlanY.clear()
        fusedByIndex.clear(); settled = -1L; resid[0] = 0.0; resid[1] = 0.0
        gainXY.fill(0.0); gainXX.fill(0.0)
        tentX.index = -1; tentY.index = -1
        l1ExecX.execute { l1X.reset() }; l1ExecY.execute { l1Y.reset() }
        shifts.clear()
        prevLuma = null
        active = true
    }

    /** The output window's width as a fraction of a ring frame's width: the view is 9:16,
     *  the camera frame may be wider (4:3 stream). */
    private fun windowX() = windowFraction() * (9f / 16f) * srcH / srcW

    /** Render the current camera frame into the ring and queue its measurement. */
    fun addFrame(ts: Long, gyro: GyroStabilizer.Correction) {
        if (!active) return
        val slot = (next % RING).toInt()
        val index0 = next
        rawByIndex[index0] = doubleArrayOf(gyro.rawY, gyro.rawX)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbo[slot])
        // The ring frame is copied at a whole-pixel offset, without the rolling-shutter
        // warp: no interpolation here. The sub-pixel rest and the rolling shutter are
        // applied by the single (Lanczos) resample at encode. Two bilinear resamples
        // halved the sharpness (build/qa/stab_m9/sharp.py).
        val (gc, _) = crops()
        val wantX: Double; val wantY: Double      // wanted sample offset, source px
        var cx = 0.0; var cy = 0.0
        if (gyro.l1Mode) {
            // Self-centred buffer: centre this frame on the planner's latest tentative path
            // (extrapolated), so the L1 path can use the camera's whole field with a buffer
            // only 1.5x the output view (sim on the 2026-09-28 clips: still shots perfectly
            // steady; pans at the Oppo level). The ring then shows content at S, not at C.
            val pxPerRad = (ringFocal() * ringH).toDouble()
            cx = pxPerRad * gyro.rawY; cy = pxPerRad * gyro.rawX
            val gmx = 0.95 * 0.5 * (gc - 1) * ringW; val gmy = 0.95 * 0.5 * (gc - 1) * ringH
            wantX = cx - centre(tentX, cx, index0).coerceIn(cx - gmx, cx + gmx)
            wantY = cy - centre(tentY, cy, index0).coerceIn(cy - gmy, cy + gmy)
        } else {
            wantX = gyro.ringOffX.toDouble() * srcH; wantY = gyro.ringOffY.toDouble() * srcH
        }
        val px = Math.rint(wantX); val py = Math.rint(wantY)
        val err = FloatArray(2)
        if (gyro.l1Mode) {
            sByIndex[index0] = doubleArrayOf(cx - px, cy - py)   // S absorbs the rounding
        } else {
            err[0] = (px - wantX).toFloat(); err[1] = (py - wantY).toFloat()
        }
        slotErr[slot] = err
        slotOff[slot] = floatArrayOf((px / srcH).toFloat(), (py / srcH).toFloat())
        drawCamera(ringW, ringH, slotOff[slot][0], slotOff[slot][1])
        slotTs[slot] = ts
        slotGyro[slot] = gyro

        // 256x256 luma of the ring frame's centre, for the shift estimator.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
        GLES20.glViewport(0, 0, SMALL, SMALL)
        GLES20.glUseProgram(progLuma)
        val fx = REGION / 2f / ringW
        val fy = REGION / 2f / ringH
        setWarp(2 * fx, 0f, 0.5f - fx, 0f, 2 * fy, 0.5f - fy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progLuma, "uTexel"), 1f / ringW, 1f / ringH)
        drawRing(progLuma, ringTex[slot])
        readBuf.position(0)
        GLES20.glReadPixels(0, 0, SMALL, SMALL, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val index = next
        next++
        rawByIndex.remove(index - 3 * LOOKAHEAD)
        sByIndex.remove(index - 3 * LOOKAHEAD)
        fusedByIndex.remove(index - 3 * LOOKAHEAD)
        settle(index - SETTLE)
        if (gyro.l1Mode && index >= LOOKAHEAD) scheduleL1(index - LOOKAHEAD, index)
        if (pending.get() >= 3 * WORKERS) {
            // Workers are behind: skip this measurement rather than pile up latency.
            statDropped++
            prevLuma = null
            prevErr = err
            return
        }
        val luma = FloatArray(SMALL * SMALL)
        for (y in 0 until SMALL) {
            val row = (SMALL - 1 - y) * SMALL   // GL rows are bottom-up; store top-down
            for (x in 0 until SMALL) {
                val i = (row + x) * 4
                luma[y * SMALL + x] = (readBuf.get(i).toInt() and 0xff) + (readBuf.get(i + 1).toInt() and 0xff) / 255f
            }
        }
        val p = prevLuma
        prevLuma = luma
        // Rounding the copy offset moved this frame's content by -err: put it back.
        val fixX = err[0] - prevErr[0]; val fixY = err[1] - prevErr[1]
        prevErr = err
        if (p == null) return
        // Where the gyro expects the picture to have moved (ring px): the search starts
        // there. In l1Mode the ring holds the camera frame as it came, so that is the whole
        // gyro step; otherwise the gyro warp has already removed it.
        var seedX = 0f; var seedY = 0f
        val r1 = rawByIndex[index]; val r0 = rawByIndex[index - 1]
        if (gyro.l1Mode && r1 != null && r0 != null) {
            val pxPerRad = (ringFocal() * ringH).toDouble()
            seedX = (gain(0) * pxPerRad * (r1[0] - r0[0])).toFloat()
            seedY = (gain(1) * pxPerRad * (r1[1] - r0[1])).toFloat()
        }
        pending.incrementAndGet()
        workers.execute {
            try {
                val t0 = System.nanoTime()
                val s = estimator.estimate(p, luma, seedX / SCALE, seedY / SCALE)
                synchronized(this) { statEstMs += (System.nanoTime() - t0) / 1e6; statEst++ }
                val dx = s.dx * SCALE + fixX
                val dy = s.dy * SCALE + fixY
                // Low agreement, or far from where the gyro puts the picture: the frame is
                // dominated by something moving on its own (or blur) -- no measurement.
                // The distance is taken from the prediction, not from zero: a fast pan
                // moves the picture 60-100 px a frame, and a bound on the shift itself
                // threw exactly those frames away (build/qa/stab_m9/fable/check4.py).
                // The agreement threshold is low because settle() also checks every
                // measurement against the gyro: in a pan 5-8% of frames failed the old 0.4
                // and each unmeasured frame costs 5-7 px (build/qa/stab_m9/u6/decomp.py).
                if (s.inlierFraction >= MIN_INLIERS && abs(dx - seedX) < 60f && abs(dy - seedY) < 60f) {
                    shifts[index] = floatArrayOf(dx, dy, s.inlierFraction)
                } else statRejected++
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Fix the fused path up to frame [upTo]: its measurements have had time to arrive. */
    private fun settle(upTo: Long) {
        val pxPerRad = (ringFocal() * ringH).toDouble()
        while (settled < upTo) {
            val j = settled + 1
            val r = rawByIndex[j] ?: break
            val p = rawByIndex[j - 1]; val sh = shifts[j]
            if (p != null) {
                val gx = pxPerRad * (r[0] - p[0]); val gy = pxPerRad * (r[1] - p[1])
                val kx = gain(0); val ky = gain(1)
                // A measurement far from the prediction is wrong (blur in a fast pan: 40-90 px
                // off on the 2026-10-05 20x pan, each one a jump in the picture,
                // build/qa/stab_m9/u4/lag.py). Good ones differ by a few px.
                val ok = sh != null &&
                    Math.hypot(sh[0] - kx * gx, sh[1] - ky * gy) <= GATE_PX + GATE_FRACTION * Math.hypot(gx, gy)
                if (ok) {
                    resid[0] += sh!![0] - gx; resid[1] += sh[1] - gy
                    gainXY[0] += GAIN_RATE * (sh[0] * gx - gainXY[0]); gainXX[0] += GAIN_RATE * (gx * gx - gainXX[0])
                    gainXY[1] += GAIN_RATE * (sh[1] * gy - gainXY[1]); gainXX[1] += GAIN_RATE * (gy * gy - gainXX[1])
                } else {
                    // No usable measurement: the gyro step, scaled by what the image has
                    // recently moved per predicted px.
                    if (sh != null) statGated++
                    resid[0] += (kx - 1) * gx; resid[1] += (ky - 1) * gy
                }
            }
            fusedByIndex[j] = doubleArrayOf(pxPerRad * r[0] + resid[0], pxPerRad * r[1] + resid[1])
            settled = j
        }
    }

    /**
     * Image motion per px of gyro-predicted motion on one axis, from the last ~1 s of accepted
     * measurements. In a pan the image moves only ~0.8x the prediction (cause unknown), so a
     * frame advanced on the raw gyro step is 6-8 px off; scaled, 3.4 (stab_m9/u5/gain.py).
     */
    private fun gain(axis: Int): Double =
        if (gainXX[axis] > GAIN_MIN_POWER) (gainXY[axis] / gainXX[axis]).coerceIn(0.7, 1.1) else 1.0

    /** Fused camera path at frame [k]; frames not settled yet continue on the scaled gyro. */
    private fun pathAt(k: Long, pxPerRad: Double): DoubleArray {
        fusedByIndex[k]?.let { return it }
        val r = rawByIndex[k]
        val f = fusedByIndex[settled]; val rs = rawByIndex[settled]
        if (r != null && f != null && rs != null) {
            return doubleArrayOf(f[0] + gain(0) * pxPerRad * (r[0] - rs[0]), f[1] + gain(1) * pxPerRad * (r[1] - rs[1]))
        }
        return doubleArrayOf(pxPerRad * (r?.get(0) ?: 0.0) + resid[0], pxPerRad * (r?.get(1) ?: 0.0) + resid[1])
    }

    /** Frames that now have their full lookahead. [encode] draws into the bound encoder surface. */
    fun encodeReady(encode: (ts: Long, gyro: GyroStabilizer.Correction, ox: Float, oy: Float, measured: FloatArray?) -> Unit) {
        if (!active) return
        while (nextEncode <= next - 1 - LOOKAHEAD - WORKER_SLACK) {
            val i = nextEncode
            val g = slotGyro[(i % RING).toInt()]
            val ready = g == null || !g.l1Mode ||
                ((l1PlanX[i]?.isDone ?: true) && (l1PlanY[i]?.isDone ?: true))
            // Never block the GL thread for a plan unless the ring would overwrite this frame.
            if (!ready && i > next - RING + 1) break
            encodeOne(nextEncode++, next - 1, encode)
        }
    }

    /** Recording stops: wait for outstanding measurements, then encode the tail. */
    fun finish(encode: (ts: Long, gyro: GyroStabilizer.Correction, ox: Float, oy: Float, measured: FloatArray?) -> Unit) {
        if (!active) return
        val deadline = System.nanoTime() + 1_000_000_000L
        while (pending.get() > 0 && System.nanoTime() < deadline) Thread.sleep(2)
        settle(next - 1)
        // The last second never got its full look-ahead: plan it with what exists.
        for (k in maxOf(0L, next - LOOKAHEAD) until next) {
            val g = slotGyro[(k % RING).toInt()]
            if (g != null && g.l1Mode && !l1PlanX.containsKey(k)) scheduleL1(k, next - 1)
        }
        while (nextEncode < next) encodeOne(nextEncode++, next - 1, encode)
        active = false
        if (statEst > 0) Log.i(TAG, "optical: est ${"%.1f".format(statEstMs / statEst)}ms avg, " +
            "rejected=$statRejected gated=$statGated dropped=$statDropped l1Missing=$statL1Missing " +
            "plan ${"%.1f".format(if (statPlans > 0) statPlanMs / statPlans else 0.0)}ms avg frames=$next")
        statEst = 0; statEstMs = 0.0; statRejected = 0; statGated = 0; statDropped = 0; statL1Missing = 0
        statPlanMs = 0.0; statPlans = 0
    }

    /** Plan frame [i] once its look-ahead up to [last] is known (copies the window now). */
    private fun scheduleL1(i: Long, last: Long) {
        val pxPerRad = (ringFocal() * ringH).toDouble()
        val (gc, crop) = crops()
        // Bounds: the output window must stay inside the camera frame (10% of the margin
        // kept in reserve), around the fused camera path.
        val totX = 0.9 * 0.5 * ringW * (1 - windowX()); val totY = 0.9 * 0.5 * ringH * (1 - windowFraction())
        val n = (last - i + 1).toInt()
        val cx = DoubleArray(n); val cy = DoubleArray(n)
        val lox = DoubleArray(n); val hix = DoubleArray(n); val loy = DoubleArray(n); val hiy = DoubleArray(n)
        for (q in 0 until n) {
            val c = pathAt(i + q, pxPerRad)
            cx[q] = c[0]; cy[q] = c[1]
            lox[q] = cx[q] - totX; hix[q] = cx[q] + totX
            loy[q] = cy[q] - totY; hiy[q] = cy[q] + totY
        }
        l1PlanX[i] = l1ExecX.submit<Double> {
            val t0 = System.nanoTime(); val r = l1X.nextBounded(cx, lox, hix)
            tentX.prev = tentX.value; tentX.value = l1X.tentative; tentX.index = last
            synchronized(this) { statPlanMs += (System.nanoTime() - t0) / 1e6; statPlans++ }; r
        }
        l1PlanY[i] = l1ExecY.submit<Double> {
            val t0 = System.nanoTime(); val r = l1Y.nextBounded(cy, loy, hiy)
            tentY.prev = tentY.value; tentY.value = l1Y.tentative; tentY.index = last
            synchronized(this) { statPlanMs += (System.nanoTime() - t0) / 1e6; statPlans++ }; r
        }
    }

    private fun encodeOne(i: Long, last: Long,
                          encode: (Long, GyroStabilizer.Correction, Float, Float, FloatArray?) -> Unit) {
        val slot = (i % RING).toInt()
        val gyro = slotGyro[slot]!!
        val s = windowFraction(); val sx = windowX()
        val my = 0.5f * (1f - s); val mx = 0.5f * (1f - sx)
        val pxPerRad = (ringFocal() * ringH).toDouble()
        val l1 = gyro.l1Mode
        // Correction = fused camera path - planned path. The fused path already carries
        // what the image measurement saw, so there is no separate optical term.
        var cx = 0.0; var cy = 0.0
        var l1x = 0.0; var l1y = 0.0
        if (l1) {
            val c = pathAt(i, pxPerRad)
            val px = try { l1PlanX.remove(i)?.get(80, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            val py = try { l1PlanY.remove(i)?.get(80, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            if (px != null && py != null) {
                l1x = c[0] - px; l1y = c[1] - py
            } else statL1Missing++
            cx = l1x; cy = l1y
        }
        val on = enabled()
        val ox = if (on) (cx / ringW).toFloat().coerceIn(-mx, mx) else 0f
        val oy = if (on) (cy / ringH).toFloat().coerceIn(-my, my) else 0f
        val meas = shifts[i]
        val measured = floatArrayOf(meas?.get(0) ?: Float.NaN, meas?.get(1) ?: Float.NaN, l1x.toFloat(), l1y.toFloat(),
            meas?.get(2) ?: Float.NaN)
        shifts.remove(i - LOOKAHEAD - 1)

        // Output window of the ring frame: scale s = gyroCrop/crop of its height and sx of
        // its width, shifted by (ox, oy).
        // Encoder output (a, b): upright u = sx*b + cu, v = s*a + cv; cu/cv also undo the
        // whole-pixel rounding of the copy. Then the rolling shutter, which acts on the
        // source position X = (u - .5)/gc + off/aspect (see StabRenderer.buildWarp):
        // u'' = kx*(u - .5) + (kx - 1)*off*gc/aspect + .5, v'' = v + rsY*gc*X.
        // Ring texcoord = (u'', 1 - v'').
        val gc = crops().first
        val err = slotErr[slot]
        val offA = slotOff[slot][0] * gc * srcH / srcW      // off*gc/aspect
        val kx = 1f + gyro.rsX * srcH / srcW
        val cu = 0.5f - 0.5f * sx + ox - err[0] / ringW
        val cv = 0.5f - 0.5f * s + oy - err[1] / ringH
        GLES20.glUseProgram(progEnc)
        setWarp(0f, kx * sx, kx * (cu - 0.5f) + (kx - 1f) * offA + 0.5f,
            -s, -gyro.rsY * sx, 1f - cv - gyro.rsY * (cu - 0.5f) - gyro.rsY * offA)
        encode(slotTs[slot], gyro, ox, oy, measured)
    }

    /** Draw ring frame of the frame being encoded into the current (encoder) surface. */
    fun drawEncoded(ts: Long, width: Int, height: Int) {
        val slot = slotTs.indexOf(ts)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(progEnc)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progEnc, "uSize"), ringW.toFloat(), ringH.toFloat())
        drawRing(progEnc, ringTex[slot])
    }

    /** mat3 (column-major) for t = (ta*a + tb*b + tc, ua*a + ub*b + uc). */
    private fun setWarp(ta: Float, tb: Float, tc: Float, ua: Float, ub: Float, uc: Float) {
        warp[0] = ta; warp[1] = ua; warp[2] = 0f
        warp[3] = tb; warp[4] = ub; warp[5] = 0f
        warp[6] = tc; warp[7] = uc; warp[8] = 1f
    }

    private fun drawRing(program: Int, tex: Int) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uWarp"), 1, false, warp, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** (Re)allocate the ring for the current zoom level's buffer crop. Not while recording. */
    private fun ensureRing() {
        if (active) return
        val gc = crops().first
        val w = Math.round(srcW / gc / 2f) * 2
        val h = Math.round(srcH / gc / 2f) * 2
        if (ringTex.isNotEmpty() && w == ringW && h == ringH) return
        freeRing()
        ringW = w; ringH = h
        allocate()
        Log.i(TAG, "ring ${ringW}x$ringH x$RING (${ringW.toLong() * ringH * 4 * RING / 1_000_000} MB)")
    }

    private fun allocate() {
        if (progEnc == 0) {
            progEnc = link(VERTEX_2D, FRAGMENT_LANCZOS)
            progLuma = link(VERTEX_2D, FRAGMENT_LUMA)
        }
        ringTex = IntArray(RING).also { GLES20.glGenTextures(RING, it, 0) }
        ringFbo = IntArray(RING).also { GLES20.glGenFramebuffers(RING, it, 0) }
        for (i in 0 until RING) attach(ringTex[i], ringFbo[i], ringW, ringH)
        val t = IntArray(1); val f = IntArray(1)
        GLES20.glGenTextures(1, t, 0); GLES20.glGenFramebuffers(1, f, 0)
        smallTex = t[0]; smallFbo = f[0]
        attach(smallTex, smallFbo, SMALL, SMALL)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun attach(tex: Int, fbo: Int, w: Int, h: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        // Both readers (Lanczos encode, luma box) sample exact texel centres.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Recording buffer ${w}x$h unavailable"
        }
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "Shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Program: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    /** GL thread, with the context current. */
    private fun freeRing() {
        if (ringTex.isEmpty()) return
        GLES20.glDeleteTextures(RING, ringTex, 0)
        GLES20.glDeleteFramebuffers(RING, ringFbo, 0)
        GLES20.glDeleteTextures(1, intArrayOf(smallTex), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
        ringTex = IntArray(0)
    }

    fun release() {
        active = false
        freeRing()
        workers.shutdown(); l1ExecX.shutdown(); l1ExecY.shutdown()
    }

    companion object {
        private const val TAG = "OpticalStage"
        const val LOOKAHEAD = 30                 // frames (1.0 s at 30 fps): L1 path look-ahead
        private const val OPTICAL_WINDOW = 12    // +/- frames for the optical leftover smoothing
        private const val L1_MARGIN = 0.8f       // share of the buffer margin the L1 path may use
        // 10, not 4: the gyro prediction itself is 2-3 px rms off a correct measurement at
        // any speed, so 4 px threw away good ones. On five logged 20x clips 10 px accepts no
        // additional wrong measurement and lowers the path error on each
        // (build/qa/stab_m9/g1/gate_replay.py).
        private const val GATE_PX = 10.0         // measurement vs gyro prediction: accepted difference,
        private const val GATE_FRACTION = 0.3    // ring px + share of the predicted step
        private const val GAIN_RATE = 1.0 / 30   // per frame: ~1 s memory
        private const val GAIN_MIN_POWER = 50.0  // px^2: below this the axis is not moving enough to fit
        private const val MIN_INLIERS = 0.2f     // estimator agreement below which a shift is discarded
        private const val SETTLE = 10            // frames a measurement may take to arrive (an estimate
                                                 // took 120 ms on a warm phone, 2026-10-05)
        private const val WORKER_SLACK = 2       // extra delay so the newest measurements are in
        private const val PLAN_SLACK = 2        // frames a late L1 plan may take before we wait
        private const val RING = LOOKAHEAD + WORKER_SLACK + PLAN_SLACK + 2
        // Look-ahead smoothing: removes what the gyro misses and re-smooths the
        // causal path of a pan (simulated real 20x pan: 2.6 -> 0.6 px jitter).
        private const val SIGMA = 8.0            // frames
        private const val SMALL = 256
        private const val REGION = 768           // ring px measured (centre square)
        private const val SCALE = REGION.toFloat() / SMALL
        private const val WORKERS = 4
        private val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        private const val VERTEX_2D = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat3 uWarp;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = (uWarp * vec3(aTexCoord, 1.0)).xy;
            }
        """
        // The one resample of the recording: Lanczos-3 (6x6 taps on exact texel centres).
        // Simulated on native frames: bilinear keeps ~50% of the Laplacian variance at a
        // sub-pixel shift, bicubic ~85%, Lanczos ~93%. Not for downscaling (no prefilter):
        // the output is always >= the window in ring pixels.
        private const val FRAGMENT_LANCZOS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D sTex;
            uniform vec2 uSize;
            varying vec2 vTex;
            float lz(float x) {
                x = abs(x);
                if (x < 1e-4) return 1.0;
                if (x >= 3.0) return 0.0;
                float p = 3.14159265 * x;
                return 3.0 * sin(p) * sin(p / 3.0) / (p * p);
            }
            void main() {
                vec2 p = vTex * uSize - 0.5;
                vec2 b = floor(p);
                vec2 f = p - b;
                vec3 acc = vec3(0.0);
                float sum = 0.0;
                for (int j = -2; j <= 3; j++) {
                    float wy = lz(float(j) - f.y);
                    for (int i = -2; i <= 3; i++) {
                        float w = lz(float(i) - f.x) * wy;
                        acc += w * texture2D(sTex, (b + vec2(float(i), float(j)) + 0.5) / uSize).rgb;
                        sum += w;
                    }
                }
                gl_FragColor = vec4(acc / sum, 1.0);
            }
        """
        // 3x3 box average before the 3:1 downsample (anti-aliasing), then luma.
        private const val FRAGMENT_LUMA = """
            // highp: mediump is fp16 on Mali, which snaps texture coordinates of a
            // 4K texture to 1-2 texel steps -- whole-pixel jumps in the output and
            // ~2 px errors in the optical measurement (2026-09-28 A/B).
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D sTex;
            uniform vec2 uTexel;
            varying vec2 vTex;
            void main() {
                vec3 s = vec3(0.0);
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                    s += texture2D(sTex, vTex + vec2(float(i), float(j)) * uTexel).rgb;
                // 16-bit luma in two channels: 8-bit readback alone loses the
                // sub-level detail sub-pixel tracking needs on low-contrast scenes.
                float l = dot(s / 9.0, vec3(0.299, 0.587, 0.114)) * 255.0;
                float hi = floor(l);
                gl_FragColor = vec4(hi / 255.0, fract(l), 0.0, 1.0);
            }
        """
    }
}
