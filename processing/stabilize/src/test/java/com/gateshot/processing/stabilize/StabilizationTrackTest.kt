package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StabilizationTrackTest {

    private fun track(dx: Float, dy: Float, rotation: Int) = PlaybackStabilizer.Track(
        dxFrac = floatArrayOf(dx),
        dyFrac = floatArrayOf(dy),
        frameIntervalMs = 33f,
        cropFactor = 1.15f,
        rotationDeg = rotation,
        jitterReduction = 0f
    )

    @Test
    fun `display mapping is identity at rotation 0`() {
        assertEquals(0.03f to -0.01f, track(0.03f, -0.01f, 0).displayCorrectionAt(0))
    }

    @Test
    fun `display mapping rotates coded vector clockwise at 90`() {
        assertEquals(0.01f to 0.03f, track(0.03f, -0.01f, 90).displayCorrectionAt(0))
    }

    @Test
    fun `display mapping negates both at 180`() {
        assertEquals(-0.03f to 0.01f, track(0.03f, -0.01f, 180).displayCorrectionAt(0))
    }

    @Test
    fun `display mapping rotates coded vector counterclockwise at 270`() {
        assertEquals(-0.01f to -0.03f, track(0.03f, -0.01f, 270).displayCorrectionAt(0))
    }

    @Test
    fun `wrapped and negative rotations normalize`() {
        val a = track(0.05f, 0.01f, -90).displayCorrectionAt(0)
        val b = track(0.05f, 0.01f, 270).displayCorrectionAt(0)
        assertEquals(b, a)
    }

    @Test
    fun `correction interpolates between analyzed frames`() {
        val t = PlaybackStabilizer.Track(
            dxFrac = floatArrayOf(0f, 0.10f),
            dyFrac = floatArrayOf(0f, -0.10f),
            frameIntervalMs = 100f,
            cropFactor = 1.15f,
            rotationDeg = 0,
            jitterReduction = 0f
        )
        val (dx, dy) = t.correctionAt(50)
        assertEquals(0.05f, dx, 1e-4f)
        assertEquals(-0.05f, dy, 1e-4f)
    }
}
