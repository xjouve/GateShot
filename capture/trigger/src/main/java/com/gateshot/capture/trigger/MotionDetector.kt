package com.gateshot.capture.trigger

import androidx.camera.core.ImageProxy

class MotionDetector {

    private var previousFrame: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    // Motion detection threshold — pixel luminance change that counts as motion
    private val motionThreshold = 30
    // Minimum percentage of zone pixels that must change to trigger
    private val motionPercentThreshold = 0.15f

    fun detectMotionInZones(
        imageProxy: ImageProxy,
        zones: List<TriggerZone>
    ): List<ZoneMotionResult> {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        // Extract Y plane data
        val currentFrame = ByteArray(buffer.remaining())
        buffer.get(currentFrame)
        buffer.rewind()

        val prev = previousFrame
        val results = mutableListOf<ZoneMotionResult>()

        if (prev != null && previousWidth == width && previousHeight == height) {
            for (zone in zones) {
                if (!zone.isArmed) {
                    results.add(ZoneMotionResult(zone.id, 0f, false))
                    continue
                }

                val motionScore = computeZoneMotion(
                    prev, currentFrame, width, height,
                    rowStride, pixelStride, zone
                )
                val hasMotion = motionScore >= motionPercentThreshold

                results.add(ZoneMotionResult(zone.id, motionScore, hasMotion))
            }
        } else {
            // First frame — no comparison possible
            zones.forEach { results.add(ZoneMotionResult(it.id, 0f, false)) }
        }

        previousFrame = currentFrame
        previousWidth = width
        previousHeight = height

        return results
    }

    private fun computeZoneMotion(
        prev: ByteArray,
        curr: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zone: TriggerZone
    ): Float {
        // Convert zone normalized coords to pixel coords
        val zoneLeft = ((zone.centerX - zone.radiusX) * width).toInt().coerceIn(0, width - 1)
        val zoneRight = ((zone.centerX + zone.radiusX) * width).toInt().coerceIn(0, width - 1)
        val zoneTop = ((zone.centerY - zone.radiusY) * height).toInt().coerceIn(0, height - 1)
        val zoneBottom = ((zone.centerY + zone.radiusY) * height).toInt().coerceIn(0, height - 1)

        var changedPixels = 0
        var totalPixels = 0
        val sampleStep = 4  // Sample every 4th pixel for performance

        for (y in zoneTop until zoneBottom step sampleStep) {
            for (x in zoneLeft until zoneRight step sampleStep) {
                val index = y * rowStride + x * pixelStride
                if (index >= prev.size || index >= curr.size) continue

                val prevLum = prev[index].toInt() and 0xFF
                val currLum = curr[index].toInt() and 0xFF
                val diff = Math.abs(currLum - prevLum)

                totalPixels++
                if (diff > motionThreshold) {
                    changedPixels++
                }
            }
        }

        return if (totalPixels > 0) changedPixels.toFloat() / totalPixels else 0f
    }

    fun reset() {
        previousFrame = null
    }
}

data class ZoneMotionResult(
    val zoneId: Int,
    val motionScore: Float,    // 0.0 - 1.0: percentage of pixels with motion
    val hasMotion: Boolean
)
