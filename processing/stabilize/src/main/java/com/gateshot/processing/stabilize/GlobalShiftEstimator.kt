package com.gateshot.processing.stabilize

import kotlin.math.abs
import kotlin.math.floor

/**
 * Global translation between two small grayscale frames, for the capture
 * stabilizer's optical stage (what is left after the gyro warp: hand
 * translation, sync noise). Coarse-to-fine Lucas-Kanade with robust (Huber)
 * weighting, so a subject moving on its own (a racer crossing the frame) is
 * down-weighted instead of dragging the estimate.
 *
 * Returns the content motion (dx right, dy down) from [prev] to [cur], in
 * pixels of the input resolution, plus the fraction of pixels treated as
 * inliers. Phase correlation was rejected here: it read a 3 px shift as 2.7.
 */
class GlobalShiftEstimator(private val size: Int = 256, private val levels: Int = 3) {

    data class Shift(val dx: Float, val dy: Float, val inlierFraction: Float)

    fun estimate(prev: FloatArray, cur: FloatArray): Shift {
        require(prev.size == size * size && cur.size == size * size)
        val pa = pyramid(prev)
        val pb = pyramid(cur)
        var dx = 0f
        var dy = 0f
        var inliers = 0f
        // Coarsest level: exhaustive integer search, so large shifts cannot
        // lock onto a wrong local minimum; gradient refinement takes it from there.
        val top = levels - 1
        val seed = coarseSearch(pa[top], pb[top], size shr top, 6)
        dx = seed.first.toFloat(); dy = seed.second.toFloat()
        for (l in levels - 1 downTo 0) {
            if (l < levels - 1) { dx *= 2f; dy *= 2f }
            val n = size shr l
            val r = refine(pa[l], pb[l], n, dx, dy, if (l == 0) 4 else 3)
            dx = r.dx; dy = r.dy; inliers = r.inlierFraction
        }
        return Shift(dx, dy, inliers)
    }

    private fun pyramid(img: FloatArray): Array<FloatArray> {
        val out = arrayOfNulls<FloatArray>(levels)
        out[0] = img
        for (l in 1 until levels) {
            val src = out[l - 1]!!
            val ns = size shr (l - 1)
            val n = ns / 2
            out[l] = FloatArray(n * n) { i ->
                val x = (i % n) * 2; val y = (i / n) * 2
                0.25f * (src[y * ns + x] + src[y * ns + x + 1] + src[(y + 1) * ns + x] + src[(y + 1) * ns + x + 1])
            }
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<FloatArray>
    }

    private fun coarseSearch(a: FloatArray, b: FloatArray, n: Int, range: Int): Pair<Int, Int> {
        val m = range + 2
        var best = Pair(0, 0)
        var bestCost = Double.MAX_VALUE
        for (sy in -range..range) for (sx in -range..range) {
            var cost = 0.0
            for (y in m until n - m) for (x in m until n - m) {
                cost += abs(b[(y + sy) * n + x + sx] - a[y * n + x])
            }
            if (cost < bestCost) { bestCost = cost; best = Pair(sx, sy) }
        }
        return best
    }

    private fun sample(img: FloatArray, n: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 >= n - 1 || y0 >= n - 1) return Float.NaN
        val fx = x - x0; val fy = y - y0
        val i = y0 * n + x0
        return (img[i] * (1 - fx) + img[i + 1] * fx) * (1 - fy) + (img[i + n] * (1 - fx) + img[i + n + 1] * fx) * fy
    }

    private fun refine(a: FloatArray, b: FloatArray, n: Int, dx0: Float, dy0: Float, iters: Int): Shift {
        var dx = dx0
        var dy = dy0
        val border = maxOf(2, n / 16)
        var inlierFrac = 0f
        val res = FloatArray(n * n)
        // Gradients of the reference frame do not change across iterations.
        val gx = FloatArray(n * n)
        val gy = FloatArray(n * n)
        for (y in 1 until n - 1) for (x in 1 until n - 1) {
            val i = y * n + x
            gx[i] = 0.5f * (a[i + 1] - a[i - 1]); gy[i] = 0.5f * (a[i + n] - a[i - n])
        }
        val absBuf = FloatArray(n * n)
        var c = -1f
        for (it in 0 until iters) {
            // Residuals at the current estimate; robust scale from their median.
            var count = 0
            for (y in border until n - border) for (x in border until n - border) {
                val v = sample(b, n, x + dx, y + dy)
                res[count++] = if (v.isNaN()) Float.NaN else v - a[y * n + x]
            }
            var nf = 0
            for (j in 0 until count) if (!res[j].isNaN()) absBuf[nf++] = abs(res[j])
            if (nf < 32) break
            if (c < 0f) {
                // Robust scale once per level (primitive sort, no boxing).
                java.util.Arrays.sort(absBuf, 0, nf)
                c = maxOf(1.5f * absBuf[nf / 2], 1e-4f)
            }
            var gxx = 0.0; var gxy = 0.0; var gyy = 0.0; var bx = 0.0; var by = 0.0
            var k = 0; var inl = 0
            for (y in border until n - border) for (x in border until n - border) {
                val r = res[k++]
                if (r.isNaN()) continue
                val i = y * n + x
                val ix = gx[i]
                val iy = gy[i]
                val w = if (abs(r) <= c) { inl++; 1.0 } else (c / abs(r)).toDouble()   // Huber
                gxx += w * ix * ix; gxy += w * ix * iy; gyy += w * iy * iy
                bx += w * ix * r; by += w * iy * r
            }
            inlierFrac = inl.toFloat() / nf
            val det = gxx * gyy - gxy * gxy
            if (abs(det) < 1e-9) break
            // Solve G * delta = -b  (content moved by +d means cur(x + d) = prev(x)).
            val ddx = (-(gyy * bx - gxy * by) / det).toFloat()
            val ddy = (-(gxx * by - gxy * bx) / det).toFloat()
            dx += ddx; dy += ddy
            if (abs(ddx) < 0.005f && abs(ddy) < 0.005f) break
        }
        return Shift(dx, dy, inlierFrac)
    }
}
