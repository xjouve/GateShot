package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM tests for the pure-Kotlin stabilizer math (no Android). These cover the
 * riskiest numerics from the `hybrid.py` port: the FFT, phase-correlation peak,
 * scipy-style Gaussian smoothing, gyro integration, and the auto-calibration +
 * sync-offset search.
 */
class StabilizerMathTest {

    @Test
    fun `fft forward then inverse recovers input scaled by n`() {
        val n = 64
        val fft = Fft(n)
        val re = FloatArray(n) { (sin(0.3 * it) + 0.5 * cos(0.17 * it)).toFloat() }
        val im = FloatArray(n) { (0.2 * sin(0.05 * it)).toFloat() }
        val re0 = re.copyOf(); val im0 = im.copyOf()
        fft.transform(re, im, inverse = false)
        fft.transform(re, im, inverse = true)   // unnormalized -> scaled by n
        for (i in 0 until n) {
            assertEquals(re0[i] * n, re[i], 1e-2f, "re[$i]")
            assertEquals(im0[i] * n, im[i], 1e-2f, "im[$i]")
        }
    }

    @Test
    fun `phase correlation recovers a known translation`() {
        val n = 512
        val src = syntheticField(n)
        val tx = 7f; val ty = 5f
        val shifted = LumaWarp.affine(src, n, n / 2f, n / 2f, 0f, tx, ty)
        val s = PhaseCorrelator(n).correlate(src, shifted)
        // Sign convention is internal; assert the magnitude matches within ~1.5 px.
        assertTrue(abs(abs(s[0]) - tx) < 1.5f, "dx recovered=${s[0]} expected ~$tx")
        assertTrue(abs(abs(s[1]) - ty) < 1.5f, "dy recovered=${s[1]} expected ~$ty")
    }

    @Test
    fun `gaussian smoothing preserves a constant signal`() {
        val x = FloatArray(50) { 3.7f }
        val out = PathSmoother.gaussian1d(x, 8f)
        for (v in out) assertEquals(3.7f, v, 1e-3f)
    }

    @Test
    fun `gaussian smoothing reduces variance of a noisy ramp`() {
        val x = FloatArray(100) { it + (if (it % 2 == 0) 5f else -5f) }  // ramp + zig-zag
        val out = PathSmoother.gaussian1d(x, 6f)
        // High-frequency zig-zag should be largely removed mid-signal.
        var maxDevIn = 0f; var maxDevOut = 0f
        for (i in 20 until 80) {
            maxDevIn = maxOf(maxDevIn, abs(x[i] - i))
            maxDevOut = maxOf(maxDevOut, abs(out[i] - i))
        }
        assertTrue(maxDevOut < maxDevIn * 0.5f, "smoothing should cut the zig-zag (in=$maxDevIn out=$maxDevOut)")
    }

    @Test
    fun `gyro integration of constant rate is linear`() {
        val hz = 200
        val secs = 3
        val n = hz * secs
        val t = LongArray(n) { (it.toLong() * 1_000_000_000L / hz) }
        val omega = 0.5f  // rad/s on z
        val track = GyroTrack(t, FloatArray(n), FloatArray(n), FloatArray(n) { omega })
        val tMid = t[n / 2].toDouble()
        val expected = omega * (tMid - t[0]) / 1e9
        assertEquals(expected, track.angleAt(tMid, 2), 1e-3)
    }

    @Test
    fun `calibrator recovers axis, sign, scale and zero sync offset`() {
        val hz = 200
        val secs = 6
        val n = hz * secs
        val t = LongArray(n) { it.toLong() * 1_000_000_000L / hz }
        // Two distinct sinusoidal rates so per-frame angle diffs are well-conditioned.
        val gx = FloatArray(n) { val s = it.toDouble() / hz; (0.15 * sin(2 * Math.PI * 0.3 * s)).toFloat() }
        val gy = FloatArray(n) { val s = it.toDouble() / hz; (0.25 * sin(2 * Math.PI * 0.5 * s + 1.0)).toFloat() }
        val track = GyroTrack(t, gx, gy, FloatArray(n))

        val frames = 30 * secs - 2
        val ft = DoubleArray(frames) { it.toDouble() * 1e9 / 30.0 }
        val sxTrue = 1000.0
        val syTrue = -800.0
        // Truth: image x driven by gyro axis 1, image y by axis 0 (using the same interp).
        val mdx = DoubleArray(frames - 1) { sxTrue * (track.angleAt(ft[it + 1], 1) - track.angleAt(ft[it], 1)) }
        val mdy = DoubleArray(frames - 1) { syTrue * (track.angleAt(ft[it + 1], 0) - track.angleAt(ft[it], 0)) }

        val cal = Calibrator(track).calibrate(ft, mdx, mdy)

        assertEquals(1, cal.axX, "x should map to gyro axis 1")
        assertEquals(0, cal.axY, "y should map to gyro axis 0")
        assertTrue(abs(cal.sx - sxTrue) < 5.0, "sx=${cal.sx}")
        assertTrue(abs(cal.sy - syTrue) < 5.0, "sy=${cal.sy}")
        assertTrue(cal.r2x > 0.99 && cal.r2y > 0.99, "R2 x=${cal.r2x} y=${cal.r2y}")
        assertTrue(abs(cal.offsetNs / 1e6) <= 2.0, "sync offset ms=${cal.offsetNs / 1e6}")
    }

    /** Deterministic smooth field with structure for phase correlation. */
    private fun syntheticField(n: Int): FloatArray {
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val v = 128.0 +
                100.0 * sin(0.10 * x) * cos(0.13 * y) +
                60.0 * sin(0.04 * x + 0.07 * y)
            out[y * n + x] = v.toFloat()
        }
        return out
    }
}
