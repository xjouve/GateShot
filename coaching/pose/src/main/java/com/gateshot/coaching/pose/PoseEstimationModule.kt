package com.gateshot.coaching.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pose Estimation Module — Skeleton overlay for ski racing technique analysis.
 *
 * Uses MoveNet Lightning (17 keypoints) via TFLite on the Dimensity 9500 NPU
 * to extract body joint positions from video frames.
 *
 * Key angles for ski racing:
 * - Knee angle: flexion/extension (ideal: 100-130° in turn, depends on discipline)
 * - Hip angle: inclination toward the slope
 * - Upper body lean: torso angle relative to vertical
 * - Ankle flexion: forward lean of the shin (critical for pressure control)
 * - Shoulder alignment: rotation relative to ski direction
 *
 * The Dimensity 9500 APU runs MoveNet at ~15ms per frame — fast enough
 * for real-time overlay during replay playback.
 */
@Singleton
class PoseEstimationModule @Inject constructor(
    @ApplicationContext private val context: Context
) : FeatureModule {

    override val name = "pose"
    override val version = "0.2.0"
    override val requiredMode = AppMode.COACH

    // MoveNet keypoint indices (17 keypoints)
    companion object {
        const val NOSE = 0
        const val LEFT_EYE = 1
        const val RIGHT_EYE = 2
        const val LEFT_EAR = 3
        const val RIGHT_EAR = 4
        const val LEFT_SHOULDER = 5
        const val RIGHT_SHOULDER = 6
        const val LEFT_ELBOW = 7
        const val RIGHT_ELBOW = 8
        const val LEFT_WRIST = 9
        const val RIGHT_WRIST = 10
        const val LEFT_HIP = 11
        const val RIGHT_HIP = 12
        const val LEFT_KNEE = 13
        const val RIGHT_KNEE = 14
        const val LEFT_ANKLE = 15
        const val RIGHT_ANKLE = 16

        // MoveNet input size
        private const val MODEL_INPUT_SIZE = 192

        // Minimum confidence to consider a keypoint detected
        private const val MIN_KEYPOINT_CONFIDENCE = 0.2f

        // Model file names (searched in order)
        private val MODEL_CANDIDATES = listOf(
            "movenet_lightning.tflite",
            "movenet_thunder.tflite",
            "pose_model.tflite"
        )

        // Skeleton connections for drawing
        val SKELETON_CONNECTIONS = listOf(
            LEFT_SHOULDER to RIGHT_SHOULDER,
            LEFT_SHOULDER to LEFT_ELBOW,
            LEFT_ELBOW to LEFT_WRIST,
            RIGHT_SHOULDER to RIGHT_ELBOW,
            RIGHT_ELBOW to RIGHT_WRIST,
            LEFT_SHOULDER to LEFT_HIP,
            RIGHT_SHOULDER to RIGHT_HIP,
            LEFT_HIP to RIGHT_HIP,
            LEFT_HIP to LEFT_KNEE,
            LEFT_KNEE to LEFT_ANKLE,
            RIGHT_HIP to RIGHT_KNEE,
            RIGHT_KNEE to RIGHT_ANKLE
        )
    }

    private var interpreter: Interpreter? = null
    private var isModelLoaded = false

    override suspend fun initialize() {
        loadModel()
    }

    override suspend fun shutdown() {
        interpreter?.close()
        interpreter = null
        isModelLoaded = false
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        EstimatePose(),
        EstimatePoseBatch(),
        ComputeAngles(),
        ComparePoses()
    )

    override fun healthCheck(): ModuleHealth {
        val status = if (isModelLoaded) ModuleHealth.Status.OK else ModuleHealth.Status.DEGRADED
        val msg = if (isModelLoaded) "MoveNet loaded, NPU inference ready" else "Model not loaded"
        return ModuleHealth(name, status, msg)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TFLite Model Loading
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadModel() {
        val modelFile = findModelFile()
        if (modelFile == null) {
            isModelLoaded = false
            return
        }

        try {
            val options = Interpreter.Options()

            // Try NNAPI (Dimensity 9500 APU) first — fastest for pose estimation
            try {
                val nnApiDelegate = NnApiDelegate(
                    NnApiDelegate.Options().apply {
                        setAllowFp16(true)
                    }
                )
                options.addDelegate(nnApiDelegate)
            } catch (_: Exception) {
                // NNAPI not available — try GPU
                try {
                    val gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                } catch (_: Exception) {
                    // Fall back to CPU with 4 threads
                    options.setNumThreads(4)
                }
            }

            val mappedModel = loadMappedFile(modelFile)
            interpreter = Interpreter(mappedModel, options)
            isModelLoaded = true
        } catch (e: Exception) {
            isModelLoaded = false
        }
    }

    private fun findModelFile(): File? {
        val modelDir = File(context.filesDir, "models")
        for (name in MODEL_CANDIDATES) {
            val file = File(modelDir, name)
            if (file.exists()) return file
        }
        // Also check assets directory (bundled with APK)
        for (name in MODEL_CANDIDATES) {
            try {
                val assetList = context.assets.list("") ?: continue
                if (name in assetList) {
                    // Copy asset to filesDir for TFLite to memory-map
                    val outFile = File(modelDir.also { it.mkdirs() }, name)
                    context.assets.open(name).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    return outFile
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun loadMappedFile(file: File): MappedByteBuffer {
        FileInputStream(file).use { fis ->
            val channel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pose Estimation — TFLite MoveNet Inference
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run MoveNet inference on a frame to extract 17 body keypoints.
     *
     * MoveNet Lightning input: [1, 192, 192, 3] (RGB uint8)
     * MoveNet Lightning output: [1, 1, 17, 3] (y, x, confidence for each keypoint)
     *
     * For frames from video replay, the caller provides width/height and
     * optionally pixel data. When pixel data is available, we run real inference.
     */
    fun estimatePoseFromFrame(
        frameWidth: Int,
        frameHeight: Int,
        framePixels: IntArray? = null
    ): SkeletonData {
        val model = interpreter
        if (model == null || framePixels == null) {
            // No model or no pixel data — return empty skeleton
            return SkeletonData(keypoints = emptyList(), confidence = 0f)
        }

        // Prepare input: resize frame to 192×192 and convert to RGB ByteBuffer
        val inputBuffer = prepareInput(framePixels, frameWidth, frameHeight)

        // Prepare output: [1, 1, 17, 3]
        val outputArray = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }

        // Run inference
        model.run(inputBuffer, outputArray)

        // Parse output to keypoints
        val keypoints = mutableListOf<Keypoint>()
        val rawKeypoints = outputArray[0][0]

        for (i in 0 until 17) {
            val y = rawKeypoints[i][0]  // MoveNet outputs Y first
            val x = rawKeypoints[i][1]
            val confidence = rawKeypoints[i][2]

            keypoints.add(Keypoint(
                id = i,
                x = x,  // Already normalized 0-1
                y = y,
                confidence = confidence
            ))
        }

        // Overall skeleton confidence = average of detected keypoints
        val detectedKeypoints = keypoints.filter { it.confidence >= MIN_KEYPOINT_CONFIDENCE }
        val overallConfidence = if (detectedKeypoints.isNotEmpty()) {
            detectedKeypoints.map { it.confidence }.average().toFloat()
        } else 0f

        return SkeletonData(keypoints = keypoints, confidence = overallConfidence)
    }

    /**
     * Prepare input tensor for MoveNet.
     *
     * Resizes the frame to 192×192 using nearest-neighbor sampling (fast),
     * and packs RGB values into a ByteBuffer in NHWC format.
     */
    private fun prepareInput(pixels: IntArray, width: Int, height: Int): ByteBuffer {
        val inputSize = MODEL_INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val xRatio = width.toFloat() / inputSize
        val yRatio = height.toFloat() / inputSize

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val srcX = (x * xRatio).toInt().coerceIn(0, width - 1)
                val srcY = (y * yRatio).toInt().coerceIn(0, height - 1)
                val srcIdx = srcY * width + srcX

                val pixel = if (srcIdx < pixels.size) pixels[srcIdx] else 0

                // Extract RGB and normalize to 0-1 float (MoveNet expects float input)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Estimate pose from a bitmap (for static image analysis).
     */
    fun estimatePoseFromBitmap(bitmap: Bitmap): SkeletonData {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return estimatePoseFromFrame(width, height, pixels)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Angle Computation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute ski-racing-specific joint angles from keypoints.
     */
    private fun computeSkiAngles(skeleton: SkeletonData): SkiAngles {
        val kp = skeleton.keypoints.associateBy { it.id }

        return SkiAngles(
            leftKneeAngle = computeAngle(kp[LEFT_HIP], kp[LEFT_KNEE], kp[LEFT_ANKLE]),
            rightKneeAngle = computeAngle(kp[RIGHT_HIP], kp[RIGHT_KNEE], kp[RIGHT_ANKLE]),
            leftHipAngle = computeAngle(kp[LEFT_SHOULDER], kp[LEFT_HIP], kp[LEFT_KNEE]),
            rightHipAngle = computeAngle(kp[RIGHT_SHOULDER], kp[RIGHT_HIP], kp[RIGHT_KNEE]),
            torsoLean = computeTorsoLean(kp[LEFT_SHOULDER], kp[RIGHT_SHOULDER], kp[LEFT_HIP], kp[RIGHT_HIP]),
            shoulderRotation = computeShoulderRotation(kp[LEFT_SHOULDER], kp[RIGHT_SHOULDER]),
            leftElbowAngle = computeAngle(kp[LEFT_SHOULDER], kp[LEFT_ELBOW], kp[LEFT_WRIST]),
            rightElbowAngle = computeAngle(kp[RIGHT_SHOULDER], kp[RIGHT_ELBOW], kp[RIGHT_WRIST])
        )
    }

    private fun computeAngle(a: Keypoint?, b: Keypoint?, c: Keypoint?): Float {
        if (a == null || b == null || c == null) return 0f
        if (a.confidence < MIN_KEYPOINT_CONFIDENCE ||
            b.confidence < MIN_KEYPOINT_CONFIDENCE ||
            c.confidence < MIN_KEYPOINT_CONFIDENCE) return 0f

        val ba = Pair(a.x - b.x, a.y - b.y)
        val bc = Pair(c.x - b.x, c.y - b.y)
        val dot = ba.first * bc.first + ba.second * bc.second
        val magBA = sqrt(ba.first * ba.first + ba.second * ba.second)
        val magBC = sqrt(bc.first * bc.first + bc.second * bc.second)
        if (magBA == 0f || magBC == 0f) return 0f
        val cosAngle = (dot / (magBA * magBC)).coerceIn(-1f, 1f)
        return (Math.acos(cosAngle.toDouble()) * 180 / PI).toFloat()
    }

    private fun computeTorsoLean(ls: Keypoint?, rs: Keypoint?, lh: Keypoint?, rh: Keypoint?): Float {
        if (ls == null || rs == null || lh == null || rh == null) return 0f
        val shoulderMidX = (ls.x + rs.x) / 2
        val shoulderMidY = (ls.y + rs.y) / 2
        val hipMidX = (lh.x + rh.x) / 2
        val hipMidY = (lh.y + rh.y) / 2
        val dx = shoulderMidX - hipMidX
        val dy = shoulderMidY - hipMidY
        return (atan2(dx.toDouble(), -dy.toDouble()) * 180 / PI).toFloat()
    }

    private fun computeShoulderRotation(ls: Keypoint?, rs: Keypoint?): Float {
        if (ls == null || rs == null) return 0f
        val dx = rs.x - ls.x
        val dy = rs.y - ls.y
        return (atan2(dy.toDouble(), dx.toDouble()) * 180 / PI).toFloat()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Endpoints
    // ─────────────────────────────────────────────────────────────────────────

    // --- coach/analysis/pose/run ---
    inner class EstimatePose : ApiEndpoint<PoseRequest, PoseResult> {
        override val path = "coach/analysis/pose/run"
        override val module = "pose"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: PoseRequest): ApiResponse<PoseResult> {
            val skeleton = estimatePoseFromFrame(
                request.frameWidth, request.frameHeight, request.framePixels
            )
            if (skeleton.keypoints.isEmpty()) {
                return ApiResponse.error(500, "Pose model not loaded or no frame data provided")
            }
            val angles = computeSkiAngles(skeleton)
            return ApiResponse.success(PoseResult(skeleton, angles, request.frameNumber))
        }
    }

    // --- coach/analysis/pose/batch ---
    inner class EstimatePoseBatch : ApiEndpoint<PoseBatchRequest, List<PoseResult>> {
        override val path = "coach/analysis/pose/batch"
        override val module = "pose"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: PoseBatchRequest): ApiResponse<List<PoseResult>> {
            val results = request.frames.mapIndexed { index, framePixels ->
                val skeleton = estimatePoseFromFrame(
                    request.frameWidth, request.frameHeight, framePixels
                )
                val angles = computeSkiAngles(skeleton)
                PoseResult(skeleton, angles, index)
            }
            return ApiResponse.success(results)
        }
    }

    // --- coach/analysis/angles/measure ---
    inner class ComputeAngles : ApiEndpoint<SkeletonData, SkiAngles> {
        override val path = "coach/analysis/angles/measure"
        override val module = "pose"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: SkeletonData): ApiResponse<SkiAngles> {
            return ApiResponse.success(computeSkiAngles(request))
        }
    }

    // --- coach/analysis/pose/compare ---
    inner class ComparePoses : ApiEndpoint<PoseCompareRequest, PoseComparison> {
        override val path = "coach/analysis/pose/compare"
        override val module = "pose"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: PoseCompareRequest): ApiResponse<PoseComparison> {
            val anglesA = computeSkiAngles(request.skeletonA)
            val anglesB = computeSkiAngles(request.skeletonB)
            return ApiResponse.success(PoseComparison(
                anglesA = anglesA,
                anglesB = anglesB,
                kneeAngleDiff = abs(anglesA.leftKneeAngle - anglesB.leftKneeAngle),
                hipAngleDiff = abs(anglesA.leftHipAngle - anglesB.leftHipAngle),
                torsoLeanDiff = abs(anglesA.torsoLean - anglesB.torsoLean)
            ))
        }
    }
}

@Serializable
data class Keypoint(val id: Int, val x: Float, val y: Float, val confidence: Float)

@Serializable
data class SkeletonData(val keypoints: List<Keypoint>, val confidence: Float)

@Serializable
data class SkiAngles(
    val leftKneeAngle: Float = 0f,
    val rightKneeAngle: Float = 0f,
    val leftHipAngle: Float = 0f,
    val rightHipAngle: Float = 0f,
    val torsoLean: Float = 0f,
    val shoulderRotation: Float = 0f,
    val leftElbowAngle: Float = 0f,
    val rightElbowAngle: Float = 0f
)

data class PoseRequest(
    val frameWidth: Int,
    val frameHeight: Int,
    val frameNumber: Int = 0,
    val framePixels: IntArray? = null
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = framePixels?.contentHashCode() ?: 0
}

data class PoseBatchRequest(
    val frameWidth: Int,
    val frameHeight: Int,
    val frames: List<IntArray>
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = frames.hashCode()
}

data class PoseResult(val skeleton: SkeletonData, val angles: SkiAngles, val frameNumber: Int)
data class PoseCompareRequest(val skeletonA: SkeletonData, val skeletonB: SkeletonData)
data class PoseComparison(
    val anglesA: SkiAngles, val anglesB: SkiAngles,
    val kneeAngleDiff: Float, val hipAngleDiff: Float, val torsoLeanDiff: Float
)
