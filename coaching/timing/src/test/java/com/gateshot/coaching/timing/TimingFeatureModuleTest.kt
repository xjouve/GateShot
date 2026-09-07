package com.gateshot.coaching.timing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimingFeatureModuleTest {

    @Test
    fun `first split has zero elapsed and zero split time`() {
        val split = computeSplit(existing = emptyList(), gateNumber = 1, videoPositionMs = 5_000L)

        assertEquals(1, split.gateNumber)
        assertEquals(5_000L, split.timestamp)
        assertEquals(0L, split.elapsedMs)
        assertEquals(0L, split.splitMs)
    }

    @Test
    fun `second split computes elapsed from first and split from previous`() {
        val first = computeSplit(existing = emptyList(), gateNumber = 1, videoPositionMs = 1_000L)
        val second = computeSplit(existing = listOf(first), gateNumber = 2, videoPositionMs = 3_500L)

        assertEquals(2, second.gateNumber)
        assertEquals(3_500L, second.timestamp)
        assertEquals(2_500L, second.elapsedMs) // since first split (start)
        assertEquals(2_500L, second.splitMs)   // since previous split (== first here)
    }

    @Test
    fun `third split elapsed is from first split, split time is from most recent split`() {
        val first = computeSplit(existing = emptyList(), gateNumber = 1, videoPositionMs = 1_000L)
        val second = computeSplit(existing = listOf(first), gateNumber = 2, videoPositionMs = 3_500L)
        val third = computeSplit(existing = listOf(first, second), gateNumber = 3, videoPositionMs = 4_200L)

        assertEquals(3, third.gateNumber)
        assertEquals(4_200L, third.timestamp)
        assertEquals(3_200L, third.elapsedMs) // 4200 - 1000 (first)
        assertEquals(700L, third.splitMs)     // 4200 - 3500 (last == second)
    }

    @Test
    fun `gate number is carried through unchanged`() {
        val first = computeSplit(existing = emptyList(), gateNumber = 7, videoPositionMs = 0L)
        val next = computeSplit(existing = listOf(first), gateNumber = 42, videoPositionMs = 100L)

        assertEquals(7, first.gateNumber)
        assertEquals(42, next.gateNumber)
    }
}
