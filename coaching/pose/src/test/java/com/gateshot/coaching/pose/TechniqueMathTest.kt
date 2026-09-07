package com.gateshot.coaching.pose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `pixel-space angle is undistorted on a non-square frame while normalized-space is not`() {
        val frameW = 1080f
        val frameH = 1920f
        val hipPx = Point(400f, 700f)
        val kneePx = Point(600f, 900f)
        val anklePx = Point(800f, 700f)

        val pixelAngle = angleAt(hipPx, kneePx, anklePx)
        assertTrue(abs(pixelAngle - 90f) < 1f, "pixel-space angle should be ~90, got $pixelAngle")

        fun norm(p: Point) = Point(p.x / frameW, p.y / frameH)
        val normalizedAngle = angleAt(norm(hipPx), norm(kneePx), norm(anklePx))
        assertTrue(abs(normalizedAngle - 90f) > 5f, "normalized-space angle should be distorted away from 90, got $normalizedAngle")
    }

    @Test
    fun `plausible person requires hip knee-or-ankle and reasonable height`() {
        assertTrue(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = true, heightFraction = 0.5f))
        assertFalse(isPlausiblePerson(hipDetected = false, kneeOrAnkleDetected = true, heightFraction = 0.5f))
        assertFalse(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = false, heightFraction = 0.5f))
        assertFalse(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = true, heightFraction = 0.05f))
        assertFalse(isPlausiblePerson(hipDetected = true, kneeOrAnkleDetected = true, heightFraction = 0.95f))
    }

    // ── Nullable metric helpers ────────────────────────────────────────

    @Test
    fun `angleOrNull is null when any keypoint is missing`() {
        val a = Point(0f, 0f); val b = Point(0f, 1f)
        assertNull(angleOrNull(a, b, null))
        assertNull(angleOrNull(null, b, a))
        assertNotNull(angleOrNull(a, b, Point(1f, 1f)))
    }

    @Test
    fun `leanOrNull is null when a keypoint is missing`() {
        assertNull(leanOrNull(Point(0f, 0f), null))
        assertNotNull(leanOrNull(Point(0f, 0f), Point(0f, 1f)))
    }

    @Test
    fun `stanceRatioOrNull is null when a keypoint is missing`() {
        val a = Point(0f, 0f); val b = Point(1f, 0f)
        assertNull(stanceRatioOrNull(a, b, a, null))
        assertNotNull(stanceRatioOrNull(a, b, Point(0f, 1f), Point(1f, 1f)))
    }

    @Test
    fun `stanceRatioOrNull is clamped to 0 and 4`() {
        val hipsClose = stanceRatioOrNull(Point(0f, 0f), Point(100f, 0f), Point(0f, 1f), Point(0.01f, 1f))
        assertNotNull(hipsClose)
        assertTrue(hipsClose!! <= 4f)
    }

    @Test
    fun `handsForwardOrNull is null when both wrists are missing`() {
        val hip = Point(0f, 0f)
        assertNull(handsForwardOrNull(null, null, hip, Point(1f, 0f), 100f, 1f))
    }

    @Test
    fun `handsForwardOrNull is not null when one wrist is present`() {
        val hip = Point(0f, 0f)
        assertNotNull(handsForwardOrNull(Point(0.5f, 0f), null, hip, Point(1f, 0f), 100f, 1f))
    }

    // ── Shoulder tilt swap-robustness ───────────────────────────────────

    @Test
    fun `wrapTo90 folds values back into range`() {
        assertEquals(-10f, wrapTo90(170f), 0.01f)
        assertEquals(10f, wrapTo90(-170f), 0.01f)
        assertEquals(45f, wrapTo90(45f), 0.01f)
    }

    @Test
    fun `shoulder tilt is unaffected by a left-right swap when hip order is consistent`() {
        // Hips: left at x=0.3, right at x=0.7 (normal order).
        val normalTilt = shoulderTiltDeg(
            lShoulderX = 0.3f, lShoulderY = 0.2f, rShoulderX = 0.7f, rShoulderY = 0.2f,
            lHipX = 0.3f, rHipX = 0.7f
        )
        assertTrue(abs(normalTilt) < 1f, "expected ~0, got $normalTilt")
    }

    @Test
    fun `swapped shoulder keypoints wrap near zero instead of reading 180`() {
        // The detector swapped left/right shoulder labels: the point labeled
        // "left shoulder" is physically on the right (matches the right hip side).
        val swappedTilt = shoulderTiltDeg(
            lShoulderX = 0.7f, lShoulderY = 0.2f, rShoulderX = 0.3f, rShoulderY = 0.2f,
            lHipX = 0.3f, rHipX = 0.7f
        )
        assertTrue(abs(swappedTilt) < 5f, "expected wrapped near 0, got $swappedTilt")
        assertTrue(abs(swappedTilt) < abs(180f - abs(swappedTilt)))
    }

    // ── Strict validity truth table ─────────────────────────────────────

    private fun validInput(
        overall: Float = 0.6f,
        lHip: Float = 0.5f, rHip: Float = 0.5f,
        lKnee: Float = 0.5f, rKnee: Float = 0.5f,
        lAnkle: Float = 0.5f, rAnkle: Float = 0.5f,
        height: Float = 100f, width: Float = 40f
    ) = ValidityInput(overall, lHip, rHip, lKnee, rKnee, lAnkle, rAnkle, height, width)

    @Test
    fun `validity passes a fully confident well-proportioned person`() {
        assertTrue(isValidTrack(validInput()))
    }

    @Test
    fun `validity fails when a knee is missing`() {
        assertFalse(isValidTrack(validInput(lKnee = 0.1f)))
        assertFalse(isValidTrack(validInput(rKnee = 0.1f)))
    }

    @Test
    fun `validity fails when overall confidence is low`() {
        assertFalse(isValidTrack(validInput(overall = 0.3f)))
    }

    @Test
    fun `validity fails when both ankles are missing`() {
        assertFalse(isValidTrack(validInput(lAnkle = 0.1f, rAnkle = 0.1f)))
    }

    @Test
    fun `validity passes when only one ankle is confident`() {
        assertTrue(isValidTrack(validInput(lAnkle = 0.5f, rAnkle = 0.1f)))
    }

    @Test
    fun `validity fails when person height is too small`() {
        assertFalse(isValidTrack(validInput(height = 10f)))
    }

    @Test
    fun `validity fails when bbox is wider than tall`() {
        assertFalse(isValidTrack(validInput(height = 50f, width = 100f)))
    }

    @Test
    fun `validity fails when a hip is missing`() {
        assertFalse(isValidTrack(validInput(lHip = 0.1f)))
    }

    // ── Pan estimation ──────────────────────────────────────────────────

    /** Deterministic integer-hash "texture": aperiodic within any realistic test range, so pan
     *  correlation has a single sharp peak instead of aliasing on a small tile period. */
    private fun tex(x: Int, y: Int): Float {
        val h = (x * 0x2545F491) xor (y * 0x27D4EB2F)
        return ((h xor (h ushr 13)) and 0xFFFF).toFloat()
    }

    @Test
    fun `pan estimate recovers a known shift on a synthetic textured frame`() {
        val w = 80; val h = 60
        val dx = 15; val dy = -8
        val prev = FloatArray(w * h) { i -> tex(i % w, i / w) }
        val curr = FloatArray(w * h) { i -> tex((i % w) - dx, (i / w) - dy) }
        val (edx, edy) = estimatePan(prev, curr, w, h, maxShift = 40)
        assertTrue(abs(edx - dx) <= 2, "expected dx~=$dx, got $edx")
        assertTrue(abs(edy - dy) <= 2, "expected dy~=$dy, got $edy")
    }

    @Test
    fun `pan estimate is near zero for an unshifted frame`() {
        val w = 60; val h = 40
        val frame = FloatArray(w * h) { i -> tex(i % w, i / w) }
        val (edx, edy) = estimatePan(frame, frame, w, h, maxShift = 40)
        assertEquals(0, edx)
        assertEquals(0, edy)
    }

    // ── Motion-saliency candidate search ────────────────────────────────

    @Test
    fun `findSalientCandidates picks the highest-energy blob when untracked`() {
        val w = 100; val h = 40
        val energy = FloatArray(w * h)
        // Blob A: x[0,20) full height, value 2 -> max window energy 2*20*40=1600.
        // Blob B: x[80,100) full height, value 1 -> max window energy 1*20*40=800.
        for (y in 0 until h) {
            for (x in 0 until 20) energy[y * w + x] = 2f
            for (x in 80 until 100) energy[y * w + x] = 1f
        }
        val candidates = findSalientCandidates(energy, w, h)
        assertTrue(candidates.isNotEmpty())
        val top = candidates.first()
        assertTrue(top.centerX < 30f, "expected top candidate near blob A, got center ${top.centerX}")
    }

    @Test
    fun `findSalientCandidates prefers the candidate nearest the previous position`() {
        val w = 100; val h = 40
        val energy = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until 20) energy[y * w + x] = 2f
            for (x in 80 until 100) energy[y * w + x] = 1f
        }
        // Previous racer position was right next to blob B.
        val candidates = findSalientCandidates(energy, w, h, prevX = 90f, prevY = 20f)
        assertTrue(candidates.isNotEmpty())
        val top = candidates.first()
        assertTrue(top.centerX > 70f, "expected top candidate near blob B (closer to previous position), got center ${top.centerX}")
    }

    @Test
    fun `integral image rectSum matches a brute-force sum`() {
        val w = 10; val h = 8
        val data = FloatArray(w * h) { (it % 7).toFloat() }
        val integral = integralImage(data, w, h)
        var expected = 0f
        for (y in 2 until 6) for (x in 1 until 5) expected += data[y * w + x]
        assertEquals(expected, rectSum(integral, w, 1, 2, 5, 6), 0.001f)
    }

    @Test
    fun `boxBlur smooths an isolated spike toward its neighbors`() {
        val w = 9; val h = 9
        val data = FloatArray(w * h)
        data[4 * w + 4] = 81f
        val blurred = boxBlur(data, w, h, radius = 2)
        assertTrue(blurred[4 * w + 4] < 81f)
        assertTrue(blurred[4 * w + 4] > 0f)
    }

    @Test
    fun `thresholdEnergyMap zeroes values at or below the mean plus 2 sigma`() {
        val data = FloatArray(100) { 1f }
        data[50] = 100f
        val energy = thresholdEnergyMap(data, sigmaMultiplier = 2f)
        assertTrue(energy[50] > 0f)
        assertEquals(0f, energy[0])
    }
}
