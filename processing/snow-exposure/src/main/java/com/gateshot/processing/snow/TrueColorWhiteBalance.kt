package com.gateshot.processing.snow

import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.platform.camera.WhiteBalanceGains
import com.gateshot.platform.sensor.TrueColorSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Spectral white balance engine for snow scenes.
 *
 * Uses the Oppo Find X9 Pro's True Color sensor (9 spectral channels, 48 zones)
 * to compute accurate white balance in the challenging lighting conditions of
 * ski racing:
 *
 * PROBLEM:
 * Snow is a near-perfect reflector — its color IS the color of the light.
 * A camera's auto WB sees "white" and assumes the light is neutral, but:
 *   - Snow in sun: warm (5500-6500K) — looks correct
 *   - Snow in shadow: blue (8000-12000K) — AWB under-corrects, blue snow
 *   - Flat overcast: very neutral but low contrast — AWB hunts
 *   - Mixed sun/shadow: WB jumps as racer moves between zones
 *   - Trees/buildings reflect colored light onto snow — local tint
 *
 * SOLUTION:
 * The True Color sensor measures the actual spectral content of the ambient
 * light independently of the image. We use it to:
 * 1. Set a base CCT from the measured illuminant (not from the image)
 * 2. Correct tint to remove green/magenta casts
 * 3. Detect shadow zones (high CCT, blue-shifted) and compensate
 * 4. Smooth transitions to prevent WB flicker as light changes
 *
 * This produces white balance that tracks the illuminant, not the subject,
 * so snow renders as neutral white regardless of scene content.
 */
@Singleton
class TrueColorWhiteBalance @Inject constructor(
    private val trueColorSensor: TrueColorSensor,
    private val cameraPlatform: CameraXPlatform
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class WhiteBalanceResult(
        val redGain: Float,       // Multiplier for R channel (1.0 = no adjustment)
        val greenGain: Float,     // Multiplier for G channel (always ~1.0)
        val blueGain: Float,      // Multiplier for B channel
        val cctKelvin: Int,       // Measured color temperature
        val tint: Float,          // Green/magenta tint
        val confidence: Float,    // 0-1: how confident we are in the reading
        val lightingCondition: LightingCondition,
        val timestamp: Long
    )

    enum class LightingCondition {
        DIRECT_SUN,        // 5000-6500K, high illuminance
        OPEN_SHADE,        // 7000-10000K, moderate illuminance
        OVERCAST,          // 6000-7500K, low contrast
        MIXED_SUN_SHADE,   // Varies, detected by zone analysis
        ARTIFICIAL,        // < 5000K, often with tint
        GOLDEN_HOUR        // 3000-4500K, warm
    }

    private val _currentWb = MutableStateFlow<WhiteBalanceResult?>(null)
    val currentWhiteBalance: StateFlow<WhiteBalanceResult?> = _currentWb.asStateFlow()

    // Smoothing: exponential moving average to prevent WB flicker
    private var smoothedCct = 6500f
    private var smoothedTint = 0f
    private val smoothingFactor = 0.15f  // Low = smooth, high = responsive

    // Zonal analysis state
    private var lastZonalCcts: FloatArray? = null

    fun start() {
        scope.launch {
            trueColorSensor.getSpectralReadings().collect { reading ->
                processReading(reading)
            }
        }
    }

    fun stop() {
        // Scope cancellation handles cleanup
    }

    private fun processReading(reading: TrueColorSensor.SpectralReading) {
        val rawCct = reading.correlatedColorTemp.toFloat()
        val rawTint = reading.tint

        // Smooth the CCT to prevent WB flicker
        smoothedCct = smoothedCct + (rawCct - smoothedCct) * smoothingFactor
        smoothedTint = smoothedTint + (rawTint - smoothedTint) * smoothingFactor

        val cct = smoothedCct.toInt()

        // Classify lighting condition
        val condition = classifyLighting(reading)

        // Convert CCT + tint to RGB gains
        val (rGain, gGain, bGain) = cctToRgbGains(cct, smoothedTint, condition)

        // Confidence based on reading stability and illuminance
        val confidence = calculateConfidence(reading)

        val wbResult = WhiteBalanceResult(
            redGain = rGain,
            greenGain = gGain,
            blueGain = bGain,
            cctKelvin = cct,
            tint = smoothedTint,
            confidence = confidence,
            lightingCondition = condition,
            timestamp = reading.timestamp
        )
        _currentWb.value = wbResult

        // Push WB gains directly into Camera2 capture stream for real-time correction.
        // This makes the live preview and captured frames correctly white-balanced
        // without needing post-processing.
        if (confidence > 0.4f) {
            cameraPlatform.setWhiteBalanceGains(WhiteBalanceGains(
                redGain = rGain,
                greenEvenGain = gGain,
                greenOddGain = gGain,
                blueGain = bGain
            ))
        }
    }

    private fun classifyLighting(reading: TrueColorSensor.SpectralReading): LightingCondition {
        val cct = reading.correlatedColorTemp
        val lux = reading.illuminanceLux

        // Zone analysis: if some zones are warm and others cool, it's mixed
        val zones = reading.zones
        if (zones != null) {
            val zoneCcts = zones.flatMap { row -> row.map { estimateZoneCct(it) } }
            val cctRange = zoneCcts.maxOrNull()?.minus(zoneCcts.minOrNull() ?: 0f) ?: 0f
            if (cctRange > 2000f) return LightingCondition.MIXED_SUN_SHADE
        }

        return when {
            cct < 3500 -> LightingCondition.GOLDEN_HOUR
            cct < 5000 && abs(reading.tint) > 0.2f -> LightingCondition.ARTIFICIAL
            cct in 5000..6500 && lux > 30000 -> LightingCondition.DIRECT_SUN
            cct in 6000..7500 && lux < 15000 -> LightingCondition.OVERCAST
            cct > 7000 -> LightingCondition.OPEN_SHADE
            else -> LightingCondition.DIRECT_SUN
        }
    }

    /**
     * Convert correlated color temperature + tint to RGB white balance gains.
     *
     * Based on the CIE daylight model with corrections for snow scenes.
     * Snow in shadow needs stronger blue attenuation than a generic WB algorithm
     * provides, because the eye adapts to shadow more than the camera does.
     */
    private fun cctToRgbGains(
        cct: Int,
        tint: Float,
        condition: LightingCondition
    ): Triple<Float, Float, Float> {
        // Base conversion using modified Planckian approximation
        // Normalized so that D65 (6500K) ≈ (1.0, 1.0, 1.0)
        val temp = cct.toFloat()

        var rGain: Float
        var gGain = 1.0f
        var bGain: Float

        if (temp <= 6500f) {
            // Warm light: boost blue, reduce red
            val t = (temp - 2000f) / 4500f  // 0-1 range for 2000-6500K
            rGain = 1.0f + (1f - t) * 0.4f   // 1.4 at 2000K → 1.0 at 6500K
            bGain = 1.0f - (1f - t) * 0.3f   // 0.7 at 2000K → 1.0 at 6500K
        } else {
            // Cool light (shade/overcast): boost red, reduce blue
            // to warm up the blue-shifted scene
            val t = ((temp - 6500f) / 5500f).coerceAtMost(1f)  // 0-1 for 6500-12000K
            rGain = 1.0f + t * 0.15f   // 1.0 → 1.15 at 12000K
            bGain = 1.0f - t * 0.3f    // 1.0 → 0.7 at 12000K
        }

        // Snow-specific shadow correction: in open shade, snow appears
        // very blue because the only light source is blue sky. The WB
        // correction above handles the CCT shift, but snow reflects
        // selectively in the blue, so we need extra blue attenuation.
        if (condition == LightingCondition.OPEN_SHADE) {
            bGain *= 0.92f  // Additional 8% blue reduction for shadow snow
            rGain *= 1.05f  // Slight warm shift to counteract blue cast
        }

        // Tint correction: shift green/magenta axis
        // Positive tint (green) → reduce green gain
        // Negative tint (magenta) → boost green gain
        gGain -= tint * 0.15f

        // Normalize so green channel is at 1.0 (reference).
        // RggbChannelVector gains are multipliers relative to green;
        // normalizing to max instead of green causes a green cast
        // when gGain is the dominant channel.
        rGain /= gGain
        bGain /= gGain
        gGain = 1.0f

        return Triple(rGain, gGain, bGain)
    }

    private fun estimateZoneCct(irradiance: Float): Float {
        // Simplified zone CCT — in practice each zone would have its own
        // spectral channels, but the True Color sensor provides per-zone
        // data that correlates with local CCT.
        return 5500f + irradiance * 10f  // Rough approximation
    }

    private fun calculateConfidence(reading: TrueColorSensor.SpectralReading): Float {
        // Higher illuminance → more reliable spectral reading
        val luxConfidence = (reading.illuminanceLux / 50000f).coerceIn(0.3f, 1f)

        // Spectral channels with non-zero values → sensor is providing data
        val activeChannels = reading.channels.count { it > 0.1f }
        val channelConfidence = (activeChannels.toFloat() / 9f).coerceIn(0.1f, 1f)

        return (luxConfidence * 0.6f + channelConfidence * 0.4f)
    }
}
