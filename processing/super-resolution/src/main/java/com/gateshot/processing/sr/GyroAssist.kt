package com.gateshot.processing.sr

import com.gateshot.platform.sensor.GyroscopeData
import com.gateshot.platform.sensor.SensorPlatform
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
import kotlin.math.sqrt

/**
 * Gyroscope-assisted frame alignment and camera motion analysis.
 *
 * PROBLEM 1: Frame alignment via block-matching SAD (FrameAligner) is CPU-heavy
 * and can fail on low-contrast snow scenes where all blocks look similar. With
 * a 200MP sensor and 0.5µm pixels, even tiny motion blurs the image.
 *
 * SOLUTION: The gyroscope provides angular velocity at high frequency (200+ Hz).
 * By integrating angular velocity between frame timestamps, we get the exact
 * rotation of the camera between frames. At known focal lengths (70mm telephoto
 * or 230mm with teleconverter), rotation maps to pixel shift:
 *
 *   pixel_shift = angular_rotation_rad × focal_length_px
 *
 * This gives FrameAligner a strong initial estimate, reducing search radius
 * from 16px to 2-3px and making alignment 5-8x faster and more robust.
 *
 * PROBLEM 2: Panning mode (following a racer across the frame) should disable
 * horizontal EIS but keep vertical OIS. Currently the user must manually select
 * the PANNING preset — there's no auto-detection.
 *
 * SOLUTION: Continuous horizontal angular velocity above a threshold (>0.5 rad/s
 * sustained for >300ms) indicates intentional panning. We detect this and
 * auto-switch EIS behavior.
 *
 * PROBLEM 3: Tripod/monopod detection. When the phone is mounted, OIS and EIS
 * can fight the mount and introduce wobble.
 *
 * SOLUTION: Very low, constant angular velocity (<0.01 rad/s across all axes for
 * >2 seconds) indicates a mount. We disable stabilization to prevent fighting.
 */
@Singleton
class GyroAssist @Inject constructor(
    private val sensorPlatform: SensorPlatform
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class FrameShiftEstimate(
        val pixelShiftX: Float,
        val pixelShiftY: Float,
        val confidence: Float   // 0-1: how reliable the gyro estimate is
    )

    data class MotionState(
        val isPanning: Boolean,
        val panDirection: PanDirection,
        val panSpeedDegPerSec: Float,
        val isMounted: Boolean,
        val shakeIntensity: Float  // 0-1: how much the camera is shaking
    )

    enum class PanDirection { NONE, LEFT, RIGHT }

    private val _motionState = MutableStateFlow(
        MotionState(false, PanDirection.NONE, 0f, false, 0f)
    )
    val motionState: StateFlow<MotionState> = _motionState.asStateFlow()

    // Gyro sample buffer — stores recent readings for integration
    private data class TimestampedGyro(
        val x: Float,  // rad/s around X (pitch)
        val y: Float,  // rad/s around Y (yaw = horizontal pan)
        val z: Float,  // rad/s around Z (roll)
        val timestampNs: Long
    )
    private val gyroBuffer = ArrayDeque<TimestampedGyro>(MAX_BUFFER_SIZE)

    // Panning detection state
    private var panAccumulatorRad = 0f
    private var panStartTimeMs = 0L
    private var lastGyroTimestampNs = 0L

    // Mount detection state
    private var lowMotionStartMs = 0L

    // Focal length in pixels (computed from sensor specs)
    // Telephoto: 70mm on 1/1.56" sensor (diagonal ~10.4mm) with 200MP (16384×12288)
    // Pixel pitch = 0.5µm, focal length physical ≈ 12.5mm
    // focal_length_px = focal_length_mm / pixel_pitch_mm = 12.5 / 0.0005 = 25000
    private var focalLengthPixels = 25000f

    // With Hasselblad teleconverter: 3.28× magnification
    private var hasTelevonverter = false

    companion object {
        private const val MAX_BUFFER_SIZE = 500  // ~2.5 seconds at 200Hz

        // Panning detection thresholds
        private const val PAN_SPEED_THRESHOLD_RAD = 0.5f     // 0.5 rad/s ≈ 29°/s
        private const val PAN_DURATION_THRESHOLD_MS = 300L

        // Mount detection thresholds
        private const val MOUNT_MOTION_THRESHOLD_RAD = 0.01f  // Very still
        private const val MOUNT_DURATION_THRESHOLD_MS = 2000L
    }

    fun start() {
        scope.launch {
            sensorPlatform.getGyroscopeReadings().collect { gyro ->
                processGyroSample(gyro)
            }
        }
    }

    fun stop() {
        gyroBuffer.clear()
    }

    fun setTelevonverter(attached: Boolean) {
        hasTelevonverter = attached
        focalLengthPixels = if (attached) 25000f * 3.28f else 25000f
    }

    /**
     * Estimate pixel shift between two frames using gyroscope integration.
     *
     * Called by FrameAligner before block-matching. The gyro estimate narrows
     * the search window from ±16px to ±3px, making alignment faster and
     * more reliable on uniform snow textures.
     *
     * @param frameTimestampNs1 Timestamp of reference frame (nanoseconds)
     * @param frameTimestampNs2 Timestamp of target frame (nanoseconds)
     * @param imageWidth Width of the frame in pixels
     * @param imageHeight Height of the frame in pixels
     */
    fun estimateShift(
        frameTimestampNs1: Long,
        frameTimestampNs2: Long,
        imageWidth: Int,
        imageHeight: Int
    ): FrameShiftEstimate {
        // Find gyro samples between the two frame timestamps
        val samples = synchronized(gyroBuffer) {
            gyroBuffer.filter { it.timestampNs in frameTimestampNs1..frameTimestampNs2 }
        }

        if (samples.size < 2) {
            return FrameShiftEstimate(0f, 0f, 0f)
        }

        // Integrate angular velocity to get total rotation
        var totalYawRad = 0f   // Horizontal rotation (→ pixel shift in X)
        var totalPitchRad = 0f // Vertical rotation (→ pixel shift in Y)

        for (i in 1 until samples.size) {
            val dt = (samples[i].timestampNs - samples[i - 1].timestampNs) / 1_000_000_000f
            if (dt <= 0f || dt > 0.1f) continue  // Skip bad intervals

            // Trapezoidal integration for better accuracy
            totalYawRad += (samples[i].y + samples[i - 1].y) / 2f * dt
            totalPitchRad += (samples[i].x + samples[i - 1].x) / 2f * dt
        }

        // Convert rotation to pixel shift
        // At focal length f (pixels), a rotation of θ radians shifts the image by f×tan(θ) ≈ f×θ pixels
        // Scale to actual image resolution (gyro estimate is at native 200MP, image may be downscaled)
        val scaleFactor = imageWidth.toFloat() / 16384f  // 16384 = 200MP native width
        val effectiveFocal = focalLengthPixels * scaleFactor

        val shiftX = totalYawRad * effectiveFocal
        val shiftY = totalPitchRad * effectiveFocal

        // Confidence: based on number of samples and integration time
        val integrationTimeMs = (frameTimestampNs2 - frameTimestampNs1) / 1_000_000f
        val expectedSamples = integrationTimeMs / 5f  // 200Hz gyro = 1 sample per 5ms
        val sampleRatio = (samples.size / expectedSamples).coerceIn(0f, 1f)

        // Lower confidence for large shifts (gyro drift accumulates)
        val shiftMagnitude = sqrt(shiftX * shiftX + shiftY * shiftY)
        val driftConfidence = if (shiftMagnitude > 50f) 0.5f else 1f

        val confidence = sampleRatio * driftConfidence

        return FrameShiftEstimate(shiftX, shiftY, confidence)
    }

    private fun processGyroSample(gyro: GyroscopeData) {
        val now = System.nanoTime()

        val sample = TimestampedGyro(gyro.x, gyro.y, gyro.z, now)
        synchronized(gyroBuffer) {
            gyroBuffer.addLast(sample)
            while (gyroBuffer.size > MAX_BUFFER_SIZE) {
                gyroBuffer.removeFirst()
            }
        }

        // Update motion analysis
        val dt = if (lastGyroTimestampNs > 0) (now - lastGyroTimestampNs) / 1_000_000_000f else 0f
        lastGyroTimestampNs = now

        if (dt <= 0f || dt > 0.1f) return

        val totalMotion = sqrt(gyro.x * gyro.x + gyro.y * gyro.y + gyro.z * gyro.z)
        val horizontalSpeed = abs(gyro.y)  // Yaw = horizontal pan
        val nowMs = System.currentTimeMillis()

        // Panning detection
        val isPanning: Boolean
        val panDirection: PanDirection
        if (horizontalSpeed > PAN_SPEED_THRESHOLD_RAD) {
            panAccumulatorRad += gyro.y * dt
            if (panStartTimeMs == 0L) panStartTimeMs = nowMs

            isPanning = (nowMs - panStartTimeMs) > PAN_DURATION_THRESHOLD_MS
            panDirection = if (gyro.y > 0) PanDirection.RIGHT else PanDirection.LEFT
        } else {
            panAccumulatorRad = 0f
            panStartTimeMs = 0L
            isPanning = false
            panDirection = PanDirection.NONE
        }

        // Mount detection
        val isMounted: Boolean
        if (totalMotion < MOUNT_MOTION_THRESHOLD_RAD) {
            if (lowMotionStartMs == 0L) lowMotionStartMs = nowMs
            isMounted = (nowMs - lowMotionStartMs) > MOUNT_DURATION_THRESHOLD_MS
        } else {
            lowMotionStartMs = 0L
            isMounted = false
        }

        // Shake intensity (normalized)
        // At 230mm, even 0.1 rad/s of shake is significant
        val shakeIntensity = (totalMotion / 2f).coerceIn(0f, 1f)

        _motionState.value = MotionState(
            isPanning = isPanning,
            panDirection = panDirection,
            panSpeedDegPerSec = Math.toDegrees(horizontalSpeed.toDouble()).toFloat(),
            isMounted = isMounted,
            shakeIntensity = shakeIntensity
        )
    }
}
