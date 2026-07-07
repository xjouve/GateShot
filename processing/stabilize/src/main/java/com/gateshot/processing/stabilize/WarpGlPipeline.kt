package com.gateshot.processing.stabilize

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL pipeline for the warp+encode pass: an EGL context bound to the encoder's
 * input [Surface], plus a decoder-output [SurfaceTexture] sampled through an
 * external-OES texture. Each decoded frame is drawn to the encoder surface with
 * a per-frame inverse-affine warp (rotation about centre + translation + crop)
 * applied to the sampling coordinates — the single interpolation the hybrid
 * algorithm relies on. Optionally tonemaps HLG -> SDR for the HDR fallback path.
 */
internal class WarpGlPipeline(
    encoderSurface: Surface,
    private val cropFrac: Float,
    private val tonemapHdr: Boolean,
    /** Optional 4x5 android.graphics.ColorMatrix array (offsets in 0..255). */
    private val colorMatrix: FloatArray? = null
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uStMatrixLoc = 0
    private var uWarpMatrixLoc = 0
    private var uTonemapLoc = 0
    private var uApplyColorLoc = 0
    private var uColorMatLoc = 0
    private var uColorOffsetLoc = 0

    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var decoderSurface: Surface
        private set

    private val stMatrix = FloatArray(16)
    private val warpMatrix = FloatArray(16)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }

    init {
        setupEgl(encoderSurface)
        setupGl()
    }

    private fun setupEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupGl() {
        program = buildProgram(VERTEX_SHADER, if (tonemapHdr) FRAG_SHADER_HLG else FRAG_SHADER_PLAIN)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uWarpMatrixLoc = GLES20.glGetUniformLocation(program, "uWarpMatrix")
        uTonemapLoc = GLES20.glGetUniformLocation(program, "uTonemap")
        uApplyColorLoc = GLES20.glGetUniformLocation(program, "uApplyColor")
        uColorMatLoc = GLES20.glGetUniformLocation(program, "uColorMat")
        uColorOffsetLoc = GLES20.glGetUniformLocation(program, "uColorOffset")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_MIRRORED_REPEAT)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_MIRRORED_REPEAT)

        surfaceTexture = SurfaceTexture(texId)
        decoderSurface = Surface(surfaceTexture)
    }

    /** Pull the latest decoded frame into the external texture. */
    fun updateTexImage() {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
    }

    /**
     * Draw the current frame to the encoder surface with the given correction.
     * [angleRad] rotation about centre; [txNorm]/[tyNorm] translation in
     * normalized units (pixels / width, pixels / height).
     */
    fun drawFrame(angleRad: Float, txNorm: Float, tyNorm: Float, ptsNs: Long) {
        buildWarpMatrix(angleRad, txNorm, tyNorm)
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

        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uWarpMatrixLoc, 1, false, warpMatrix, 0)
        if (uTonemapLoc >= 0) GLES20.glUniform1i(uTonemapLoc, if (tonemapHdr) 1 else 0)

        // Optional color grade: android 4x5 ColorMatrix split into a GL mat4
        // (column-major) plus an offset vec4 (rescaled from 0..255 to 0..1).
        val cm = colorMatrix
        if (uApplyColorLoc >= 0) GLES20.glUniform1i(uApplyColorLoc, if (cm != null) 1 else 0)
        if (cm != null && uColorMatLoc >= 0) {
            val m = floatArrayOf(
                cm[0], cm[5], cm[10], cm[15],
                cm[1], cm[6], cm[11], cm[16],
                cm[2], cm[7], cm[12], cm[17],
                cm[3], cm[8], cm[13], cm[18]
            )
            GLES20.glUniformMatrix4fv(uColorMatLoc, 1, false, m, 0)
            GLES20.glUniform4f(
                uColorOffsetLoc,
                cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f
            )
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt_setPresentationTime(ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Maps base tex coords (0..1) to the source sampling coords implementing
     * crop + inverse-affine (sample = M^-1(cropMap(base))).
     */
    private fun buildWarpMatrix(angleRad: Float, txNorm: Float, tyNorm: Float) {
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
        val cx = 0.5f; val cy = 0.5f
        val scale = 1f - 2f * cropFrac
        Matrix.setIdentityM(warpMatrix, 0)
        // translate(c) * R^-1 * translate(-(c+t)) * translate(crop) * scale(1-2crop)
        Matrix.translateM(warpMatrix, 0, cx, cy, 0f)
        Matrix.rotateM(warpMatrix, 0, -angleDeg, 0f, 0f, 1f)
        Matrix.translateM(warpMatrix, 0, -(cx + txNorm), -(cy + tyNorm), 0f)
        Matrix.translateM(warpMatrix, 0, cropFrac, cropFrac, 0f)
        Matrix.scaleM(warpMatrix, 0, scale, scale, 1f)
    }

    private fun EGLExt_setPresentationTime(ptsNs: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        if (::decoderSurface.isInitialized) decoderSurface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
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
        // x, y, u, v — full-screen triangle strip.
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
            uniform int uApplyColor;
            uniform mat4 uColorMat;
            uniform vec4 uColorOffset;
            varying vec2 vTex;
            void main() {
                vec4 c = texture2D(sTexture, vTex);
                if (uApplyColor == 1) {
                    c = clamp(uColorMat * c + uColorOffset, 0.0, 1.0);
                }
                gl_FragColor = c;
            }
        """

        // HLG -> linear -> Reinhard -> gamma, an approximate HDR->SDR fallback.
        private const val FRAG_SHADER_HLG = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES sTexture;
            uniform int uTonemap;
            varying vec2 vTex;
            float hlgToLinear(float e) {
                const float a = 0.17883277;
                const float b = 0.28466892; // 1 - 4a
                const float c = 0.55991073; // 0.5 - a*ln(4a)
                if (e <= 0.5) return (e * e) / 3.0;
                return (exp((e - c) / a) + b) / 12.0;
            }
            void main() {
                vec4 s = texture2D(sTexture, vTex);
                vec4 c = s;
                if (uTonemap == 1) {
                    vec3 lin = vec3(hlgToLinear(s.r), hlgToLinear(s.g), hlgToLinear(s.b));
                    vec3 mapped = lin / (lin + vec3(1.0));      // Reinhard
                    vec3 srgb = pow(mapped, vec3(1.0 / 2.2));   // gamma
                    c = vec4(srgb, 1.0);
                }
                if (uApplyColor == 1) {
                    c = clamp(uColorMat * c + uColorOffset, 0.0, 1.0);
                }
                gl_FragColor = c;
            }
        """
    }
}
