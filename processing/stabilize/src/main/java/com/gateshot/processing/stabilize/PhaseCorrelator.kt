package com.gateshot.processing.stabilize

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * FFT phase correlation of two NxN single-channel (grayscale) frames, returning
 * the inter-frame translation in pixels at the NxN working resolution. Mirrors
 * the role of `cv2.phaseCorrelate` in the reference `hybrid.py`: a Hanning
 * window, normalized cross-power spectrum, inverse FFT, and a sub-pixel
 * weighted-centroid peak.
 *
 * Sign convention is internally consistent (not necessarily identical to
 * OpenCV's): the downstream [Calibrator] fits a signed scale, and the residual
 * stage uses the cumulative sum of these same measurements, so only consistency
 * matters, not absolute sign.
 */
internal class PhaseCorrelator(val n: Int = 512) {
    private val fft = Fft(n)
    private val win = FloatArray(n).also { w ->
        for (i in 0 until n) w[i] = (0.5 * (1.0 - cos(2.0 * Math.PI * i / (n - 1)))).toFloat()
    }
    // Reusable scratch (NxN complex, plus per-row/col temporaries).
    private val reA = FloatArray(n * n)
    private val imA = FloatArray(n * n)
    private val reB = FloatArray(n * n)
    private val imB = FloatArray(n * n)
    private val tRe = FloatArray(n)
    private val tIm = FloatArray(n)

    /** Returns (dx, dy, peakResponse). Inputs are row-major NxN luma (any scale). */
    fun correlate(a: FloatArray, b: FloatArray): FloatArray {
        loadWindowed(a, reA, imA)
        loadWindowed(b, reB, imB)
        fft2d(reA, imA, inverse = false)
        fft2d(reB, imB, inverse = false)

        // Cross-power spectrum R = A * conj(B), normalized per bin.
        for (i in 0 until n * n) {
            val ar = reA[i]; val ai = imA[i]
            val br = reB[i]; val bi = imB[i]
            var rr = ar * br + ai * bi
            var ri = ai * br - ar * bi
            val mag = sqrt(rr * rr + ri * ri) + 1e-8f
            rr /= mag; ri /= mag
            reA[i] = rr; imA[i] = ri
        }
        fft2d(reA, imA, inverse = true)   // correlation surface in reA (real part)

        // Find the integer peak (largest real value).
        var peakIdx = 0
        var peakVal = reA[0]
        for (i in 1 until n * n) {
            if (reA[i] > peakVal) { peakVal = reA[i]; peakIdx = i }
        }
        val py = peakIdx / n
        val px = peakIdx % n

        // Sub-pixel refinement via a 5x5 weighted centroid (with wraparound).
        var sumW = 0f; var sumX = 0f; var sumY = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val xx = ((px + dx) % n + n) % n
            val yy = ((py + dy) % n + n) % n
            val v = reA[yy * n + xx]
            val w = if (v > 0f) v else 0f
            sumW += w; sumX += w * dx; sumY += w * dy
        }
        val cx = if (sumW > 0f) sumX / sumW else 0f
        val cy = if (sumW > 0f) sumY / sumW else 0f

        // Peak position with sub-pixel offset, wrapped to [-n/2, n/2).
        var fx = px + cx
        var fy = py + cy
        if (fx > n / 2) fx -= n
        if (fy > n / 2) fy -= n
        return floatArrayOf(fx, fy, peakVal)
    }

    private fun loadWindowed(src: FloatArray, re: FloatArray, im: FloatArray) {
        for (y in 0 until n) {
            val wy = win[y]
            val row = y * n
            for (x in 0 until n) {
                re[row + x] = src[row + x] * win[x] * wy
                im[row + x] = 0f
            }
        }
    }

    private fun fft2d(re: FloatArray, im: FloatArray, inverse: Boolean) {
        // Rows (contiguous).
        for (y in 0 until n) {
            val off = y * n
            System.arraycopy(re, off, tRe, 0, n)
            System.arraycopy(im, off, tIm, 0, n)
            fft.transform(tRe, tIm, inverse)
            System.arraycopy(tRe, 0, re, off, n)
            System.arraycopy(tIm, 0, im, off, n)
        }
        // Columns (strided).
        for (x in 0 until n) {
            for (y in 0 until n) { tRe[y] = re[y * n + x]; tIm[y] = im[y * n + x] }
            fft.transform(tRe, tIm, inverse)
            for (y in 0 until n) { re[y * n + x] = tRe[y]; im[y * n + x] = tIm[y] }
        }
        if (inverse) {
            val inv = 1f / (n.toFloat() * n.toFloat())
            for (i in 0 until n * n) { re[i] *= inv; im[i] *= inv }
        }
    }
}
