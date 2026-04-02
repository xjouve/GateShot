package com.gateshot.processing.sr

import com.gateshot.platform.sensor.GyroscopeData
import com.gateshot.platform.sensor.SensorPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gyro data logger + post-capture video stabilization.
 *
 * LOGGING:
 * During video recording, records timestamped gyroscope samples to a CSV file
 * alongside the video. The gyro runs at ~200Hz, producing a dense rotation
 * trace that maps each video frame to the camera's angular state.
 *
 * Format: timestamp_ns, gyro_x (rad/s), gyro_y (rad/s), gyro_z (rad/s)
 *
 * POST-CAPTURE STABILIZATION:
 * For coaching replay at 230mm telephoto, even small shake is visible.
 * The logged gyro data enables software stabilization after recording:
 *
 * 1. For each video frame, integrate gyro samples over the frame duration
 *    to get the inter-frame rotation (pitch, yaw, roll)
 * 2. Compute the cumulative rotation path
 * 3. Apply a low-pass filter to get the "intended" smooth path
 * 4. For each frame, compute the correction = smooth_path - actual_path
 * 5. Apply a homography warp (rotation around the optical center) to each frame
 *
 * This is similar to how GoPro HyperSmooth and Google Video Stabilization work,
 * but optimized for telephoto focal lengths where small rotations cause large
 * pixel displacements.
 */
@Singleton
class GyroStabilizer @Inject constructor(
    private val sensorPlatform: SensorPlatform
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Logging state
    private var isLogging = false
    private var logWriter: PrintWriter? = null
    private var logFile: File? = null
    private var recordingStartNs = 0L

    data class GyroSample(
        val timestampNs: Long,
        val x: Float,  // pitch (rad/s)
        val y: Float,  // yaw (rad/s)
        val z: Float   // roll (rad/s)
    )

    data class FrameRotation(
        val frameIndex: Int,
        val timestampMs: Long,
        val pitchRad: Float,   // Rotation around X axis
        val yawRad: Float,     // Rotation around Y axis (horizontal pan)
        val rollRad: Float     // Rotation around Z axis
    )

    data class StabilizationTransform(
        val frameIndex: Int,
        val correctionPitchRad: Float,
        val correctionYawRad: Float,
        val correctionRollRad: Float,
        val cropFactor: Float   // How much to crop to hide correction margins
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Gyro Logging During Recording
    // ─────────────────────────────────────────────────────────────────────────

    fun startLogging(outputDir: File) {
        if (isLogging) return

        logFile = File(outputDir, "gyro_${System.currentTimeMillis()}.csv")
        logWriter = PrintWriter(FileOutputStream(logFile!!, false))
        logWriter?.println("timestamp_ns,gyro_x,gyro_y,gyro_z")
        recordingStartNs = System.nanoTime()
        isLogging = true

        scope.launch {
            sensorPlatform.getGyroscopeReadings().collect { gyro ->
                if (isLogging) {
                    val timestamp = System.nanoTime() - recordingStartNs
                    logWriter?.println("$timestamp,${gyro.x},${gyro.y},${gyro.z}")
                }
            }
        }
    }

    fun stopLogging(): File? {
        isLogging = false
        logWriter?.flush()
        logWriter?.close()
        logWriter = null
        return logFile
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Post-Capture Stabilization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load gyro samples from a CSV log file.
     */
    fun loadGyroLog(logFile: File): List<GyroSample> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size == 4) {
                GyroSample(
                    timestampNs = parts[0].trim().toLongOrNull() ?: return@mapNotNull null,
                    x = parts[1].trim().toFloatOrNull() ?: return@mapNotNull null,
                    y = parts[2].trim().toFloatOrNull() ?: return@mapNotNull null,
                    z = parts[3].trim().toFloatOrNull() ?: return@mapNotNull null
                )
            } else null
        }
    }

    /**
     * Compute per-frame rotation from gyro samples.
     *
     * @param samples Gyro samples from the recording
     * @param frameRate Video frame rate (e.g., 30, 60, 120)
     * @param durationMs Total video duration in milliseconds
     */
    fun computeFrameRotations(
        samples: List<GyroSample>,
        frameRate: Int,
        durationMs: Long
    ): List<FrameRotation> {
        if (samples.isEmpty()) return emptyList()

        val frameDurationNs = 1_000_000_000L / frameRate
        val totalFrames = (durationMs * frameRate / 1000).toInt()
        val rotations = mutableListOf<FrameRotation>()

        var sampleIdx = 0
        var cumulativePitch = 0f
        var cumulativeYaw = 0f
        var cumulativeRoll = 0f

        for (frame in 0 until totalFrames) {
            val frameStartNs = frame.toLong() * frameDurationNs
            val frameEndNs = frameStartNs + frameDurationNs

            // Integrate gyro samples within this frame's time window
            var framePitch = 0f
            var frameYaw = 0f
            var frameRoll = 0f

            while (sampleIdx < samples.size - 1 &&
                   samples[sampleIdx].timestampNs < frameEndNs) {
                val s0 = samples[sampleIdx]
                val s1 = samples[sampleIdx + 1]
                val dt = (s1.timestampNs - s0.timestampNs) / 1_000_000_000f

                if (dt > 0 && dt < 0.1f && s0.timestampNs >= frameStartNs) {
                    // Trapezoidal integration
                    framePitch += (s0.x + s1.x) / 2f * dt
                    frameYaw += (s0.y + s1.y) / 2f * dt
                    frameRoll += (s0.z + s1.z) / 2f * dt
                }
                sampleIdx++
            }

            cumulativePitch += framePitch
            cumulativeYaw += frameYaw
            cumulativeRoll += frameRoll

            rotations.add(FrameRotation(
                frameIndex = frame,
                timestampMs = frame * 1000L / frameRate,
                pitchRad = cumulativePitch,
                yawRad = cumulativeYaw,
                rollRad = cumulativeRoll
            ))
        }

        return rotations
    }

    /**
     * Compute stabilization transforms by smoothing the rotation path.
     *
     * Uses a Gaussian low-pass filter on the cumulative rotation path.
     * The correction for each frame is the difference between the smooth
     * path and the actual path.
     *
     * @param rotations Per-frame rotations from computeFrameRotations()
     * @param smoothingWindowFrames Number of frames in the smoothing window
     *        (larger = smoother but more aggressive crop)
     * @param focalLengthPx Focal length in pixels (for crop factor estimation)
     */
    fun computeStabilizationTransforms(
        rotations: List<FrameRotation>,
        smoothingWindowFrames: Int = 30,
        focalLengthPx: Float = 25000f
    ): List<StabilizationTransform> {
        if (rotations.isEmpty()) return emptyList()

        // Smooth the rotation path using a Gaussian kernel
        val smoothPitch = gaussianSmooth(
            rotations.map { it.pitchRad }.toFloatArray(), smoothingWindowFrames)
        val smoothYaw = gaussianSmooth(
            rotations.map { it.yawRad }.toFloatArray(), smoothingWindowFrames)
        val smoothRoll = gaussianSmooth(
            rotations.map { it.rollRad }.toFloatArray(), smoothingWindowFrames)

        return rotations.mapIndexed { i, rotation ->
            val corrPitch = smoothPitch[i] - rotation.pitchRad
            val corrYaw = smoothYaw[i] - rotation.yawRad
            val corrRoll = smoothRoll[i] - rotation.rollRad

            // Estimate crop factor needed to hide the correction margins
            // At the given focal length, a rotation of θ rad shifts by f×θ pixels
            val maxShiftPx = focalLengthPx * maxOf(
                kotlin.math.abs(corrPitch),
                kotlin.math.abs(corrYaw)
            )
            // Crop factor: 1.0 = no crop, >1.0 = zoom in to hide margins
            val cropFactor = 1f + (maxShiftPx / 1920f).coerceAtMost(0.15f)

            StabilizationTransform(
                frameIndex = i,
                correctionPitchRad = corrPitch,
                correctionYawRad = corrYaw,
                correctionRollRad = corrRoll,
                cropFactor = cropFactor
            )
        }
    }

    /**
     * Apply a stabilization transform to a frame's pixel data.
     *
     * Performs a 2D rotation (yaw, pitch) + crop around the image center,
     * simulating a rotation of the camera's optical axis. Roll correction
     * is applied as image rotation.
     */
    fun applyTransform(
        pixels: IntArray,
        width: Int,
        height: Int,
        transform: StabilizationTransform,
        focalLengthPx: Float = 25000f
    ): IntArray {
        val output = IntArray(width * height)

        // Convert rotation to pixel shifts
        val shiftX = transform.correctionYawRad * focalLengthPx * (width.toFloat() / 16384f)
        val shiftY = transform.correctionPitchRad * focalLengthPx * (height.toFloat() / 12288f)
        val rollAngle = transform.correctionRollRad

        val cx = width / 2f
        val cy = height / 2f
        val cosR = cos(rollAngle.toDouble()).toFloat()
        val sinR = sin(rollAngle.toDouble()).toFloat()
        val crop = transform.cropFactor

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Apply inverse transform to find source pixel
                // 1. Undo crop (scale from center)
                val sx = (x - cx) * crop + cx
                val sy = (y - cy) * crop + cy

                // 2. Undo rotation (inverse roll)
                val dx = sx - cx
                val dy = sy - cy
                val rx = dx * cosR + dy * sinR + cx
                val ry = -dx * sinR + dy * cosR + cy

                // 3. Undo translation (shift)
                val finalX = (rx - shiftX).toInt()
                val finalY = (ry - shiftY).toInt()

                if (finalX in 0 until width && finalY in 0 until height) {
                    output[y * width + x] = pixels[finalY * width + finalX]
                } else {
                    output[y * width + x] = 0  // Black border (will be cropped)
                }
            }
        }

        return output
    }

    private fun gaussianSmooth(data: FloatArray, windowSize: Int): FloatArray {
        if (data.isEmpty()) return data
        val result = FloatArray(data.size)
        val halfWindow = windowSize / 2
        val sigma = windowSize / 4f

        // Precompute Gaussian weights
        val weights = FloatArray(windowSize) { i ->
            val x = (i - halfWindow).toFloat()
            kotlin.math.exp(-(x * x) / (2 * sigma * sigma))
        }
        val weightSum = weights.sum()

        for (i in data.indices) {
            var sum = 0f
            var wSum = 0f
            for (j in 0 until windowSize) {
                val idx = i + j - halfWindow
                if (idx in data.indices) {
                    sum += data[idx] * weights[j]
                    wSum += weights[j]
                }
            }
            result[i] = if (wSum > 0) sum / wSum else data[i]
        }

        return result
    }
}
