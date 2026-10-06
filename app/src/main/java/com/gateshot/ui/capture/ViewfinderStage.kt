package com.gateshot.ui.capture

import android.opengl.GLES20
import android.util.Log
import com.gateshot.processing.stabilize.GlobalShiftEstimator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Viewfinder shown one frame late, so that each displayed frame can be placed with its own
 * image measurement. GL thread only, except the estimate, which runs on its own thread.
 *
 * The gyro alone mis-predicts the image step by 2-3 px every frame, at random
 * (build/qa/stab_m9/g2/vfsim2.py): a gyro-only viewfinder shook by 1.2-2.3 px above 3 Hz
 * while the recording of the same clip held 0.06 px. No filter removes that, and a
 * measurement that arrives after the frame was shown makes it worse (each late correction
 * is a jump). So the frame waits one frame interval (33 ms) for a cheap estimate of its
 * shift against the previous frame.
 *
 * The view is held against the measured image path, not the gyro's: the two drift apart
 * by 150-230 px in 13 s, so a view held still in gyro terms glides across the picture
 * (the user's "image moves by itself", build/qa/stab_m9/g3/vfsim3.py). A frame without a
 * usable measurement advances on the gyro step. From the gyro stage's correction only the
 * rolling-shutter terms are used.
 */
internal class ViewfinderStage(
    /** Draw the camera frame, unwarped (an exact copy), into the bound framebuffer. */
    private val drawCamera: (width: Int, height: Int) -> Unit,
    /** Camera frame in the upright portrait image, px. */
    private val srcW: Int,
    private val srcH: Int,
) {
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var smallTex = 0
    private var smallFbo = 0
    private var progView = 0
    private var progLuma = 0
    private val warp = FloatArray(9)
    private val readBuf = ByteBuffer.allocateDirect(SMALL * SMALL * 4).order(ByteOrder.nativeOrder())
    private val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD); position(0) }

    // Display priority: the estimate has one frame interval, and at default priority it
    // averaged 26 ms and missed it on 15% of frames (2026-10-06); a frame placed on the
    // gyro step instead is 2-3 px off, which shows as a jump.
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            r.run()
        }, "GateShotViewfinder")
    }
    private val estimator = GlobalShiftEstimator(HALF)

    /** The buffered frames' gyro corrections and timestamps, by slot. */
    private val held = arrayOfNulls<GyroStabilizer.Correction>(2)
    private val heldTs = LongArray(2)
    private var count = 0L
    private var prevLuma: FloatArray? = null
    private var prevRaw: DoubleArray? = null
    /** Estimate of the newest buffered frame against the one before, and its gyro step (px). */
    private class Job(val result: Future<GlobalShiftEstimator.Shift>, val gx: Double, val gy: Double)
    private var job: Job? = null
    /** Measured image path (source px, x/y) and the hold it is viewed through. */
    private val path = DoubleArray(2)
    private val holdX = ViewHold()
    private val holdY = ViewHold()
    private var lastOff = FloatArray(2)
    private val gainXY = DoubleArray(2)
    private val gainXX = DoubleArray(2)

    private var statFrames = 0
    private var statLate = 0
    private var statGated = 0
    private var statEstMs = 0.0
    private var statEst = 0

    /** Forget the buffered frame and the paths (zoom change, stabilization toggled). */
    fun reset() {
        held[0] = null; held[1] = null
        prevLuma = null; prevRaw = null; job = null
        path.fill(0.0); holdX.reset(); holdY.reset(); gainXY.fill(0.0); gainXX.fill(0.0)
    }

    private fun gain(axis: Int): Double =
        if (gainXX[axis] > GAIN_MIN_POWER) (gainXY[axis] / gainXX[axis]).coerceIn(0.7, 1.1) else 1.0

    /**
     * Take in the current camera frame (correction [c]) and draw the previous one into the
     * current surface. Returns false when there is no previous frame yet: the caller then
     * draws the current frame itself.
     *
     * [pxPerRad] is the focal length in source px; [zoom] the view's crop of the source;
     * [srcAspect] and [viewAspect] the widths of the source and of the view in units of
     * their heights.
     */
    fun show(ts: Long, c: GyroStabilizer.Correction, pxPerRad: Double, zoom: Float, srcAspect: Float, viewAspect: Float,
             width: Int, height: Int): Boolean {
        if (tex[0] == 0) allocate()
        val slot = (count % 2).toInt()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[slot])
        drawCamera(srcW, srcH)

        // 256x256 luma of the frame's centre, as the recording stage measures it.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
        GLES20.glViewport(0, 0, SMALL, SMALL)
        GLES20.glUseProgram(progLuma)
        val fx = REGION / 2f / srcW; val fy = REGION / 2f / srcH
        setWarp(2 * fx, 0f, 0.5f - fx, 0f, 2 * fy, 0.5f - fy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progLuma, "uTexel"), 1f / srcW, 1f / srcH)
        drawTex(progLuma, tex[slot])
        readBuf.position(0)
        GLES20.glReadPixels(0, 0, SMALL, SMALL, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // Reduced 2:1 while decoding: the estimate works at 128 px only (a quarter of the
        // cost of the recording's, 0.03 px rms from it; GlobalShiftRealFramesTest).
        val luma = FloatArray(HALF * HALF)
        for (y in 0 until HALF) {
            for (x in 0 until HALF) {
                var sum = 0f
                for (q in 0 until 4) {
                    // GL rows are bottom-up; store top-down
                    val i = ((SMALL - 1 - (2 * y + q / 2)) * SMALL + 2 * x + q % 2) * 4
                    sum += (readBuf.get(i).toInt() and 0xff) + (readBuf.get(i + 1).toInt() and 0xff) / 255f
                }
                luma[y * HALF + x] = 0.25f * sum
            }
        }

        // The previous frame: its measurement was started one frame ago.
        val shown = held[1 - slot]
        if (shown != null) {
            job?.let { j ->
                val s = try { j.result.get(WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
                if (s == null) { j.result.cancel(false); statLate++ }
                val kx = gain(0); val ky = gain(1)
                val dx = (s?.dx ?: 0f) * SCALE.toDouble(); val dy = (s?.dy ?: 0f) * SCALE.toDouble()
                // Same test as the recording (OpticalStage.settle): a measurement far from
                // the gyro prediction is wrong; that frame moves on the scaled gyro step.
                val ok = s != null && s.inlierFraction >= MIN_INLIERS &&
                    Math.hypot(dx - kx * j.gx, dy - ky * j.gy) <= GATE_PX + GATE_FRACTION * Math.hypot(j.gx, j.gy)
                if (ok) {
                    path[0] += dx; path[1] += dy
                    gainXY[0] += GAIN_RATE * (dx * j.gx - gainXY[0]); gainXX[0] += GAIN_RATE * (j.gx * j.gx - gainXX[0])
                    gainXY[1] += GAIN_RATE * (dy * j.gy - gainXY[1]); gainXX[1] += GAIN_RATE * (j.gy * j.gy - gainXX[1])
                } else {
                    if (s != null) statGated++
                    path[0] += kx * j.gx; path[1] += ky * j.gy
                }
            }
            // Sample offset in image heights: image path minus held path, inside the margin
            // the crop leaves. The filter works in radians, as for the gyro paths.
            val mx = 0.5f * (srcAspect - viewAspect / zoom); val my = 0.5f * (1f - 1f / zoom)
            val focal = pxPerRad / srcH                       // image heights per radian
            val offX = (holdX.step(heldTs[1 - slot], path[0] / pxPerRad, mx / focal) * focal).toFloat().coerceIn(-mx, mx)
            val offY = (holdY.step(heldTs[1 - slot], path[1] / pxPerRad, my / focal) * focal).toFloat().coerceIn(-my, my)
            lastOff[0] = offX * srcH; lastOff[1] = offY * srcH
            // As StabRenderer.buildWarp, for a buffered frame: view (a, b), b up, samples the
            // source at X = (a - .5) * r / zoom + offX / aspect (source widths), Y = -(b - .5) / zoom
            // + offY; rolling shutter X' = kx * X, Y' = Y + rsY * X; texcoord (.5 + X', .5 - Y').
            val r = viewAspect / srcAspect
            val pxA = r / zoom; val pxC = -0.5f * r / zoom + offX / srcAspect
            val pyB = -1f / zoom; val pyC = 0.5f / zoom + offY
            val k = 1f + shown.rsX / srcAspect
            GLES20.glUseProgram(progView)
            setWarp(k * pxA, 0f, 0.5f + k * pxC, -shown.rsY * pxA, -pyB, 0.5f - pyC - shown.rsY * pxC)
            GLES20.glViewport(0, 0, width, height)
            drawTex(progView, tex[1 - slot])
        }

        // This frame's measurement, for when it is shown.
        val raw = doubleArrayOf(c.rawY, c.rawX)          // yaw -> x, pitch -> y
        val p = prevLuma; val pr = prevRaw
        job = if (p != null && pr != null) {
            val gx = pxPerRad * (raw[0] - pr[0]); val gy = pxPerRad * (raw[1] - pr[1])
            val sx = (gain(0) * gx / SCALE).toFloat(); val sy = (gain(1) * gy / SCALE).toFloat()
            Job(exec.submit<GlobalShiftEstimator.Shift> {
                val t0 = System.nanoTime()
                val s = estimator.refineFrom(p, luma, sx, sy)
                synchronized(this) { statEstMs += (System.nanoTime() - t0) / 1e6; statEst++ }
                s
            }, gx, gy)
        } else null
        prevLuma = luma; prevRaw = raw
        held[slot] = c; heldTs[slot] = ts
        count++
        if (++statFrames >= 300) {
            val (ms, n) = synchronized(this) { Pair(statEstMs, statEst).also { statEstMs = 0.0; statEst = 0 } }
            Log.i(TAG, "viewfinder: est ${"%.1f".format(if (n > 0) ms / n else 0.0)}ms avg, late=$statLate gated=$statGated of $statFrames, " +
                "offset ${"%.0f".format(lastOff[0])},${"%.0f".format(lastOff[1])} px")
            statFrames = 0; statLate = 0; statGated = 0
        }
        return shown != null
    }

    /** mat3 (column-major) for t = (ta*a + tb*b + tc, ua*a + ub*b + uc). */
    private fun setWarp(ta: Float, tb: Float, tc: Float, ua: Float, ub: Float, uc: Float) {
        warp[0] = ta; warp[1] = ua; warp[2] = 0f
        warp[3] = tb; warp[4] = ub; warp[5] = 0f
        warp[6] = tc; warp[7] = uc; warp[8] = 1f
    }

    private fun drawTex(program: Int, texture: Int) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
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
        progView = link(VERTEX_2D, FRAGMENT_VIEW)
        progLuma = link(VERTEX_2D, FRAGMENT_LUMA)
        GLES20.glGenTextures(2, tex, 0); GLES20.glGenFramebuffers(2, fbo, 0)
        // Linear: the view is scaled to the screen. The luma pass samples texel centres, where
        // linear and nearest agree.
        for (i in 0 until 2) attach(tex[i], fbo[i], srcW, srcH, GLES20.GL_LINEAR)
        val t = IntArray(1); val f = IntArray(1)
        GLES20.glGenTextures(1, t, 0); GLES20.glGenFramebuffers(1, f, 0)
        smallTex = t[0]; smallFbo = f[0]
        attach(smallTex, smallFbo, SMALL, SMALL, GLES20.GL_NEAREST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Log.i(TAG, "viewfinder buffer ${srcW}x$srcH x2 (${srcW.toLong() * srcH * 8 / 1_000_000} MB)")
    }

    private fun attach(texture: Int, framebuffer: Int, w: Int, h: Int, filter: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Viewfinder buffer ${w}x$h unavailable"
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
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(2, tex, 0); GLES20.glDeleteFramebuffers(2, fbo, 0)
            GLES20.glDeleteTextures(1, intArrayOf(smallTex), 0); GLES20.glDeleteFramebuffers(1, intArrayOf(smallFbo), 0)
            tex[0] = 0
        }
        exec.shutdown()
    }

    companion object {
        private const val TAG = "ViewfinderStage"
        private const val SMALL = 256
        private const val REGION = 768           // source px measured (centre square)
        private const val HALF = SMALL / 2
        private const val SCALE = REGION.toFloat() / HALF
        private const val WAIT_MS = 10L          // for an estimate started a frame ago
        // As the recording (OpticalStage).
        private const val GATE_PX = 10.0
        private const val GATE_FRACTION = 0.3
        private const val GAIN_RATE = 1.0 / 30
        private const val GAIN_MIN_POWER = 50.0
        private const val MIN_INLIERS = 0.2f
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
        private const val FRAGMENT_VIEW = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D sTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
        // 3x3 box average before the 3:1 downsample, then 16-bit luma in two channels
        // (as OpticalStage; highp because mediump is fp16 on Mali).
        private const val FRAGMENT_LUMA = """
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
                float l = dot(s / 9.0, vec3(0.299, 0.587, 0.114)) * 255.0;
                float hi = floor(l);
                gl_FragColor = vec4(hi / 255.0, fract(l), 0.0, 1.0);
            }
        """
    }
}
