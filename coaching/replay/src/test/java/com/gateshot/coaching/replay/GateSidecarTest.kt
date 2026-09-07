package com.gateshot.coaching.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateSidecarTest {

    @Test
    fun `round trip through format and parse preserves timestamps`() {
        val timestamps = listOf(0L, 1200L, 5430L, 99999L)
        val formatted = formatGateTimestamps(timestamps)
        val parsed = parseGateTimestamps(formatted.split("\n"))
        assertEquals(timestamps, parsed)
    }

    @Test
    fun `parse skips blank lines silently`() {
        val lines = listOf("100", "", "   ", "200")
        assertEquals(listOf(100L, 200L), parseGateTimestamps(lines))
    }

    @Test
    fun `parse skips non-numeric lines`() {
        val lines = listOf("100", "not-a-number", "200")
        assertEquals(listOf(100L, 200L), parseGateTimestamps(lines))
    }

    @Test
    fun `parse skips negative values`() {
        val lines = listOf("100", "-50", "200")
        assertEquals(listOf(100L, 200L), parseGateTimestamps(lines))
    }

    @Test
    fun `parse of all-invalid lines returns empty list`() {
        val lines = listOf("", "abc", "-1", "   ")
        val result = parseGateTimestamps(lines)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `format joins timestamps with newline and no trailing newline`() {
        val formatted = formatGateTimestamps(listOf(10L, 20L, 30L))
        assertEquals("10\n20\n30", formatted)
    }
}
