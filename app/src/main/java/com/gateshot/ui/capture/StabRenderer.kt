package com.gateshot.ui.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the GL thread between the camera and its outputs. The camera writes into
 * [cameraSurface]; every frame is warped once by the [GyroStabilizer] correction
 * and drawn to the viewfinder and, while recording, to the encoder. The same
 * warped frame reaches both, so what the user sees is what is recorded.
 *
 * Geometry: the camera SurfaceTexture delivers the frame in the phone's natural
 * (portrait) orientation but upside down, because of the periscope/teleconverter
 * optics. All warp maths is done in the upright portrait image, y down, in units
 * of the image height.
 *
 * The viewfinder is gyro-only (zero latency). The recording goes through
 * [OpticalStage] as well: a 0.4 s buffer that removes the motion the gyro
 * cannot see before each frame is encoded.
 */
class StabRenderer(
    previewTexture: SurfaceTexture,
    private val previewWidth: Int,
    private val previewHeight: Int,
    streamWidth: Int,
    streamHeight: Int,
    private val stabilizer: GyroStabilizer,
    /** Big Cat logo burned into the bottom-right corner of recorded video (not the viewfinder). */
    private val watermark: android.graphics.Bitmap? = null,
) {
    private val thread = HandlerThread("GateShotStabGL").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uSt = 0
    private var uWarp = 0
    private val stMatrix = FloatArray(16)
    private val warp = FloatArray(9)
    private val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD); position(0) }

    private lateinit var cameraTexture: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set

    /** Per recorded frame (GL thread): timestamp, gyro correction, optical offset (ring fraction). */
    var onFrame: ((Long, GyroStabilizer.Correction, Float, Float, FloatArray?) -> Unit)? = null
    private lateinit var stage: OpticalStage
    private var current = GyroStabilizer.Correction(0f, 0f, 0.0, 0.0, false)
    private var released = false
    private var stLogged = false
    private val bootToMonotonicNs = android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime()

    // Once-a-second diagnostics.
    private var statFrames = 0
    private var statClamped = 0
    private var statMaxPx = 0f
    private var statGyroLagMs = 0.0
    private var statStart = 0L

    // Declared before init: Kotlin runs initializers and init blocks in source order,
    // and init already sets up the watermark on the GL thread.
    private var wmTex = 0
    private var wmProg = 0
    private val wmQuad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        runOnGl {
            setupEgl(previewTexture)
            setupGl()
            setupWatermark()
            stage = OpticalStage({ w, h, ox, oy ->
                // Exact texel copy for the recording buffer: whole-pixel offset, no
                // rolling-shutter warp, nearest sampling. The encode pass resamples once.
                buildWarp(current.copy(offX = ox, offY = oy, rsX = 0f, rsY = 0f), stabilizer.gyroCropZoom,
                    qxa = 1f, qxb = 0f, qya = 0f, qyb = -1f, viewAsp = stabilizer.srcAspect)
                oesFilter(GLES20.GL_NEAREST)
                draw(w, h)
                oesFilter(GLES20.GL_LINEAR)
            }, { Pair(stabilizer.gyroCropZoom, stabilizer.cropZoom) },
                { stabilizer.gyroCropZoom / stabilizer.cropZoom },
                { stabilizer.focalPerRad * stabilizer.gyroCropZoom }, { stabilizer.enabled },
                srcW = streamHeight, srcH = streamWidth)
            stage.prepare()
            cameraTexture = SurfaceTexture(texId).apply {
                setDefaultBufferSize(streamWidth, streamHeight)
                setOnFrameAvailableListener({ drawFrame() }, handler)
            }
            cameraSurface = Surface(cameraTexture)
        }
    }

    /** The zoom level changed (not while recording): resize the recording buffer for it. */
    fun viewChanged() = runOnGl { if (encoderEgl == EGL14.EGL_NO_SURFACE) stage.prepare() }

    private fun oesFilter(mode: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, mode)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, mode)
    }

    /** Attach (or detach with null) the encoder input surface. Blocks until done. */
    fun setEncoder(surface: Surface?, width: Int, height: Int) = runOnGl {
        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            stage.finish(::encodeFrame)
            EGL14.eglMakeCurrent(display, previewEgl, previewEgl, context)
            EGL14.eglDestroySurface(display, encoderEgl)
            encoderEgl = EGL14.EGL_NO_SURFACE
        }
        if (surface != null) {
            encoderEgl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            encoderWidth = width
            encoderHeight = height
            stage.start()
        }
    }

    private fun drawFrame() {
        if (released) return
        cameraTexture.updateTexImage()
        cameraTexture.getTransformMatrix(stMatrix)
        if (!stLogged) {
            stLogged = true
            Log.i(TAG, "stMatrix=${stMatrix.joinToString { "%.5f".format(it) }}")
        }
        val ts = cameraTexture.timestamp
        val c = stabilizer.correctionFor(ts)
        logStats(ts, c)

        EGL14.eglMakeCurrent(display, previewEgl, previewEgl, context)
        // Viewfinder: portrait view, output (a, b) with b up -> image (a, 1 - b).
        buildWarp(c, stabilizer.cropZoom, qxa = 1f, qxb = 0f, qya = 0f, qyb = -1f)
        draw(previewWidth, previewHeight)
        EGL14.eglSwapBuffers(display, previewEgl)

        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            // Recording: buffer the gyro-warped frame, measure what is left, and
            // encode frames whose optical lookahead is complete.
            current = c.forRing()
            stage.addFrame(ts, c)
            stage.encodeReady(::encodeFrame)
        }
    }

    private fun encodeFrame(ts: Long, gyro: GyroStabilizer.Correction, ox: Float, oy: Float, measured: FloatArray?) {
        EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)
        // Encoder: landscape buffer tagged with a 90-degree orientation hint.
        stage.drawEncoded(ts, encoderWidth, encoderHeight)
        drawWatermark()
        // Camera timestamps are BOOTTIME; MediaRecorder's audio track uses
        // MONOTONIC, so shift video onto that clock or A/V drift apart.
        EGLExt.eglPresentationTimeANDROID(display, encoderEgl, ts - bootToMonotonicNs)
        EGL14.eglSwapBuffers(display, encoderEgl)
        onFrame?.invoke(ts, gyro, ox, oy, measured)
        EGL14.eglMakeCurrent(display, previewEgl, previewEgl, context)
    }

    /**
     * Output texcoord (a, b) -> camera texcoord. The output position in the
     * upright image is q = (qx * viewAsp, qy) with qx = qxa(a-.5) + qxb(b-.5),
     * qy = qya(a-.5) + qyb(b-.5). The source is sampled at s = q / zoom + offset,
     * then mapped into the upside-down natural texture: t = (0.5 - sx/aspect, 0.5 + sy).
     */
    private fun buildWarp(c: GyroStabilizer.Correction, z: Float, qxa: Float, qxb: Float, qya: Float, qyb: Float,
                          viewAsp: Float = stabilizer.viewAspect) {
        val asp = stabilizer.srcAspect
        val r = viewAsp / asp       // output width as a fraction of the source width (at z = 1)
        // q.x / aspect and q.y as affine in (a, b, 1).
        val xa = qxa; val xb = qxb; val xc = -0.5f * (qxa + qxb)
        val ya = qya; val yb = qyb; val yc = -0.5f * (qya + qyb)
        // Source position before rolling shutter: X = s.x/aspect (source width units,
        // -0.5..0.5), Y = s.y. Rolling shutter shifts column X by (rsX, rsY) * X:
        // X' = X * (1 + rsX/aspect), Y' = Y + rsY * X. Still affine, so one matrix.
        // t.x = 0.5 - X' ; t.y = 0.5 + Y'
        val pxA = xa * r / z; val pxB = xb * r / z; val pxC = xc * r / z + c.offX / asp
        val pyA = ya / z; val pyB = yb / z; val pyC = yc / z + c.offY
        val kx = 1f + c.rsX / asp
        val txA = -pxA * kx; val txB = -pxB * kx; val txC = 0.5f - pxC * kx
        val tyA = pyA + c.rsY * pxA; val tyB = pyB + c.rsY * pxB; val tyC = 0.5f + pyC + c.rsY * pxC
        // Column-major mat3.
        warp[0] = txA; warp[1] = tyA; warp[2] = 0f
        warp[3] = txB; warp[4] = tyB; warp[5] = 0f
        warp[6] = txC; warp[7] = tyC; warp[8] = 1f
    }

    private fun draw(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix3fv(uWarp, 1, false, warp, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupWatermark() {
        val bmp = watermark ?: return
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); wmTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        wmProg = link(compile(GLES20.GL_VERTEX_SHADER, WM_VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, WM_FRAGMENT))
        // Upright video (u right, v down, 1080x1920) -> stored landscape buffer with the
        // 90-degree hint: stored a = v, b = u (b up), i.e. NDC (x, y) = (2v - 1, 2u - 1).
        val d = WM_DIAMETER_PX; val margin = WM_MARGIN_PX
        val du = d / 1080f; val dv = d / 1920f
        val u0 = 1f - margin / 1080f - du; val v0 = 1f - margin / 1920f - dv
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)   // (s, t) of the logo, t down
        for ((s, tt) in corners) {
            val u = u0 + s * du; val v = v0 + tt * dv
            wmQuad.put(2 * v - 1).put(2 * u - 1).put(s).put(tt)
        }
        wmQuad.position(0)
    }

    private fun drawWatermark() {
        if (wmTex == 0) return
        GLES20.glUseProgram(wmProg)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)   // bitmap is premultiplied
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTex)
        val aPos = GLES20.glGetAttribLocation(wmProg, "aPosition"); val aTex = GLES20.glGetAttribLocation(wmProg, "aTexCoord")
        wmQuad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, wmQuad); GLES20.glEnableVertexAttribArray(aPos)
        wmQuad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, wmQuad); GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(wmProg, "uAlpha"), WM_OPACITY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun logStats(ts: Long, c: GyroStabilizer.Correction) {
        if (statStart == 0L) statStart = ts
        statFrames++
        if (c.clamped) statClamped++
        val px = maxOf(kotlin.math.abs(c.offX), kotlin.math.abs(c.offY)) * 3840f
        if (px > statMaxPx) statMaxPx = px
        statGyroLagMs = (ts - stabilizer.latestGyroNs()) / 1e6
        if (ts - statStart >= 1_000_000_000L) {
            Log.i(TAG, "stab fps=$statFrames maxCorr=${statMaxPx.toInt()}px(4K) clamped=$statClamped " +
                "frameMinusGyro=${"%.1f".format(statGyroLagMs)}ms enabled=${stabilizer.enabled}")
            statFrames = 0; statClamped = 0; statMaxPx = 0f; statStart = ts
        }
    }

    private fun setupEgl(previewTexture: SurfaceTexture) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        check(num[0] > 0) { "No recordable EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        previewEgl = EGL14.eglCreateWindowSurface(display, config, previewTexture, intArrayOf(EGL14.EGL_NONE), 0)
        check(previewEgl != EGL14.EGL_NO_SURFACE) { "Viewfinder surface unavailable" }
        EGL14.eglMakeCurrent(display, previewEgl, previewEgl, context)
    }

    private fun setupGl() {
        program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSt = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uWarp = GLES20.glGetUniformLocation(program, "uWarp")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Shader: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun link(vs: Int, fs: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Program: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    /** Run [block] on the GL thread and wait for it; rethrows its failure. */
    private fun runOnGl(block: () -> Unit) {
        if (Thread.currentThread() == thread) { block(); return }
        var error: Throwable? = null
        val done = CountDownLatch(1)
        handler.post {
            try { block() } catch (t: Throwable) { error = t } finally { done.countDown() }
        }
        check(done.await(3, TimeUnit.SECONDS)) { "GL thread timed out" }
        error?.let { throw it }
    }

    fun release() {
        try {
            runOnGl {
                released = true
                if (::stage.isInitialized) stage.release()
                if (wmTex != 0) GLES20.glDeleteTextures(1, intArrayOf(wmTex), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEgl)
                    if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEgl)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                encoderEgl = EGL14.EGL_NO_SURFACE
                previewEgl = EGL14.EGL_NO_SURFACE
                if (::cameraSurface.isInitialized) cameraSurface.release()
                if (::cameraTexture.isInitialized) cameraTexture.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release: ${t.message}")
        }
        thread.quitSafely()
    }

    companion object {
        private const val WM_DIAMETER_PX = 160f      // on the 1080-wide upright video
        private const val WM_MARGIN_PX = 28f
        private const val WM_OPACITY = 0.85f
        private const val WM_VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = aTexCoord; }
        """
        private const val WM_FRAGMENT = """
            precision mediump float;
            uniform sampler2D sTex;
            uniform float uAlpha;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex) * uAlpha; }
        """
        private const val TAG = "StabRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform mat3 uWarp;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vec3 t = uWarp * vec3(aTexCoord, 1.0);
                vTex = (uSTMatrix * vec4(t.xy, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            // highp: mediump is fp16 on Mali, which snaps texture coordinates of a
            // 4K texture to 1-2 texel steps -- whole-pixel jumps in the output and
            // ~2 px errors in the optical measurement (2026-09-28 A/B).
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES sTexture;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTexture, vTex); }
        """
    }
}
