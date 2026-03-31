package com.gateshot.capture.burst

import java.nio.ByteBuffer

data class BufferedFrame(
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val format: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BufferedFrame) return false
        return timestamp == other.timestamp
    }

    override fun hashCode(): Int = timestamp.hashCode()
}

class FrameRingBuffer(private val maxFrames: Int) {

    private val buffer = arrayOfNulls<BufferedFrame>(maxFrames)
    private var writeIndex = 0
    private var count = 0

    val size: Int get() = count
    val capacity: Int get() = maxFrames

    @Synchronized
    fun push(frame: BufferedFrame) {
        buffer[writeIndex] = frame
        writeIndex = (writeIndex + 1) % maxFrames
        if (count < maxFrames) count++
    }

    @Synchronized
    fun flush(): List<BufferedFrame> {
        if (count == 0) return emptyList()

        val result = mutableListOf<BufferedFrame>()
        val startIndex = if (count < maxFrames) 0 else writeIndex

        for (i in 0 until count) {
            val index = (startIndex + i) % maxFrames
            buffer[index]?.let { result.add(it) }
        }

        // Sort by timestamp to guarantee chronological order
        result.sortBy { it.timestamp }
        return result
    }

    @Synchronized
    fun clear() {
        for (i in buffer.indices) buffer[i] = null
        writeIndex = 0
        count = 0
    }

    @Synchronized
    fun getRecentFrames(count: Int): List<BufferedFrame> {
        val available = minOf(count, this.count)
        if (available == 0) return emptyList()

        val result = mutableListOf<BufferedFrame>()
        for (i in 0 until available) {
            val index = (writeIndex - available + i + maxFrames) % maxFrames
            buffer[index]?.let { result.add(it) }
        }
        result.sortBy { it.timestamp }
        return result
    }

    fun estimateMemoryUsageMb(): Float {
        if (count == 0) return 0f
        val avgFrameSize = buffer.filterNotNull().take(5).map { it.data.size }.average()
        return (count * avgFrameSize / (1024 * 1024)).toFloat()
    }
}
