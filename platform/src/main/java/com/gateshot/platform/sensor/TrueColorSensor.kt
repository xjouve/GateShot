package com.gateshot.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface to the Oppo Find X9 Pro True Color sensor.
 *
 * The sensor has 9 spectral channels across 48 zones (6 rows × 8 columns).
 * It measures the spectral power distribution of ambient light, enabling
 * accurate white balance even in mixed lighting (e.g. snow in sun vs. shadow,
 * artificial vs. natural light, flat overcast conditions).
 *
 * OPPO exposes this via a vendor-specific light sensor type that returns
 * multi-channel spectral data instead of a single lux value. We use the
 * standard TYPE_LIGHT sensor as a fallback, but prefer the vendor-specific
 * sensor when available (registered with a "true color" or "spectral" name
 * in the sensor list).
 *
 * The spectral channels approximate:
 *   Ch0: 380-420nm (deep violet)
 *   Ch1: 420-460nm (blue)
 *   Ch2: 460-500nm (cyan)
 *   Ch3: 500-540nm (green)
 *   Ch4: 540-580nm (yellow-green)
 *   Ch5: 580-620nm (orange)
 *   Ch6: 620-660nm (red)
 *   Ch7: 660-720nm (deep red)
 *   Ch8: 720-780nm (near-IR, useful for foliage / snow distinction)
 */
@Singleton
class TrueColorSensor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    data class SpectralReading(
        val channels: FloatArray,          // 9 spectral channel values (raw irradiance)
        val zones: Array<FloatArray>?,     // 6×8 zone map (null if sensor doesn't provide zonal data)
        val correlatedColorTemp: Int,      // Estimated CCT in Kelvin
        val tint: Float,                   // Green/magenta tint shift (-1.0 to +1.0)
        val illuminanceLux: Float,         // Total illuminance in lux
        val timestamp: Long
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = channels.contentHashCode()
    }

    /**
     * Find the True Color spectral sensor.
     *
     * OPPO registers it as a vendor-specific sensor type (>= 0x10000) or
     * under the standard TYPE_LIGHT with a name containing "spectral" or
     * "true color". We search the sensor list for the best match.
     */
    private fun findSpectralSensor(): Sensor? {
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)

        // Prefer vendor-specific spectral sensor
        val vendorSpectral = allSensors.firstOrNull { sensor ->
            val name = sensor.name.lowercase()
            sensor.type >= 0x10000 && (
                name.contains("spectral") ||
                name.contains("true color") ||
                name.contains("truecolor") ||
                name.contains("color spectrum") ||
                name.contains("cct")
            )
        }
        if (vendorSpectral != null) return vendorSpectral

        // Fallback: OPPO may also expose a multi-channel light sensor
        val multiChannelLight = allSensors.firstOrNull { sensor ->
            val name = sensor.name.lowercase()
            sensor.type == Sensor.TYPE_LIGHT && (
                name.contains("spectral") ||
                name.contains("true color") ||
                name.contains("multi")
            )
        }
        if (multiChannelLight != null) return multiChannelLight

        // Last resort: standard light sensor (single lux value only)
        return sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    fun isAvailable(): Boolean = findSpectralSensor() != null

    /**
     * Stream spectral readings from the True Color sensor.
     * Updates at approximately 10 Hz (adequate for white balance tracking).
     */
    fun getSpectralReadings(): Flow<SpectralReading> = callbackFlow {
        val sensor = findSpectralSensor()
        if (sensor == null) {
            close()
            return@callbackFlow
        }

        val isVendorSpectral = sensor.type >= 0x10000 ||
            sensor.name.lowercase().let { it.contains("spectral") || it.contains("true color") }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val reading = if (isVendorSpectral && event.values.size >= 9) {
                    parseSpectralEvent(event)
                } else {
                    parseLuxFallback(event)
                }
                trySend(reading)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            listener, sensor,
            SensorManager.SENSOR_DELAY_NORMAL  // ~100ms interval
        )
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /**
     * Parse a full spectral event from the vendor True Color sensor.
     * The sensor reports 9 channel values, and optionally 48 zone values.
     */
    private fun parseSpectralEvent(event: SensorEvent): SpectralReading {
        val channels = FloatArray(9) { i ->
            if (i < event.values.size) event.values[i] else 0f
        }

        // If the sensor provides zonal data (9 channels + 48 zones = 57 values)
        val zones = if (event.values.size >= 57) {
            Array(6) { row ->
                FloatArray(8) { col ->
                    event.values[9 + row * 8 + col]
                }
            }
        } else null

        val cct = estimateCCT(channels)
        val tint = estimateTint(channels)
        val lux = channels.sum()  // Approximate total illuminance

        return SpectralReading(
            channels = channels,
            zones = zones,
            correlatedColorTemp = cct,
            tint = tint,
            illuminanceLux = lux,
            timestamp = event.timestamp
        )
    }

    /**
     * Fallback: convert a single lux reading to a pseudo-spectral reading.
     * Assumes D65 daylight spectrum (reasonable for outdoor snow scenes).
     */
    private fun parseLuxFallback(event: SensorEvent): SpectralReading {
        val lux = if (event.values.isNotEmpty()) event.values[0] else 0f

        // D65 daylight relative spectral power distribution (normalized)
        val d65Weights = floatArrayOf(0.05f, 0.08f, 0.10f, 0.12f, 0.13f, 0.14f, 0.15f, 0.13f, 0.10f)
        val channels = FloatArray(9) { i -> lux * d65Weights[i] }

        return SpectralReading(
            channels = channels,
            zones = null,
            correlatedColorTemp = 6500,  // Assume daylight
            tint = 0f,
            illuminanceLux = lux,
            timestamp = event.timestamp
        )
    }

    /**
     * Estimate correlated color temperature from spectral channels.
     *
     * Uses the blue/red ratio method:
     * - High blue/red ratio → high CCT (cool/blue sky, ~8000-10000K)
     * - Low blue/red ratio → low CCT (warm/sunset, ~3000-4000K)
     * - Equal → ~5500K (midday sun)
     *
     * Snow scenes typically read 6500-9000K due to blue sky fill.
     * Shaded snow reads even higher (10000K+).
     */
    private fun estimateCCT(channels: FloatArray): Int {
        val blue = channels[1] + channels[2]   // 420-500nm
        val green = channels[3] + channels[4]  // 500-580nm
        val red = channels[5] + channels[6]    // 580-660nm

        if (red < 0.001f) return 10000  // Very blue = high CCT

        val blueRedRatio = blue / red

        // Empirical mapping from blue/red ratio to CCT
        // Calibrated against standard illuminants:
        //   A (2856K): ratio ~0.3
        //   D50 (5003K): ratio ~0.85
        //   D65 (6504K): ratio ~1.1
        //   D95 (9503K): ratio ~1.8
        val cct = when {
            blueRedRatio > 2.0f -> 12000
            blueRedRatio > 1.5f -> (8000 + (blueRedRatio - 1.5f) * 8000).toInt()
            blueRedRatio > 1.0f -> (6000 + (blueRedRatio - 1.0f) * 4000).toInt()
            blueRedRatio > 0.6f -> (4000 + (blueRedRatio - 0.6f) * 5000).toInt()
            blueRedRatio > 0.3f -> (2800 + (blueRedRatio - 0.3f) * 4000).toInt()
            else -> 2800
        }

        return cct.coerceIn(2000, 15000)
    }

    /**
     * Estimate green/magenta tint from spectral channels.
     *
     * Tint measures how much the illuminant deviates from the Planckian locus.
     * Positive tint = greenish (fluorescent lights, foliage reflection).
     * Negative tint = magenta (some LED lights, high-altitude snow).
     *
     * For ski racing: shaded snow often has negative tint (magenta cast from
     * blue sky minus direct sun). Detecting this prevents the "blue/purple
     * snow in shadow" problem.
     */
    private fun estimateTint(channels: FloatArray): Float {
        val blue = channels[1] + channels[2]
        val green = channels[3] + channels[4]
        val red = channels[5] + channels[6]

        val expectedGreen = (blue + red) / 2f
        if (expectedGreen < 0.001f) return 0f

        val greenDeviation = (green - expectedGreen) / expectedGreen
        return greenDeviation.coerceIn(-1f, 1f)
    }
}
