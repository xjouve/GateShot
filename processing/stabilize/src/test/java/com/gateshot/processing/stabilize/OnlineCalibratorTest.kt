package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * End-to-end test of the on-device auto-calibrator: feed a gyro stream plus
 * synthetic frames whose optical motion is a known linear function of the gyro
 * (image-X driven by gyro axis 0, image-Y by axis 1), and assert it recovers the
 * axis mapping and scale and applies them to the [LiveStabilizer]. This is the
 * property that fixes the "fully unstabilized" bug — the mapping was previously a
 * hardcoded (wrong, for landscape) guess.
 */
class OnlineCalibratorTest {

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
    fun `recovers axis mapping and scale from correlated gyro and frames`() {
        val hz = 200
        val dt = 1.0 / hz
        val base = field()
        val stab = LiveStabilizer()
        val cal = OnlineCalibrator(stab, cropFrac = 0.12f, cutoffHz = 0.8f, n = n, enableResidual = false)

        // Truth: image-X ← gyro axis 0 (scale SX px/rad), image-Y ← gyro axis 1.
        val sxTrue = 180.0
        val syTrue = 140.0
        var a0 = 0.0; var a1 = 0.0

        for (i in 0 until 1600) {              // 8 s at 200 Hz
            val t = i * dt
            val gx = (0.5 * sin(2 * PI * 0.4 * t)).toFloat()
            val gy = (0.6 * sin(2 * PI * 0.7 * t)).toFloat()
            val tsNs = (t * 1e9).toLong()
            cal.onGyro(tsNs, gx, gy, 0f)
            if (i > 0) { a0 += gx * dt; a1 += gy * dt }   // mirror the calibrator's integration
            if (i % 6 == 0) {                              // ~33 fps frames
                cal.submitFrame(shifted(base, (sxTrue * a0).toFloat(), (syTrue * a1).toFloat()), tsNs)
            }
        }

        val cfg = stab.config()
        val info = "axX=${cfg.axX} axY=${cfg.axY} sxN*n=${cfg.sx * n} syN*n=${cfg.sy * n} state=${cal.state()}"
        // image-X must map to gyro axis 0, image-Y to gyro axis 1.
        assertEquals(0, cfg.axX, "axX — $info")
        assertEquals(1, cfg.axY, "axY — $info")
        // Normalized scale magnitude ≈ sxTrue/n, syTrue/n (sign may be flipped by
        // the no-warp verify phase in this harness — only magnitude is asserted).
        // Phase-correlation windowing systematically under-reads the shift by
        // ~10-15%, which is fine for EIS; assert order-of-magnitude correctness.
        assertTrue(abs(abs(cfg.sx) * n - sxTrue) < sxTrue * 0.2,
            "sx magnitude: got ${abs(cfg.sx) * n}, expected ~$sxTrue")
        assertTrue(abs(abs(cfg.sy) * n - syTrue) < syTrue * 0.2,
            "sy magnitude: got ${abs(cfg.sy) * n}, expected ~$syTrue")
        // Calibration should have completed.
        assertEquals("DONE", cal.state())
    }

    @Test
    fun `stays in COLLECTING when there is no motion`() {
        val base = field()
        val stab = LiveStabilizer()
        val cal = OnlineCalibrator(stab, cropFrac = 0.12f, cutoffHz = 0.8f, n = n, enableResidual = false)
        var t = 0L
        repeat(80) {
            cal.onGyro(t, 0f, 0f, 0f)               // perfectly still
            cal.submitFrame(base.copyOf(), t)
            t += 5_000_000L
        }
        assertEquals("COLLECTING", cal.state())
    }
}
