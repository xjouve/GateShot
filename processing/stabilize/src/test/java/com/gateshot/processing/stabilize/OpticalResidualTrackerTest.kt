package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM tests for the Stage-2 optical-residual tracker. The tracker is a closed
 * loop on-device (it reads back the warped output), but these tests drive it
 * open-loop with synthetic frames to verify the safety-critical properties:
 * it stays quiet on still frames, reacts within the crop clamp to sustained
 * jitter, never exceeds the clamp, and rejects implausible (mismatch) measurements.
 */
class OpticalResidualTrackerTest {

    private val n = 256

    private fun field(): FloatArray {
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            out[y * n + x] = (128.0 +
                100.0 * sin(0.10 * x) * cos(0.13 * y) +
                60.0 * sin(0.04 * x + 0.07 * y)).toFloat()
        }
        return out
    }

    private fun shifted(src: FloatArray, tx: Float, ty: Float): FloatArray =
        LumaWarp.affine(src, n, n / 2f, n / 2f, 0f, tx, ty)

    @Test
    fun `still frames keep the correction near zero`() {
        val stab = LiveStabilizer()
        val tr = OpticalResidualTracker(stab, n)
        val f = field()
        var t = 0L
        repeat(30) { tr.submitFrame(f.copyOf(), t); t += 33_000_000L }
        val (rx, ry) = tr.residual()
        assertEquals(0f, rx, 1e-4f)
        assertEquals(0f, ry, 1e-4f)
    }

    @Test
    fun `sustained jitter yields a bounded, non-zero, clamped correction`() {
        val clamp = 0.06f
        val stab = LiveStabilizer()
        val tr = OpticalResidualTracker(stab, n, OpticalResidualTracker.Config(clamp = clamp))
        val base = field()
        var t = 0L
        var maxMag = 0f
        // Alternate a small ±2px shake every frame — pure high-frequency jitter.
        repeat(120) { i ->
            val s = if (i % 2 == 0) 2f else -2f
            tr.submitFrame(shifted(base, s, s), t)
            t += 33_000_000L
            val (rx, ry) = tr.residual()
            assertTrue(abs(rx) <= clamp + 1e-6f && abs(ry) <= clamp + 1e-6f,
                "correction exceeded clamp: ($rx,$ry)")
            maxMag = maxOf(maxMag, abs(rx), abs(ry))
        }
        // It should have reacted to the jitter at some point (non-trivial correction).
        assertTrue(maxMag > 1e-3f, "tracker did not react; peak correction=$maxMag")
    }

    @Test
    fun `implausible jumps are rejected and do not move the correction`() {
        val stab = LiveStabilizer()
        val tr = OpticalResidualTracker(stab, n, OpticalResidualTracker.Config(maxStep = 0.02f))
        val base = field()
        tr.submitFrame(base.copyOf(), 0)               // seed
        val before = tr.residual()
        // A 40px shift at n=256 ≈ 0.156 fraction ≫ maxStep → must be dropped.
        tr.submitFrame(shifted(base, 40f, 0f), 33_000_000L)
        val after = tr.residual()
        assertEquals(before.first, after.first, 1e-6f)
        assertEquals(before.second, after.second, 1e-6f)
    }

    @Test
    fun `reset clears state`() {
        val stab = LiveStabilizer()
        val tr = OpticalResidualTracker(stab, n)
        val base = field()
        var t = 0L
        repeat(20) { i -> tr.submitFrame(shifted(base, if (i % 2 == 0) 2f else -2f, 0f), t); t += 33_000_000L }
        tr.reset()
        val (rx, ry) = tr.residual()
        assertEquals(0f, rx, 1e-6f)
        assertEquals(0f, ry, 1e-6f)
        assertEquals(1f, tr.detectedSign(), 1e-6f)
    }
}
