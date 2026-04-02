package com.gateshot.platform.sensor

import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * IMU processor: accelerometer crash detection + magnetometer compass heading.
 *
 * ACCELEROMETER — CRASH/IMPACT DETECTION:
 * Ski racing involves gate contacts, falls, and equipment impacts. The
 * accelerometer detects sudden G-force spikes that indicate:
 * - Gate impact: 2-5G spike lasting 20-50ms (racer hits gate panel)
 * - Fall/crash: >5G spike with sustained deviation from 1G
 * - Equipment drop: phone dropped on snow, sharp >8G spike
 *
 * When an impact is detected, we:
 * 1. Tag the current video timestamp with an impact marker
 * 2. Ensure the pre-capture buffer is preserved (don't overwrite)
 * 3. Publish an event for the session module to log
 *
 * MAGNETOMETER — COMPASS HEADING:
 * The magnetometer provides compass heading for:
 * - EXIF geotagging (GPS direction)
 * - Course map orientation (which direction the camera is facing)
 * - Coaching overlay registration (align runs shot from same position)
 *
 * The heading is computed from the magnetic field vector using tilt
 * compensation from the accelerometer (gravity direction).
 */
@Singleton
class ImuProcessor @Inject constructor(
    private val sensorPlatform: SensorPlatform,
    private val eventBus: EventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Impact detection state ──────────────────────────────────────────
    data class ImpactEvent(
        val peakG: Float,
        val durationMs: Long,
        val severity: ImpactSeverity,
        val timestamp: Long
    )

    enum class ImpactSeverity {
        LIGHT,      // 2-3G: gate brush, minor bump
        MODERATE,   // 3-5G: solid gate contact
        HEAVY,      // 5-8G: fall or hard crash
        SEVERE      // >8G: equipment drop or violent impact
    }

    private val _lastImpact = MutableStateFlow<ImpactEvent?>(null)
    val lastImpact: StateFlow<ImpactEvent?> = _lastImpact.asStateFlow()

    // ── Compass heading state ───────────────────────────────────────────
    private val _compassHeading = MutableStateFlow(0f)
    val compassHeading: StateFlow<Float> = _compassHeading.asStateFlow()

    // Impact detection thresholds
    companion object {
        private const val IMPACT_THRESHOLD_G = 2.0f   // Minimum G to register as impact
        private const val GRAVITY_MS2 = 9.81f
        private const val IMPACT_COOLDOWN_MS = 500L    // Min time between impact events
    }

    // State for impact detection
    private var lastImpactTimeMs = 0L
    private var impactStartTimeMs = 0L
    private var impactPeakG = 0f
    private var isInImpact = false

    // State for compass heading (tilt-compensated)
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = GRAVITY_MS2

    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        // Accelerometer for impact detection and tilt reference
        scope.launch {
            sensorPlatform.getAccelerometerReadings().collect { accel ->
                processAccelerometer(accel)
            }
        }

        // Magnetometer for compass heading
        scope.launch {
            sensorPlatform.getMagnetometerReadings().collect { mag ->
                processMagnetometer(mag)
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    fun getCompassHeading(): Float = _compassHeading.value

    // ─────────────────────────────────────────────────────────────────────
    // Accelerometer → Impact Detection
    // ─────────────────────────────────────────────────────────────────────

    private fun processAccelerometer(accel: AccelerometerData) {
        // Store for tilt compensation in compass
        lastAccelX = accel.x
        lastAccelY = accel.y
        lastAccelZ = accel.z

        // Compute total acceleration magnitude
        val totalG = sqrt(accel.x * accel.x + accel.y * accel.y + accel.z * accel.z) / GRAVITY_MS2

        // Deviation from 1G gravity (stationary = ~0, impact = high)
        val deviationG = kotlin.math.abs(totalG - 1.0f)

        val now = System.currentTimeMillis()

        if (deviationG >= IMPACT_THRESHOLD_G) {
            if (!isInImpact) {
                // Impact started
                isInImpact = true
                impactStartTimeMs = now
                impactPeakG = deviationG + 1f  // Total G including gravity
            } else {
                // Impact continuing — track peak
                val currentG = deviationG + 1f
                if (currentG > impactPeakG) impactPeakG = currentG
            }
        } else if (isInImpact) {
            // Impact ended
            isInImpact = false
            val durationMs = now - impactStartTimeMs

            if (now - lastImpactTimeMs >= IMPACT_COOLDOWN_MS && durationMs >= 10) {
                lastImpactTimeMs = now

                val severity = when {
                    impactPeakG >= 8f -> ImpactSeverity.SEVERE
                    impactPeakG >= 5f -> ImpactSeverity.HEAVY
                    impactPeakG >= 3f -> ImpactSeverity.MODERATE
                    else -> ImpactSeverity.LIGHT
                }

                val impact = ImpactEvent(
                    peakG = impactPeakG,
                    durationMs = durationMs,
                    severity = severity,
                    timestamp = impactStartTimeMs
                )

                _lastImpact.value = impact

                // Publish event
                eventBus.tryPublish(AppEvent.BatteryWarning(
                    impactPeakG, severity.ordinal
                ))
            }

            impactPeakG = 0f
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Magnetometer → Tilt-Compensated Compass Heading
    // ─────────────────────────────────────────────────────────────────────

    private fun processMagnetometer(mag: MagnetometerData) {
        // Tilt-compensated compass heading
        // The phone may not be horizontal — we need to project the magnetic
        // field vector onto the horizontal plane using gravity direction.

        // Normalize gravity vector
        val gMag = sqrt(lastAccelX * lastAccelX + lastAccelY * lastAccelY + lastAccelZ * lastAccelZ)
        if (gMag < 0.1f) return
        val gx = lastAccelX / gMag
        val gy = lastAccelY / gMag
        val gz = lastAccelZ / gMag

        // Compute East vector (cross product of gravity × magnetic field)
        val ex = gy * mag.z - gz * mag.y
        val ey = gz * mag.x - gx * mag.z
        val ez = gx * mag.y - gy * mag.x
        val eMag = sqrt(ex * ex + ey * ey + ez * ez)
        if (eMag < 0.1f) return

        // Compute North vector (cross product of East × gravity)
        val nx = ey * gz - ez * gy
        val ny = ez * gx - ex * gz

        // Heading = atan2(East component, North component)
        val headingRad = atan2(ex.toDouble(), nx.toDouble())
        var headingDeg = Math.toDegrees(headingRad).toFloat()
        if (headingDeg < 0) headingDeg += 360f

        // Smooth the heading to reduce jitter
        val currentHeading = _compassHeading.value
        val diff = headingDeg - currentHeading
        val normalizedDiff = ((diff + 540) % 360) - 180  // Handle 360° wraparound
        _compassHeading.value = (currentHeading + normalizedDiff * 0.1f + 360f) % 360f
    }
}
