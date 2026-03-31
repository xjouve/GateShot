package com.gateshot.capture.trigger

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TriggerZoneTest {

    @Test
    fun `point at center is inside zone`() {
        val zone = TriggerZone(id = 1, centerX = 0.5f, centerY = 0.5f, radiusX = 0.1f, radiusY = 0.1f)
        assertTrue(zone.containsPoint(0.5f, 0.5f))
    }

    @Test
    fun `point at edge is inside zone`() {
        val zone = TriggerZone(id = 1, centerX = 0.5f, centerY = 0.5f, radiusX = 0.1f, radiusY = 0.1f)
        assertTrue(zone.containsPoint(0.6f, 0.5f))  // Right edge
    }

    @Test
    fun `point outside zone is not contained`() {
        val zone = TriggerZone(id = 1, centerX = 0.5f, centerY = 0.5f, radiusX = 0.1f, radiusY = 0.1f)
        assertFalse(zone.containsPoint(0.8f, 0.8f))
    }

    @Test
    fun `zone at corner of frame`() {
        val zone = TriggerZone(id = 1, centerX = 0.0f, centerY = 0.0f, radiusX = 0.05f, radiusY = 0.05f)
        assertTrue(zone.containsPoint(0.03f, 0.03f))
        assertFalse(zone.containsPoint(0.1f, 0.1f))
    }

    @Test
    fun `elliptical zone shape`() {
        val zone = TriggerZone(id = 1, centerX = 0.5f, centerY = 0.5f, radiusX = 0.2f, radiusY = 0.05f)
        // Wide but short — point far right but near center Y should be inside
        assertTrue(zone.containsPoint(0.65f, 0.5f))
        // Point not far right but far from center Y should be outside
        assertFalse(zone.containsPoint(0.5f, 0.6f))
    }
}
