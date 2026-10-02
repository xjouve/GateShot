package com.gateshot.ui.capture

import android.opengl.GLES20
import android.util.Log
import com.gateshot.processing.stabilize.GlobalShiftEstimator
import com.gateshot.processing.stabilize.RecordingPathPlanner
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
) {
    private var ringTex = IntArray(0)
    private var ringFbo = IntArray(0)
    private var smallTex = 0
    private var smallFbo = 0
    private var prog2d = 0
    private var progLuma = 0
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
    private val planX = RecordingPathPlanner(OPTICAL_WINDOW, SIGMA, holdEnabled = false)
    private val planY = RecordingPathPlanner(OPTICAL_WINDOW, SIGMA, holdEnabled = false)

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
    private var statL1Missing = 0
    private var statPlanMs = 0.0
    private var statPlans = 0

    /**
     * Camera screen opened (GL thread): allocate the ~0.5 GB recording buffer and
     * JIT-warm the L1 planners now, not when Record is pressed (that stalled the
     * first seconds of the first recording to 6-15 fps).
     */
    fun prepare() {
        if (ringTex.isEmpty()) allocate()
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
        if (ringTex.isEmpty()) allocate()
        next = 0L; nextEncode = 0L
        planX.reset(); planY.reset()
        rawByIndex.clear(); sByIndex.clear(); l1PlanX.clear(); l1PlanY.clear()
        tentX.index = -1; tentY.index = -1
        l1ExecX.execute { l1X.reset() }; l1ExecY.execute { l1Y.reset() }
        shifts.clear()
        prevLuma = null
        active = true
    }

    /** Render the current camera frame into the ring and queue its measurement. */
    fun addFrame(ts: Long, gyro: GyroStabilizer.Correction) {
        if (!active) return
        val slot = (next % RING).toInt()
        val index0 = next
        rawByIndex[index0] = doubleArrayOf(gyro.rawY, gyro.rawX)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbo[slot])
        if (gyro.l1Mode) {
            // Self-centred buffer: centre this frame on the planner's latest tentative path
            // (extrapolated), so the L1 path can use the camera's whole field with a buffer
            // only 1.5x the output view (sim on the 2026-09-28 clips: still shots perfectly
            // steady; pans at the Oppo level). The ring then shows content at S, not at C.
            val (gc, _) = crops()
            val pxPerRad = (ringFocal() * RING_H).toDouble()
            val cx = pxPerRad * gyro.rawY; val cy = pxPerRad * gyro.rawX
            val gmx = 0.95 * 0.5 * (gc - 1) * RING_W; val gmy = 0.95 * 0.5 * (gc - 1) * RING_H
            val sx = centre(tentX, cx, index0).coerceIn(cx - gmx, cx + gmx)
            val sy = centre(tentY, cy, index0).coerceIn(cy - gmy, cy + gmy)
            sByIndex[index0] = doubleArrayOf(sx, sy)
            val pxPerHeight = RING_H * gc
            drawCamera(RING_W, RING_H, ((cx - sx) / pxPerHeight).toFloat(), ((cy - sy) / pxPerHeight).toFloat())
        } else {
            drawCamera(RING_W, RING_H, Float.NaN, Float.NaN)
        }
        slotTs[slot] = ts
        slotGyro[slot] = gyro

        // 256x256 luma of the ring frame's centre, for the shift estimator.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
        GLES20.glViewport(0, 0, SMALL, SMALL)
        GLES20.glUseProgram(progLuma)
        val fx = REGION / 2f / RING_W
        val fy = REGION / 2f / RING_H
        setWarp(2 * fx, 0f, 0.5f - fx, 0f, 2 * fy, 0.5f - fy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progLuma, "uTexel"), 1f / RING_W, 1f / RING_H)
        drawRing(progLuma, ringTex[slot])
        readBuf.position(0)
        GLES20.glReadPixels(0, 0, SMALL, SMALL, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val index = next
        next++
        rawByIndex.remove(index - 3 * LOOKAHEAD)
        sByIndex.remove(index - 3 * LOOKAHEAD)
        if (gyro.l1Mode && index >= LOOKAHEAD) scheduleL1(index - LOOKAHEAD, index)
        if (pending.get() >= 3 * WORKERS) {
            // Workers are behind: skip this measurement rather than pile up latency.
            statDropped++
            prevLuma = null
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
        if (p == null) return
        pending.incrementAndGet()
        workers.execute {
            try {
                val t0 = System.nanoTime()
                val s = estimator.estimate(p, luma)
                synchronized(this) { statEstMs += (System.nanoTime() - t0) / 1e6; statEst++ }
                val dx = s.dx * SCALE
                val dy = s.dy * SCALE
                // Low agreement or a huge jump: the frame is dominated by something
                // moving on its own (or blur) -- treat as no measurement.
                // Pans legitimately move the buffered picture by tens of px/frame
                // (15 px rejected half of a real 20x pan).
                if (s.inlierFraction >= 0.4f && abs(dx) < 60f && abs(dy) < 60f) {
                    shifts[index] = floatArrayOf(dx, dy)
                } else statRejected++
            } finally {
                pending.decrementAndGet()
            }
        }
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
        // The last second never got its full look-ahead: plan it with what exists.
        for (k in maxOf(0L, next - LOOKAHEAD) until next) {
            val g = slotGyro[(k % RING).toInt()]
            if (g != null && g.l1Mode && !l1PlanX.containsKey(k)) scheduleL1(k, next - 1)
        }
        while (nextEncode < next) encodeOne(nextEncode++, next - 1, encode)
        active = false
        if (statEst > 0) Log.i(TAG, "optical: est ${"%.1f".format(statEstMs / statEst)}ms avg, " +
            "rejected=$statRejected dropped=$statDropped l1Missing=$statL1Missing " +
            "plan ${"%.1f".format(if (statPlans > 0) statPlanMs / statPlans else 0.0)}ms avg frames=$next")
        statEst = 0; statEstMs = 0.0; statRejected = 0; statDropped = 0; statL1Missing = 0
        statPlanMs = 0.0; statPlans = 0
    }

    /** Plan frame [i] once its look-ahead up to [last] is known (copies the window now). */
    private fun scheduleL1(i: Long, last: Long) {
        val pxPerRad = (ringFocal() * RING_H).toDouble()
        val (gc, crop) = crops()
        val s = windowFraction()
        // Bounds: the camera's whole field (output within the source), intersected with the
        // buffer margin around this frame's centre S (10% of each kept for the optical stage).
        val totX = 0.9 * 0.5 * RING_W * gc * (1 - 1 / crop); val totY = 0.9 * 0.5 * RING_H * gc * (1 - 1 / crop)
        val bufX = 0.9 * 0.5 * (1 - s) * RING_W; val bufY = 0.9 * 0.5 * (1 - s) * RING_H
        val n = (last - i + 1).toInt()
        val cx = DoubleArray(n); val cy = DoubleArray(n)
        val lox = DoubleArray(n); val hix = DoubleArray(n); val loy = DoubleArray(n); val hiy = DoubleArray(n)
        for (q in 0 until n) {
            val r = rawByIndex[i + q]; val sv = sByIndex[i + q]
            cx[q] = pxPerRad * (r?.get(0) ?: 0.0); cy[q] = pxPerRad * (r?.get(1) ?: 0.0)
            val sx = sv?.get(0) ?: cx[q]; val sy = sv?.get(1) ?: cy[q]
            lox[q] = maxOf(cx[q] - totX, sx - bufX); hix[q] = minOf(cx[q] + totX, sx + bufX)
            loy[q] = maxOf(cy[q] - totY, sy - bufY); hiy[q] = minOf(cy[q] + totY, sy + bufY)
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
        val s = windowFraction()
        val m = 0.5f * (1f - s)
        val pxPerRad = (ringFocal() * RING_H).toDouble()
        val l1 = gyro.l1Mode
        // Optical leftover: in L1 mode the buffered picture is the RAW view, so remove the
        // gyro-predicted camera motion from each measurement (what is left is what the gyro
        // cannot see); the smoothing then acts on that residual only.
        for (k in maxOf(1L, i - OPTICAL_WINDOW)..minOf(last, i + OPTICAL_WINDOW)) {
            val sh = shifts[k] ?: continue
            var dx = sh[0].toDouble(); var dy = sh[1].toDouble()
            if (l1) {
                // The ring shows content centred at S: remove S's motion from the measurement.
                val a = sByIndex[k]; val b = sByIndex[k - 1]
                if (a != null && b != null) { dx -= a[0] - b[0]; dy -= a[1] - b[1] }
            }
            planX.addShift(k, dx); planY.addShift(k, dy)
        }
        var cx = planX.plan(i, last, pxPerRad, (m * RING_W).toDouble())
        var cy = planY.plan(i, last, pxPerRad, (m * RING_H).toDouble())
        var l1x = 0.0; var l1y = 0.0
        if (l1) {
            val sv = sByIndex[i]
            val px = try { l1PlanX.remove(i)?.get(80, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            val py = try { l1PlanY.remove(i)?.get(80, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            if (sv != null && px != null && py != null) {
                l1x = sv[0] - px; l1y = sv[1] - py      // S - P: the ring is centred at S
            } else statL1Missing++
            cx += l1x; cy += l1y
        }
        val on = enabled()
        val ox = if (on) (cx / RING_W).toFloat().coerceIn(-m, m) else 0f
        val oy = if (on) (cy / RING_H).toFloat().coerceIn(-m, m) else 0f
        val meas = shifts[i]
        val measured = floatArrayOf(meas?.get(0) ?: Float.NaN, meas?.get(1) ?: Float.NaN, l1x.toFloat(), l1y.toFloat())
        shifts.remove(i - LOOKAHEAD - 1)

        // Output window of the ring frame: scale s = gyroCrop/crop, shifted by (ox, oy).
        // Encoder output (a, b): upright u = b, v = a; ring texcoord = (u', 1 - v').
        GLES20.glUseProgram(prog2d)
        setWarp(0f, s, 0.5f - 0.5f * s + ox, -s, 0f, 0.5f + 0.5f * s - oy)
        encode(slotTs[slot], gyro, ox, oy, measured)
    }

    /** Draw ring frame of the frame being encoded into the current (encoder) surface. */
    fun drawEncoded(ts: Long, width: Int, height: Int) {
        val slot = slotTs.indexOf(ts)
        GLES20.glViewport(0, 0, width, height)
        drawRing(prog2d, ringTex[slot])
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

    private fun allocate() {
        prog2d = link(VERTEX_2D, FRAGMENT_2D)
        progLuma = link(VERTEX_2D, FRAGMENT_LUMA)
        ringTex = IntArray(RING).also { GLES20.glGenTextures(RING, it, 0) }
        ringFbo = IntArray(RING).also { GLES20.glGenFramebuffers(RING, it, 0) }
        for (i in 0 until RING) attach(ringTex[i], ringFbo[i], RING_W, RING_H)
        val t = IntArray(1); val f = IntArray(1)
        GLES20.glGenTextures(1, t, 0); GLES20.glGenFramebuffers(1, f, 0)
        smallTex = t[0]; smallFbo = f[0]
        attach(smallTex, smallFbo, SMALL, SMALL)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun attach(tex: Int, fbo: Int, w: Int, h: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
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
    fun release() {
        active = false
        if (ringTex.isNotEmpty()) {
            GLES20.glDeleteTextures(RING, ringTex, 0)
            GLES20.glDeleteFramebuffers(RING, ringFbo, 0)
            GLES20.glDeleteTextures(1, intArrayOf(smallTex), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
            ringTex = IntArray(0)
        }
        workers.shutdown(); l1ExecX.shutdown(); l1ExecY.shutdown()
    }

    companion object {
        private const val TAG = "OpticalStage"
        const val LOOKAHEAD = 30                 // frames (1.0 s at 30 fps): L1 path look-ahead
        private const val OPTICAL_WINDOW = 12    // +/- frames for the optical leftover smoothing
        private const val L1_MARGIN = 0.8f       // share of the buffer margin the L1 path may use
        private const val WORKER_SLACK = 2       // extra delay so the newest measurements are in
        private const val PLAN_SLACK = 2        // frames a late L1 plan may take before we wait
        private const val RING = LOOKAHEAD + WORKER_SLACK + PLAN_SLACK + 2
        // Look-ahead smoothing: removes what the gyro misses and re-smooths the
        // causal path of a pan (simulated real 20x pan: 2.6 -> 0.6 px jitter).
        private const val SIGMA = 8.0            // frames
        // Ring frame: 1.5x the output view at 20x/30x, 1:1 source pixels (1620/1.5 = 1080).
        private const val RING_W = 1620
        private const val RING_H = 2880
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
        private const val FRAGMENT_2D = """
            // highp: mediump is fp16 on Mali, which snaps texture coordinates of a
            // 4K texture to 1-2 texel steps -- whole-pixel jumps in the output and
            // ~2 px errors in the optical measurement (2026-09-28 A/B).
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D sTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
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
