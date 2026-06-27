package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM tests for the causal live stabilizer. These cover the properties that make
 * it safe for real-time use: it is strictly causal (no dependence on future
 * samples), it attenuates high-frequency shake while preserving intended low-
 * frequency panning, the gyro→pixel scale wiring is correct, and the correction
 * never exceeds the crop margin.
 */
class LiveStabilizerTest {

    private val hz = 200
    private fun ts(i: Int): Long = i.toLong() * 1_000_000_000L / hz

    /** Feed a constant-rate yaw stream and read the correction after [n] samples. */
    private fun feedConstRate(stab: LiveStabilizer, n: Int, rate: Float, axis: Int) {
        for (i in 0 until n) {
            val g = FloatArray(3)
            g[axis] = rate
            stab.onGyro(ts(i), g[0], g[1], g[2])
        }
    }

    @Test
    fun `correction is strictly causal — a value read at frame k never changes later`() {
        val cfg = LiveStabilizer.Config(axX = 0, sx = 200f, axY = 1, sy = 200f, cropFrac = 1f)
        // Streaming run: capture the correction at a set of "frame" indices.
        val streaming = LiveStabilizer(cfg)
        val frameIdx = intArrayOf(10, 40, 90, 150, 199)
        val captured = HashMap<Int, LiveStabilizer.Correction>()
        for (i in 0 until 200) {
            val s = i.toDouble() / hz
            val gx = (0.4 * sin(2 * PI * 0.3 * s) + 0.2 * sin(2 * PI * 6.0 * s)).toFloat()
            val gy = (0.3 * sin(2 * PI * 0.5 * s + 1.0)).toFloat()
            streaming.onGyro(ts(i), gx, gy, 0f)
            if (i in frameIdx) captured[i] = streaming.correction()
        }
        // For each frame k, a fresh stabilizer fed only the first k+1 samples must
        // produce exactly the captured correction — i.e. nothing after k mattered.
        for (k in frameIdx) {
            val prefix = LiveStabilizer(cfg)
            for (i in 0..k) {
                val s = i.toDouble() / hz
                val gx = (0.4 * sin(2 * PI * 0.3 * s) + 0.2 * sin(2 * PI * 6.0 * s)).toFloat()
                val gy = (0.3 * sin(2 * PI * 0.5 * s + 1.0)).toFloat()
                prefix.onGyro(ts(i), gx, gy, 0f)
            }
            val c = prefix.correction()
            val exp = captured[k]!!
            assertEquals(exp.txNorm, c.txNorm, 1e-6f, "tx at frame $k")
            assertEquals(exp.tyNorm, c.tyNorm, 1e-6f, "ty at frame $k")
        }
    }

    @Test
    fun `high-frequency shake is strongly attenuated in the smoothed path`() {
        val stab = LiveStabilizer(LiveStabilizer.Config(cutoffHz = 0.8f, axX = 0, sx = 1f))
        var sumRaw = 0.0; var sumRaw2 = 0.0
        var sumSm = 0.0; var sumSm2 = 0.0
        var nObs = 0
        val secs = 6
        val total = hz * secs
        for (i in 0 until total) {
            val s = i.toDouble() / hz
            // Pure 8 Hz jitter, zero mean — no intended motion.
            val gx = (1.0 * sin(2 * PI * 8.0 * s)).toFloat()
            stab.onGyro(ts(i), gx, 0f, 0f)
            if (i > hz) { // skip warm-up
                val r = stab.rawAngle(0); val m = stab.smoothedAngle(0)
                sumRaw += r; sumRaw2 += r * r; sumSm += m; sumSm2 += m * m; nObs++
            }
        }
        val stdRaw = sqrt(sumRaw2 / nObs - (sumRaw / nObs) * (sumRaw / nObs))
        val stdSm = sqrt(sumSm2 / nObs - (sumSm / nObs) * (sumSm / nObs))
        assertTrue(stdSm < 0.3 * stdRaw,
            "smoothed jitter should be <30% of raw (raw=$stdRaw sm=$stdSm)")
    }

    @Test
    fun `intended low-frequency panning is preserved`() {
        val stab = LiveStabilizer(LiveStabilizer.Config(cutoffHz = 0.8f, axX = 0, sx = 1f, cropFrac = 1f))
        val rate = 0.2f // rad/s slow pan, well below cutoff
        val n = hz * 4
        feedConstRate(stab, n, rate, axis = 0)
        val raw = stab.rawAngle(0)
        val sm = stab.smoothedAngle(0)
        // The smoothed path should track the ramp closely (lag bounded by ~tau).
        assertTrue(sm > 0.7 * raw, "smoothed should follow the pan (raw=$raw sm=$sm)")
    }

    @Test
    fun `gyro to pixel scale wiring is correct on a fresh step`() {
        // cropFrac large so we read the unclamped scaled value.
        val sx = 1000f
        val stab = LiveStabilizer(LiveStabilizer.Config(axX = 0, sx = sx, cropFrac = 1f))
        val angle = 1e-4f // rad — tiny so the smoother barely moves in one step
        stab.onGyro(ts(0), 0f, 0f, 0f)           // establish t0
        stab.onGyro(ts(1), angle * hz, 0f, 0f)   // omega*dt = angle over one 1/hz step
        val tx = stab.correction().txNorm
        // correction ≈ sx*(smooth - raw) ≈ sx*(0 - angle) = -sx*angle.
        assertEquals(-sx * angle, tx, abs(sx * angle) * 0.02f, "tx=$tx")
    }

    @Test
    fun `correction never exceeds the crop margin under violent motion`() {
        val crop = 0.12f
        val stab = LiveStabilizer(LiveStabilizer.Config(axX = 0, sx = 5000f, axY = 1, sy = 5000f, cropFrac = crop))
        for (i in 0 until hz * 3) {
            val s = i.toDouble() / hz
            val gx = (3.0 * sin(2 * PI * 5.0 * s)).toFloat()  // fast, large
            val gy = (3.0 * sin(2 * PI * 4.0 * s + 0.5)).toFloat()
            stab.onGyro(ts(i), gx, gy, 0f)
            val c = stab.correction()
            assertTrue(abs(c.txNorm) <= crop + 1e-6f, "tx=${c.txNorm} > crop")
            assertTrue(abs(c.tyNorm) <= crop + 1e-6f, "ty=${c.tyNorm} > crop")
        }
    }

    @Test
    fun `fromIntrinsics produces the expected normalized scale`() {
        val cfg = LiveStabilizer.fromIntrinsics(
            fxPx = 4000f, fyPx = 4000f, width = 4000, height = 3000,
            cropFrac = 0.1f, axX = 1, signX = -1f, axY = 0, signY = 1f,
        )
        // sx = signX * fx/width = -1 * 4000/4000 = -1.0 (norm fraction per rad)
        assertEquals(-1.0f, cfg.sx, 1e-6f)
        assertEquals(4000f / 3000f, cfg.sy, 1e-5f)
        assertEquals(1, cfg.axX)
        assertEquals(0, cfg.axY)
    }
}
