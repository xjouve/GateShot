package com.gateshot.capture.burst

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameRingBufferTest {

    private fun makeFrame(ts: Long) = BufferedFrame(
        timestamp = ts,
        width = 1920,
        height = 1080,
        data = ByteArray(100),
        format = 0
    )

    @Test
    fun `empty buffer returns empty list`() {
        val buffer = FrameRingBuffer(10)
        assertEquals(0, buffer.size)
        assertTrue(buffer.flush().isEmpty())
    }

    @Test
    fun `push increases size`() {
        val buffer = FrameRingBuffer(10)
        buffer.push(makeFrame(1))
        buffer.push(makeFrame(2))
        assertEquals(2, buffer.size)
    }

    @Test
    fun `flush returns frames in chronological order`() {
        val buffer = FrameRingBuffer(10)
        buffer.push(makeFrame(3))
        buffer.push(makeFrame(1))
        buffer.push(makeFrame(2))

        val flushed = buffer.flush()
        assertEquals(3, flushed.size)
        assertEquals(1L, flushed[0].timestamp)
        assertEquals(2L, flushed[1].timestamp)
        assertEquals(3L, flushed[2].timestamp)
    }

    @Test
    fun `buffer wraps at capacity`() {
        val buffer = FrameRingBuffer(3)
        buffer.push(makeFrame(1))
        buffer.push(makeFrame(2))
        buffer.push(makeFrame(3))
        buffer.push(makeFrame(4))  // Overwrites frame 1

        assertEquals(3, buffer.size)
        val flushed = buffer.flush()
        assertEquals(3, flushed.size)
        assertEquals(2L, flushed[0].timestamp)
        assertEquals(3L, flushed[1].timestamp)
        assertEquals(4L, flushed[2].timestamp)
    }

    @Test
    fun `clear resets buffer`() {
        val buffer = FrameRingBuffer(10)
        buffer.push(makeFrame(1))
        buffer.push(makeFrame(2))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertTrue(buffer.flush().isEmpty())
    }

    @Test
    fun `getRecentFrames returns last N frames`() {
        val buffer = FrameRingBuffer(10)
        for (i in 1L..5L) buffer.push(makeFrame(i))

        val recent = buffer.getRecentFrames(3)
        assertEquals(3, recent.size)
        assertEquals(3L, recent[0].timestamp)
        assertEquals(4L, recent[1].timestamp)
        assertEquals(5L, recent[2].timestamp)
    }

    @Test
    fun `getRecentFrames with count larger than size returns all`() {
        val buffer = FrameRingBuffer(10)
        buffer.push(makeFrame(1))
        buffer.push(makeFrame(2))

        val recent = buffer.getRecentFrames(5)
        assertEquals(2, recent.size)
    }
}
