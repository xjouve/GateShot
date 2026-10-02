package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.sin

/** Pins sign, sub-pixel accuracy and subject-motion robustness of the optical stage. */
class GlobalShiftEstimatorTest {
    private val n = 256
    // Broadband texture: many random plane waves (like real image detail).
    private val waves = java.util.Random(7).let { r ->
        Array(80) { floatArrayOf((r.nextFloat() - 0.5f) * 1.6f, (r.nextFloat() - 0.5f) * 1.6f, r.nextFloat() * 6.28f) }
    }
    private fun tex(x: Float, y: Float): Float {
        var v = 0.0
        for (w in waves) v += sin(w[0] * x + w[1] * y + w[2])
        return v.toFloat()
    }
    /** Frame whose content has moved by (sx, sy): pixel (x, y) shows world (x - sx, y - sy). */
    private fun frame(sx: Float, sy: Float) = FloatArray(n * n) { i -> tex((i % n) - sx, (i / n) - sy) }

    private fun check(dx: Float, dy: Float, tol: Float = 0.05f) {
        val s = GlobalShiftEstimator(n).estimate(frame(0f, 0f), frame(dx, dy))
        assertEquals(dx, s.dx, tol, "dx for ($dx,$dy): got (${s.dx},${s.dy})")
        assertEquals(dy, s.dy, tol, "dy for ($dx,$dy): got (${s.dx},${s.dy})")
    }

    @Test fun `right and down is positive`() = check(3f, 2f)
    @Test fun `left and up is negative`() = check(-4f, -1f)
    @Test fun `subpixel`() = check(1.5f, -0.7f)
    @Test fun `tiny`() = check(0.3f, 0.4f)
    @Test fun `large shift needs the pyramid`() = check(11f, -9f, 0.1f)
    @Test fun `zero`() = check(0f, 0f)

    @Test
    fun `a subject moving on its own does not drag the estimate`() {
        val a = frame(0f, 0f)
        val b = frame(2f, -1f)
        // A 60x90 "racer" patch in the middle moves by (+9, +4) instead.
        for (y in 90 until 180) for (x in 100 until 160) b[y * n + x] = 3f * tex(x - 9f + 500f, y - 4f)
        for (y in 90 until 180) for (x in 100 until 160) a[y * n + x] = 3f * tex(x + 500f, y.toFloat())
        val s = GlobalShiftEstimator(n).estimate(a, b)
        assertEquals(2f, s.dx, 0.25f, "got (${s.dx},${s.dy})")
        assertEquals(-1f, s.dy, 0.25f, "got (${s.dx},${s.dy})")
    }
}
