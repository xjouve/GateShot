package com.gateshot.platform.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.gateshot.processing.stabilize.LiveStabilizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Real-time electronic stabilization spliced into the CameraX pipeline via the
 * effects API ([SurfaceProcessor] + `CameraEffect`). One camera input stream is
 * sampled through an external-OES texture and warped per-frame on the GPU, then
 * fanned out to every output (preview + video). The per-frame warp (rotation
 * about centre + translation + crop) comes from [LiveStabilizer.correction], the
 * causal gyro smoother — so the same correction stabilizes both the live preview
 * and the recorded file with zero added latency.
 *
 * GL work runs on a single dedicated thread that owns the EGL context. CameraX
 * callbacks are delivered on [glExecutor] (which posts to that thread), and the
 * input [SurfaceTexture]'s frame-available callback fires there too, so no
 * cross-thread GL access occurs.
 *
 * Ported from the offline `WarpGlPipeline`; generalized from a single encoder
 * surface to N output surfaces sharing one context, and driven live instead of
 * from precomputed per-frame corrections.
 */
class StabilizingSurfaceProcessor(
    private val stabilizer: LiveStabilizer,
    private val calibrator: com.gateshot.processing.stabilize.OnlineCalibrator? = null,
) : SurfaceProcessor, com.gateshot.processing.stabilize.OnlineCalibrator.WarpController {

    /** Held false by the calibrator while it measures raw motion (warp = identity). */
    @Volatile private var warpActive: Boolean = true
    override fun setWarpActive(active: Boolean) { warpActive = active }

    /** Crop margin per side; must match the [LiveStabilizer.Config.cropFrac]. */
    @Volatile var cropFrac: Float = 0.12f

    /** Tonemap HLG10 → SDR (HDR fallback). Set before the first frame. */
    @Volatile var tonemapHdr: Boolean = false

    /**
     * Extra 180° rotation baked into the warp — the Hasselblad periscope module is
     * mounted upside-down, so at tele the frames arrive flipped. With the effect
     * active this replaces the `PreviewView.rotation` hack (which only fixed the
     * preview, not the recording).
     */
    @Volatile var rotate180: Boolean = false

    private val glThread = HandlerThread("StabGL").also { it.start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { cmd -> glHandler.post(cmd) }

    /** Executor for CameraX to deliver processor callbacks on. */
    fun executor(): Executor = glExecutor

    // ---- EGL / GL state (only touched on glThread) ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uStMatrixLoc = 0
    private var uWarpMatrixLoc = 0
    private var uTonemapLoc = 0

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private val stMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val warpMatrix = FloatArray(16)

    private class OutTarget(
        val output: SurfaceOutput,
        val surface: Surface,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
    )

    private val outputs = ArrayList<OutTarget>(2)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }

    private var released = false
    private var frameCount = 0

    // Stage-2 optical residual: render the warped frame to a small FBO every
    // RESIDUAL_SAMPLE_EVERY frames, read it back, and hand the luma to the tracker
    // on a background thread. Drop-if-busy so it never stalls the GL thread.
    private var fbo = 0
    private var fboTex = 0
    private val readBuf = ByteBuffer.allocateDirect(RES_N * RES_N * 4).order(ByteOrder.nativeOrder())
    private val residualBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val residualExecutor =
        if (calibrator != null) java.util.concurrent.Executors.newSingleThreadExecutor() else null

    // ------------------------------------------------------------------
    // SurfaceProcessor
    // ------------------------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            if (released) { request.willNotProvideSurface(); return@post }
            ensureEgl()
            // Tear down any previous input (rebind).
            inputSurface?.release()
            inputSurfaceTexture?.release()

            val st = SurfaceTexture(texId)
            val size = request.resolution
            st.setDefaultBufferSize(size.width, size.height)
            st.setOnFrameAvailableListener({ glHandler.post { drawFrame() } }, glHandler)
            val surface = Surface(st)
            inputSurfaceTexture = st
            inputSurface = surface
            stabilizer.reset()
            calibrator?.reset()

            request.provideSurface(surface, glExecutor) { result ->
                // Camera released the surface — drop it.
                surface.release()
                st.release()
                if (inputSurface === surface) { inputSurface = null; inputSurfaceTexture = null }
                Log.i(TAG, "input surface released code=${result.resultCode}")
            }
            Log.i(TAG, "input surface provided ${size.width}x${size.height}")
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        Log.i(TAG, "onOutputSurface called targets=${output.targets} size=${output.size}")
        glHandler.post {
            if (released) { output.close(); return@post }
            ensureEgl()
            val surface = output.getSurface(glExecutor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    glHandler.post { removeOutput(output) }
                }
            }
            val egl = createWindowSurface(surface) ?: run {
                Log.e(TAG, "failed to create EGL surface for output targets=${output.targets}")
                output.close(); return@post
            }
            val size = output.size
            outputs.add(OutTarget(output, surface, egl, size.width, size.height))
            Log.i(TAG, "output surface added targets=${output.targets} ${size.width}x${size.height}")
        }
    }

    private fun removeOutput(output: SurfaceOutput) {
        val it = outputs.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (o.output === output) {
                if (o.eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, o.eglSurface)
                o.output.close()
                it.remove()
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-frame draw
    // ------------------------------------------------------------------

    private fun drawFrame() {
        val st = inputSurfaceTexture ?: return
        // Always consume the buffer (even with no outputs) so the queue drains.
        makeCurrentPbuffer()
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage failed: ${e.message}")
            return
        }
        st.getTransformMatrix(stMatrix)
        frameCount++
        // Confirm rendering started, then go quiet (avoid logspam at 30/60 fps).
        if (frameCount == 1 || frameCount % 1800 == 0) Log.i(TAG, "frame $frameCount outputs=${outputs.size}")
        if (outputs.isEmpty()) return

        val ts = st.timestamp
        // While the calibrator is measuring raw motion it holds the warp at
        // identity (just the static crop), so the readback is uncontaminated.
        val corr = if (warpActive) stabilizer.correction()
            else LiveStabilizer.Correction(0f, 0f, 0f)
        if (frameCount % 60 == 0) {
            val cfg = stabilizer.config()
            val outs = outputs.joinToString(",") { it.output.targets.toString() }
            Log.i(TAG, "corr tx=${"%.4f".format(corr.txNorm)} ty=${"%.4f".format(corr.tyNorm)} " +
                "active=$warpActive cal=${calibrator?.state()} sx=${"%.3f".format(cfg.sx)} " +
                "sy=${"%.3f".format(cfg.sy)} crop=$cropFrac outs=[$outs]")
        }
        buildWarpMatrix(corr.rollRad, corr.txNorm, corr.tyNorm)

        for (o in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, o.eglSurface, o.eglSurface, eglContext)) {
                Log.w(TAG, "eglMakeCurrent(output) failed"); continue
            }
            // Fold the camera ST transform together with this output's required
            // transform (crop/rotation/mirror) — exactly what CameraX expects.
            o.output.updateTransformMatrix(outMatrix, stMatrix)

            GLES20.glViewport(0, 0, o.width, o.height)
            drawQuad(outMatrix)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, o.eglSurface, ts)
            EGL14.eglSwapBuffers(eglDisplay, o.eglSurface)
        }

        // Feed the readback to the auto-calibrator / residual tracker.
        if (calibrator != null && frameCount % RESIDUAL_SAMPLE_EVERY == 0) {
            sampleResidual(ts)
        }
    }

    /** Draw the camera texture warped by [warpMatrix], sampled via [stTransform]. */
    private fun drawQuad(stTransform: FloatArray) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stTransform, 0)
        GLES20.glUniformMatrix4fv(uWarpMatrixLoc, 1, false, warpMatrix, 0)
        if (uTonemapLoc >= 0) GLES20.glUniform1i(uTonemapLoc, if (tonemapHdr) 1 else 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Render the warped frame to the small FBO, read it back, and hand the luma to
     * the residual tracker on a background thread. Skips entirely if the tracker
     * is still busy with the previous frame (graceful degradation → gyro-only).
     */
    private fun sampleResidual(ts: Long) {
        val exec = residualExecutor ?: return
        if (fbo == 0) return
        if (residualBusy.get()) return  // tracker behind — skip, stay real-time

        makeCurrentPbuffer()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, RES_N, RES_N)
        // stMatrix (camera transform) keeps the readback in a stable orientation.
        drawQuad(stMatrix)
        readBuf.rewind()
        GLES20.glReadPixels(0, 0, RES_N, RES_N, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        readBuf.rewind()
        val bytes = ByteArray(RES_N * RES_N * 4)
        readBuf.get(bytes)
        residualBusy.set(true)
        exec.execute {
            try {
                val luma = FloatArray(RES_N * RES_N)
                var j = 0
                for (i in luma.indices) {
                    val r = bytes[j].toInt() and 0xFF
                    val g = bytes[j + 1].toInt() and 0xFF
                    val b = bytes[j + 2].toInt() and 0xFF
                    luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
                    j += 4
                }
                calibrator?.submitFrame(luma, ts)
            } finally {
                residualBusy.set(false)
            }
        }
    }

    /**
     * Map base tex coords (0..1) to the source sampling coords: crop in, then the
     * inverse-affine correction (rotate −angle about centre + translate), plus the
     * fixed 180° periscope flip when engaged.
     */
    private fun buildWarpMatrix(angleRad: Float, txNorm: Float, tyNorm: Float) {
        // The periscope 180° flip is folded into the rotation below to right the
        // upside-down tele sensor. But a 180° rotation also inverts the sign of the
        // translation applied after it, so the SAME gyro correction pushes the
        // opposite way at tele vs. wide — i.e. a sign that's calibrated at 1× is
        // wrong once the periscope engages (stabilization fails exactly at tele).
        // Pre-negate the translation under the flip so the correction direction is
        // consistent across the wide↔tele boundary and the 1× calibration carries.
        val flip = rotate180
        val tx = if (flip) -txNorm else txNorm
        val ty = if (flip) -tyNorm else tyNorm
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat() + (if (flip) 180f else 0f)
        val cx = 0.5f; val cy = 0.5f
        val crop = cropFrac
        val scale = 1f - 2f * crop
        Matrix.setIdentityM(warpMatrix, 0)
        Matrix.translateM(warpMatrix, 0, cx, cy, 0f)
        Matrix.rotateM(warpMatrix, 0, -angleDeg, 0f, 0f, 1f)
        Matrix.translateM(warpMatrix, 0, -(cx + tx), -(cy + ty), 0f)
        Matrix.translateM(warpMatrix, 0, crop, crop, 0f)
        Matrix.scaleM(warpMatrix, 0, scale, scale, 1f)
    }

    // ------------------------------------------------------------------
    // EGL setup / teardown
    // ------------------------------------------------------------------

    private fun ensureEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeCurrentPbuffer()
        setupGl()
    }

    private fun setupGl() {
        program = buildProgram(VERTEX_SHADER, if (tonemapHdr) FRAG_SHADER_HLG else FRAG_SHADER_PLAIN)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uWarpMatrixLoc = GLES20.glGetUniformLocation(program, "uWarpMatrix")
        uTonemapLoc = GLES20.glGetUniformLocation(program, "uTonemap")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_MIRRORED_REPEAT)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_MIRRORED_REPEAT)

        // Readback FBO (RES_N²) for the calibrator / residual tracker.
        if (calibrator != null) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            fboTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, RES_N, RES_N, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            fbo = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTex, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "residual FBO incomplete: $status"); fbo = 0
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    private fun makeCurrentPbuffer() {
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        }
    }

    private fun createWindowSurface(surface: Surface): EGLSurface? {
        return try {
            val s = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (s == EGL14.EGL_NO_SURFACE) null else s
        } catch (e: Exception) {
            Log.e(TAG, "eglCreateWindowSurface failed: ${e.message}"); null
        }
    }

    /** Release everything. Safe to call once; further callbacks are ignored. */
    fun release() {
        residualExecutor?.shutdownNow()
        glHandler.post {
            released = true
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (fboTex != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            fbo = 0; fboTex = 0
            for (o in outputs) {
                if (o.eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, o.eglSurface)
                o.output.close()
            }
            outputs.clear()
            inputSurface?.release(); inputSurface = null
            inputSurfaceTexture?.release(); inputSurfaceTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            pbufferSurface = EGL14.EGL_NO_SURFACE
            glThread.quitSafely()
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val TAG = "StabProcessor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        // Residual-tracker readback resolution (power of two for the FFT) and how
        // often we sample (every Nth frame → ~10 Hz at 30 fps).
        private const val RES_N = 256
        private const val RESIDUAL_SAMPLE_EVERY = 3

        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform mat4 uWarpMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vec4 warped = uWarpMatrix * vec4(aTexCoord.xy, 0.0, 1.0);
                vTex = (uSTMatrix * vec4(warped.xy, 0.0, 1.0)).xy;
            }
        """

        private const val FRAG_SHADER_PLAIN = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform int uTonemap;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(sTexture, vTex);
            }
        """

        private const val FRAG_SHADER_HLG = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES sTexture;
            uniform int uTonemap;
            varying vec2 vTex;
            float hlgToLinear(float e) {
                const float a = 0.17883277;
                const float b = 0.28466892;
                const float c = 0.55991073;
                if (e <= 0.5) return (e * e) / 3.0;
                return (exp((e - c) / a) + b) / 12.0;
            }
            void main() {
                vec4 s = texture2D(sTexture, vTex);
                if (uTonemap == 1) {
                    vec3 lin = vec3(hlgToLinear(s.r), hlgToLinear(s.g), hlgToLinear(s.b));
                    vec3 mapped = lin / (lin + vec3(1.0));
                    vec3 srgb = pow(mapped, vec3(1.0 / 2.2));
                    gl_FragColor = vec4(srgb, 1.0);
                } else {
                    gl_FragColor = s;
                }
            }
        """
    }
}
