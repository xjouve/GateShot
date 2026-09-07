package com.gateshot.coaching.pose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TechniqueAnalyzerTest {

    private fun sample(
        t: Long,
        kneeL: Float = 120f,
        kneeR: Float = 120f,
        hipAngle: Float = 100f,
        torsoLean: Float = 10f,
        shoulderTilt: Float = 5f,
        stance: Float = 1f,
        handsForward: Float = 0.2f,
        confidence: Float = 0.6f
    ) = PoseSample(
        timestampMs = t,
        kneeAngleL = kneeL, kneeAngleR = kneeR, hipAngle = hipAngle,
        torsoLeanDeg = torsoLean, shoulderTiltDeg = shoulderTilt,
        stanceWidthRatio = stance, handsForward = handsForward,
        personHeightPx = 200f, confidence = confidence,
        cropRect = NormalizedRect(0.4f, 0.4f, 0.2f, 0.2f)
    )

    @Test
    fun `gate segments split samples between consecutive gates`() {
        val samples = (0..10).map { sample(it * 100L) }
        val segments = buildGateSegments(samples, listOf(0L, 500L, 1000L))
        assertEquals(2, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(500L, segments[0].endMs)
        assertEquals(500L, segments[1].startMs)
        assertEquals(1000L, segments[1].endMs)
    }

    @Test
    fun `fewer than two gates yields no segments`() {
        assertTrue(buildGateSegments(listOf(sample(0L)), listOf(300L)).isEmpty())
        assertTrue(buildGateSegments(listOf(sample(0L)), emptyList()).isEmpty())
    }

    @Test
    fun `upright flag fires when mean knee angle exceeds threshold`() {
        val confident = listOf(sample(0L, kneeL = 160f, kneeR = 160f))
        val stats = computeAllMetricStats(confident)
        val flags = buildFlags(confident, confident, emptyList(), 1f, stats)
        assertTrue(flags.any { it.code == "UPRIGHT" })
    }

    @Test
    fun `no upright flag for normal flexion`() {
        val confident = listOf(sample(0L, kneeL = 110f, kneeR = 110f))
        val stats = computeAllMetricStats(confident)
        val flags = buildFlags(confident, confident, emptyList(), 1f, stats)
        assertTrue(flags.none { it.code == "UPRIGHT" })
    }

    @Test
    fun `low tracking flag fires below 50 percent coverage`() {
        val confident = listOf(sample(0L))
        val stats = computeAllMetricStats(confident)
        val flags = buildFlags(confident, confident, emptyList(), 0.3f, stats)
        assertTrue(flags.any { it.code == "LOW_TRACKING" })
    }

    @Test
    fun `hands back flag fires when hands trail on most samples`() {
        val confident = (0 until 10).map { sample(it * 100L, handsForward = -0.5f) }
        val stats = computeAllMetricStats(confident)
        val flags = buildFlags(confident, confident, emptyList(), 1f, stats)
        assertTrue(flags.any { it.code == "HANDS_BACK" })
    }

    @Test
    fun `straight legs at gate flag fires near a gate passage`() {
        val confident = listOf(sample(1000L, kneeL = 170f, kneeR = 170f))
        val stats = computeAllMetricStats(confident)
        val flags = buildFlags(confident, confident, listOf(1050L), 1f, stats)
        assertTrue(flags.any { it.code == "STRAIGHT_LEGS_AT_GATE" })
    }

    @Test
    fun `narrow stance flag fires when p10 stance ratio is tight`() {
        val confident = (0 until 10).map { sample(it * 100L, stance = 0.5f) }
        val stats = computeAllMetricStats(confident)
        val flags = buildFlags(confident, confident, emptyList(), 1f, stats)
        assertTrue(flags.any { it.code == "NARROW_STANCE" })
    }

    @Test
    fun `key frame selection includes gate passages and stays within bounds`() {
        val samples = (0..20).map { sample(it * 100L) }
        val refs = selectKeyFrames(samples, listOf(500L, 1500L))
        assertTrue(refs.size in 6..8)
        assertTrue(refs.any { it.timestampMs == 500L })
        assertTrue(refs.any { it.timestampMs == 1500L })
    }

    @Test
    fun `key frame selection is sorted by timestamp`() {
        val samples = (0..20).map { sample(it * 100L) }
        val refs = selectKeyFrames(samples, listOf(500L, 1500L))
        assertEquals(refs.sortedBy { it.timestampMs }, refs)
    }

    @Test
    fun `key frame selection deduplicates by timestamp`() {
        val refs = selectKeyFrames(listOf(sample(0L, kneeL = 90f, kneeR = 90f)), listOf(0L))
        assertEquals(1, refs.size)
    }

    @Test
    fun `key frame selection is empty when no sample is confident`() {
        val samples = listOf(sample(0L, confidence = 0.1f))
        assertTrue(selectKeyFrames(samples, listOf(0L)).isEmpty())
    }
}
