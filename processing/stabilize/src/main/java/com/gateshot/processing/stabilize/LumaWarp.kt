package com.gateshot.processing.stabilize

import kotlin.math.cos
import kotlin.math.sin

/**
 * CPU affine warp of an NxN luma buffer, used only for the stage-2 residual
 * measurement (cheap at the 512² analysis resolution). Applies rotation about
 * the centre plus translation, sampled bilinearly — the analysis-resolution
 * equivalent of the GPU warp applied to the full-res frames on the live path.
 */
internal object LumaWarp {

    /** Warp [src] (NxN) by rotation [angleRad] about ([cx],[cy]) then translation ([tx],[ty]). */
    fun affine(src: FloatArray, n: Int, cx: Float, cy: Float, angleRad: Float, tx: Float, ty: Float): FloatArray {
        val out = FloatArray(n * n)
        val c = cos(-angleRad)   // inverse rotation
        val s = sin(-angleRad)
        for (oy in 0 until n) {
            for (ox in 0 until n) {
                val dx = ox - cx - tx
                val dy = oy - cy - ty
                val srcX = c * dx + s * dy + cx
                val srcY = -s * dx + c * dy + cy
                out[oy * n + ox] = sampleBilinear(src, n, srcX, srcY)
            }
        }
        return out
    }

    private fun sampleBilinear(src: FloatArray, n: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, n - 1)
        val y0 = y.toInt().coerceIn(0, n - 1)
        val x1 = (x0 + 1).coerceIn(0, n - 1)
        val y1 = (y0 + 1).coerceIn(0, n - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val a = src[y0 * n + x0]; val b = src[y0 * n + x1]
        val cc = src[y1 * n + x0]; val d = src[y1 * n + x1]
        val top = a + (b - a) * fx
        val bot = cc + (d - cc) * fx
        return top + (bot - top) * fy
    }
}
