package com.gateshot.processing.stabilize

import kotlin.math.exp

/**
 * 1D Gaussian smoothing matching `scipy.ndimage.gaussian_filter1d` with the
 * default `mode='reflect'` (half-sample symmetric) and `truncate=4.0`. Edge
 * behaviour matters: the gyro/residual correction is `smooth - raw`, so a
 * mismatched boundary corrupts the first/last ~4σ frames.
 */
internal object PathSmoother {

    fun gaussian1d(x: FloatArray, sigma: Float): FloatArray {
        val n = x.size
        if (sigma <= 0f || n == 0) return x.copyOf()
        val radius = (4.0 * sigma + 0.5).toInt().coerceAtLeast(1)
        // Symmetric Gaussian kernel, normalized.
        val kernel = DoubleArray(2 * radius + 1)
        var sum = 0.0
        val s2 = (sigma * sigma).toDouble()
        for (k in -radius..radius) {
            val w = exp(-(k * k) / (2.0 * s2))
            kernel[k + radius] = w; sum += w
        }
        for (i in kernel.indices) kernel[i] /= sum

        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (k in -radius..radius) {
                acc += kernel[k + radius] * x[reflect(i + k, n)]
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    /** scipy 'reflect' (half-sample symmetric): edges are repeated, e.g. -1 -> 0. */
    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        val period = 2 * n
        var m = ((i % period) + period) % period
        if (m >= n) m = period - 1 - m
        return m
    }
}
