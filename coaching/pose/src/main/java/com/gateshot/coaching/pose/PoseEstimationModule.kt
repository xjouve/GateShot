package com.gateshot.coaching.pose

import android.content.Context
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pose Estimation Module — Skeleton overlay for ski racing technique analysis.
 *
 * Uses MoveNet (17 keypoints) or MediaPipe (33 keypoints) via TFLite to
 * extract body joint positions from video frames.
 *
 * Key angles for ski racing:
 * - Knee angle: flexion/extension (ideal: 100-130° in turn, depends on discipline)
 * - Hip angle: inclination toward the slope
 * - Upper body lean: torso angle relative to vertical
 * - Ankle flexion: forward lean of the shin (critical for pressure control)
 * - Shoulder alignment: rotation relative to ski direction
 *
 * The Dimensity 9500 APU can run MoveNet at ~15ms per frame — fast enough
 * for real-time overlay during replay playback.
 */
@Singleton
class PoseEstimationModule @Inject constructor(
    @ApplicationContext private val context: Context
) : FeatureModule {

    override val name = "pose"
    override val version = "0.1.0"
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

    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        EstimatePose(),
        EstimatePoseBatch(),
        ComputeAngles(),
        ComparePoses()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    /**
     * Estimate pose from a single frame.
     * In production: runs TFLite MoveNet model.
     * Current: returns placeholder skeleton for UI development.
     */
    private fun estimatePoseFromFrame(frameWidth: Int, frameHeight: Int): SkeletonData {
        // Placeholder: generate a skiing pose skeleton
        // In production, this feeds the frame through TFLite MoveNet
        val keypoints = listOf(
            Keypoint(NOSE, 0.50f, 0.15f, 0.9f),
            Keypoint(LEFT_EYE, 0.49f, 0.13f, 0.85f),
            Keypoint(RIGHT_EYE, 0.51f, 0.13f, 0.85f),
            Keypoint(LEFT_EAR, 0.47f, 0.14f, 0.7f),
            Keypoint(RIGHT_EAR, 0.53f, 0.14f, 0.7f),
            Keypoint(LEFT_SHOULDER, 0.44f, 0.25f, 0.9f),
            Keypoint(RIGHT_SHOULDER, 0.56f, 0.25f, 0.9f),
            Keypoint(LEFT_ELBOW, 0.38f, 0.35f, 0.85f),
            Keypoint(RIGHT_ELBOW, 0.62f, 0.35f, 0.85f),
            Keypoint(LEFT_WRIST, 0.35f, 0.30f, 0.8f),
            Keypoint(RIGHT_WRIST, 0.65f, 0.30f, 0.8f),
            Keypoint(LEFT_HIP, 0.46f, 0.50f, 0.9f),
            Keypoint(RIGHT_HIP, 0.54f, 0.50f, 0.9f),
            Keypoint(LEFT_KNEE, 0.42f, 0.68f, 0.9f),
            Keypoint(RIGHT_KNEE, 0.52f, 0.65f, 0.9f),
            Keypoint(LEFT_ANKLE, 0.40f, 0.85f, 0.85f),
            Keypoint(RIGHT_ANKLE, 0.50f, 0.82f, 0.85f)
        )
        return SkeletonData(keypoints = keypoints, confidence = 0.85f)
    }

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

    // --- coach/analysis/pose/run ---
    inner class EstimatePose : ApiEndpoint<PoseRequest, PoseResult> {
        override val path = "coach/analysis/pose/run"
        override val module = "pose"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: PoseRequest): ApiResponse<PoseResult> {
            val skeleton = estimatePoseFromFrame(request.frameWidth, request.frameHeight)
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
            val results = (0 until request.frameCount).map { frame ->
                val skeleton = estimatePoseFromFrame(request.frameWidth, request.frameHeight)
                val angles = computeSkiAngles(skeleton)
                PoseResult(skeleton, angles, frame)
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

data class PoseRequest(val frameWidth: Int, val frameHeight: Int, val frameNumber: Int = 0)
data class PoseBatchRequest(val frameWidth: Int, val frameHeight: Int, val frameCount: Int)
data class PoseResult(val skeleton: SkeletonData, val angles: SkiAngles, val frameNumber: Int)
data class PoseCompareRequest(val skeletonA: SkeletonData, val skeletonB: SkeletonData)
data class PoseComparison(
    val anglesA: SkiAngles, val anglesB: SkiAngles,
    val kneeAngleDiff: Float, val hipAngleDiff: Float, val torsoLeanDiff: Float
)
