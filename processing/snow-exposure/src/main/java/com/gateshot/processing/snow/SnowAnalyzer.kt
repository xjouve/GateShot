package com.gateshot.processing.snow

import androidx.camera.core.ImageProxy

data class SceneAnalysis(
    val snowCoveragePercent: Float,   // 0.0 - 1.0
    val averageLuminance: Float,       // 0.0 - 255.0
    val isFlatLight: Boolean,
    val hasShadowTransition: Boolean,
    val recommendedEvBias: Float,      // +0.0 to +2.5
    val timestamp: Long
)

class SnowAnalyzer {

    // Luminance thresholds for snow detection
    // Snow pixels are typically > 180 luminance in Y plane
    private val snowLuminanceThreshold = 180
    // Flat light: very low contrast (std dev of luminance)
    private val flatLightContrastThreshold = 25f
    // Shadow transition: high local contrast variance
    private val shadowTransitionThreshold = 80f

    fun analyze(imageProxy: ImageProxy): SceneAnalysis {
        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        // Sample a grid of pixels (every 8th pixel for performance)
        val sampleStep = 8
        var totalPixels = 0
        var snowPixels = 0
        var luminanceSum = 0L
        var luminanceSqSum = 0L
        val regionLuminances = mutableListOf<Float>()

        // Divide frame into a 4x4 grid for regional analysis
        val regionWidth = width / 4
        val regionHeight = height / 4
        val regionSums = Array(4) { FloatArray(4) }
        val regionCounts = Array(4) { IntArray(4) }

        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val index = y * rowStride + x * pixelStride
                if (index >= yBuffer.limit()) continue

                val luminance = yBuffer.get(index).toInt() and 0xFF
                totalPixels++
                luminanceSum += luminance
                luminanceSqSum += luminance.toLong() * luminance

                if (luminance > snowLuminanceThreshold) {
                    snowPixels++
                }

                // Track per-region luminance
                val rx = (x / regionWidth).coerceAtMost(3)
                val ry = (y / regionHeight).coerceAtMost(3)
                regionSums[ry][rx] += luminance
                regionCounts[ry][rx]++
            }
        }

        if (totalPixels == 0) {
            return SceneAnalysis(0f, 0f, false, false, 0f, System.currentTimeMillis())
        }

        val snowCoverage = snowPixels.toFloat() / totalPixels
        val avgLuminance = luminanceSum.toFloat() / totalPixels

        // Standard deviation of luminance = contrast indicator
        val variance = (luminanceSqSum.toFloat() / totalPixels) - (avgLuminance * avgLuminance)
        val stdDev = if (variance > 0) Math.sqrt(variance.toDouble()).toFloat() else 0f

        val isFlatLight = stdDev < flatLightContrastThreshold && snowCoverage > 0.3f

        // Shadow transition: check if adjacent regions have large luminance differences
        var maxRegionDelta = 0f
        for (ry in 0 until 4) {
            for (rx in 0 until 3) {
                val count1 = regionCounts[ry][rx]
                val count2 = regionCounts[ry][rx + 1]
                if (count1 > 0 && count2 > 0) {
                    val avg1 = regionSums[ry][rx] / count1
                    val avg2 = regionSums[ry][rx + 1] / count2
                    val delta = Math.abs(avg1 - avg2)
                    if (delta > maxRegionDelta) maxRegionDelta = delta
                }
            }
        }
        val hasShadowTransition = maxRegionDelta > shadowTransitionThreshold

        // Calculate recommended EV bias
        val evBias = calculateEvBias(snowCoverage, avgLuminance, isFlatLight)

        return SceneAnalysis(
            snowCoveragePercent = snowCoverage,
            averageLuminance = avgLuminance,
            isFlatLight = isFlatLight,
            hasShadowTransition = hasShadowTransition,
            recommendedEvBias = evBias,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun calculateEvBias(snowCoverage: Float, avgLuminance: Float, isFlatLight: Boolean): Float {
        // Base EV compensation based on snow coverage
        val baseEv = when {
            snowCoverage > 0.7f -> 2.0f    // Mostly snow (open slope)
            snowCoverage > 0.5f -> 1.5f    // Mixed snow/trees
            snowCoverage > 0.3f -> 1.0f    // Some snow
            snowCoverage > 0.1f -> 0.5f    // Little snow (finish area)
            else -> 0.0f                     // No snow detected
        }

        // Adjust for already-bright scenes (avoid blowout)
        val brightnessAdj = if (avgLuminance > 200) -0.5f else 0f

        // Boost slightly for flat light (need more contrast)
        val flatLightAdj = if (isFlatLight) 0.3f else 0f

        return (baseEv + brightnessAdj + flatLightAdj).coerceIn(0f, 2.5f)
    }
}
