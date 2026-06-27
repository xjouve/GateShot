package com.gateshot.processing.stabilize

import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal, dependency-free radix-2 FFT (power-of-two sizes only). Pure Kotlin so
 * it runs identically on the JVM (unit tests) and on-device. Used by
 * [PhaseCorrelator]; we deliberately avoid pulling in OpenCV/JTransforms.
 */
internal class Fft(private val n: Int) {
    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
    }

    private val bitRev = IntArray(n).also { rev ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            var x = i; var r = 0
            for (b in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            rev[i] = r
        }
    }
    // Twiddle tables for the forward transform (inverse uses the conjugate).
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    init {
        for (k in 0 until n / 2) {
            val ang = -2.0 * Math.PI * k / n
            cosT[k] = cos(ang).toFloat()
            sinT[k] = sin(ang).toFloat()
        }
    }

    /** In-place 1D FFT (or inverse, unnormalized) on contiguous re/im of length n. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        // Bit-reversal permutation.
        for (i in 0 until n) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var ti = 0
                while (k < half) {
                    val wr = cosT[ti]
                    val wi = if (inverse) -sinT[ti] else sinT[ti]
                    val a = i + k
                    val b = a + half
                    val xr = re[b]; val xi = im[b]
                    val tr = xr * wr - xi * wi
                    val tii = xr * wi + xi * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - tii
                    re[a] += tr
                    im[a] += tii
                    k++; ti += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
