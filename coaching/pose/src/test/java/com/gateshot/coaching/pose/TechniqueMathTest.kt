package com.gateshot.coaching.pose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TechniqueMathTest {

    @Test
    fun `right angle at knee measures 90 degrees`() {
        val angle = angleAt(Point(0f, 0f), Point(0f, 1f), Point(1f, 1f))
        assertTrue(abs(angle - 90f) < 0.01f, "expected ~90, got $angle")
    }

    @Test
    fun `straight leg measures 180 degrees`() {
        val angle = angleAt(Point(0f, 0f), Point(0f, 1f), Point(0f, 2f))
        assertTrue(abs(angle - 180f) < 0.01f, "expected ~180, got $angle")
    }

    @Test
    fun `degenerate coincident points yield zero angle instead of NaN`() {
        val p = Point(0.5f, 0.5f)
        assertEquals(0f, angleAt(p, p, p))
    }

    @Test
    fun `vertical lean is zero degrees`() {
        val lean = leanAngleDeg(topX = 0.5f, topY = 0f, bottomX = 0.5f, bottomY = 1f)
        assertTrue(abs(lean) < 0.01f)
    }

    @Test
    fun `forward lean is positive when top is ahead of bottom`() {
        val lean = leanAngleDeg(topX = 0.6f, topY = 0f, bottomX = 0.5f, bottomY = 1f)
        assertTrue(lean > 0f)
    }

    @Test
    fun `horizontal shoulders have zero tilt`() {
        val tilt = tiltAngleDeg(leftX = 0.4f, leftY = 0.3f, rightX = 0.6f, rightY = 0.3f)
        assertTrue(abs(tilt) < 0.01f)
    }

    @Test
    fun `percentile matches known distribution`() {
        val sorted = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        assertEquals(1f, percentile(sorted, 0f))
        assertEquals(10f, percentile(sorted, 100f))
        assertTrue(abs(percentile(sorted, 50f) - 5.5f) < 0.01f)
    }

    @Test
    fun `computeStats returns null for empty input`() {
        assertEquals(null, computeStats(emptyList()))
    }

    @Test
    fun `computeStats computes min max mean`() {
        val stats = computeStats(listOf(1f, 2f, 3f, 4f, 5f))!!
        assertEquals(1f, stats.min)
        assertEquals(5f, stats.max)
        assertEquals(3f, stats.mean)
    }

    @Test
    fun `travel direction sign is positive for rightward motion`() {
        assertEquals(1f, travelDirectionSign(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)))
    }

    @Test
    fun `travel direction sign is negative for leftward motion`() {
        assertEquals(-1f, travelDirectionSign(listOf(0.5f, 0.4f, 0.3f, 0.2f, 0.1f)))
    }

    @Test
    fun `travel direction defaults positive with insufficient history`() {
        assertEquals(1f, travelDirectionSign(listOf(0.3f)))
    }

    @Test
    fun `crop stays centered when fully inside frame`() {
        val crop = clampSquareCrop(centerX = 500f, centerY = 500f, side = 200f, frameWidth = 960f, frameHeight = 960f)
        assertEquals(400f, crop.x, 0.01f)
        assertEquals(400f, crop.y, 0.01f)
        assertEquals(200f, crop.width)
    }

    @Test
    fun `crop clamps to frame edges near the origin`() {
        val crop = clampSquareCrop(centerX = 10f, centerY = 10f, side = 200f, frameWidth = 960f, frameHeight = 960f)
        assertEquals(0f, crop.x, 0.01f)
        assertEquals(0f, crop.y, 0.01f)
    }

    @Test
    fun `crop clamps to frame edges near the far corner`() {
        val crop = clampSquareCrop(centerX = 950f, centerY = 950f, side = 200f, frameWidth = 960f, frameHeight = 960f)
        assertEquals(760f, crop.x, 0.01f)
        assertEquals(760f, crop.y, 0.01f)
    }

    @Test
    fun `crop side never falls below the minimum`() {
        val crop = clampSquareCrop(centerX = 100f, centerY = 100f, side = 10f, frameWidth = 960f, frameHeight = 960f, minSide = 160f)
        assertEquals(160f, crop.width)
    }

    @Test
    fun `crop side never exceeds the smaller frame dimension`() {
        val crop = clampSquareCrop(centerX = 100f, centerY = 100f, side = 5000f, frameWidth = 960f, frameHeight = 540f)
        assertEquals(540f, crop.width)
    }

    @Test
    fun `plausible person requires hip knee-or-ankle and reasonable height`() {
        assertTrue(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = true, heightFraction = 0.5f))
        assertFalse(isPlausiblePerson(hipDetected = false, kneeOrAnkleDetected = true, heightFraction = 0.5f))
        assertFalse(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = false, heightFraction = 0.5f))
        assertFalse(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = true, heightFraction = 0.05f))
        assertFalse(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = true, heightFraction = 0.95f))
    }
}
