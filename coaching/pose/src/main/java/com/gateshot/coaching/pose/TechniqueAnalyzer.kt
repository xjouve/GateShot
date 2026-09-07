package com.gateshot.coaching.pose

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.gateshot.core.module.ModuleHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "TechniqueAnalyzer"
private const val SEARCH_FRAME_BOX = 960
private const val KEYFRAME_DECODE_BOX = 1920

// ─────────────────────────────────────────────────────────────────────────
// Public data model
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float)

@Serializable
data class PoseSample(
    val timestampMs: Long,
    val kneeAngleL: Float,
    val kneeAngleR: Float,
    val hipAngle: Float,
    val torsoLeanDeg: Float,
    val shoulderTiltDeg: Float,
    val stanceWidthRatio: Float,
    val handsForward: Float,
    val personHeightPx: Float,
    val confidence: Float,
    val cropRect: NormalizedRect
)

@Serializable
data class MetricStats(val min: Float, val max: Float, val mean: Float, val p10: Float, val p90: Float)

@Serializable
data class GateSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val stats: Map<String, MetricStats>
)

@Serializable
data class TechniqueFlag(
    val code: String,
    val severity: Int,
    val message: String,
    val timestampMs: Long? = null
)

@Serializable
data class KeyFrameRef(
    val timestampMs: Long,
    val reason: String,
    val cropRect: NormalizedRect?
)

data class KeyFrame(
    val timestampMs: Long,
    val reason: String,
    val jpegBytes: ByteArray,
    val cropped: Boolean
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = jpegBytes.contentHashCode()
}

@Serializable
data class TechniqueReport(
    val clipPath: String,
    val durationMs: Long,
    val sampleIntervalMs: Long,
    val samples: List<PoseSample>,
    val trackedFraction: Float,
    val metricStats: Map<String, MetricStats>,
    val gateSegments: List<GateSegment>,
    val flags: List<TechniqueFlag>,
    val keyFrames: List<KeyFrameRef>
) {
    companion object {
        fun load(file: File): TechniqueReport =
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), file.readText())
    }
}

fun TechniqueReport.save(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(Json.encodeToString(TechniqueReport.serializer(), this))
}

/** Sidecar path convention: `<clipPath>.technique.json`. */
fun techniqueSidecarFile(clipPath: String): File = File("$clipPath.technique.json")

private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

/**
 * Compact JSON intended for a language-model prompt: aggregates, gate
 * segments, flags and key-frame list — no per-sample data — rounded to
 * 1 decimal, aiming for <= ~4 KB.
 */
fun TechniqueReport.toPromptJson(): String {
    fun metricsJson(m: Map<String, MetricStats>) = JsonObject(
        m.mapValues { (_, v) ->
            JsonObject(
                mapOf(
                    "min" to JsonPrimitive(round1(v.min)),
                    "max" to JsonPrimitive(round1(v.max)),
                    "mean" to JsonPrimitive(round1(v.mean)),
                    "p10" to JsonPrimitive(round1(v.p10)),
                    "p90" to JsonPrimitive(round1(v.p90))
                )
            )
        }
    )
    val root = JsonObject(
        mapOf(
            "clipPath" to JsonPrimitive(clipPath),
            "durationMs" to JsonPrimitive(durationMs),
            "trackedFraction" to JsonPrimitive(round1(trackedFraction)),
            "metrics" to metricsJson(metricStats),
            "gateSegments" to JsonArray(
                gateSegments.map { seg ->
                    JsonObject(
                        mapOf(
                            "index" to JsonPrimitive(seg.index),
                            "startMs" to JsonPrimitive(seg.startMs),
                            "endMs" to JsonPrimitive(seg.endMs),
                            "metrics" to metricsJson(seg.stats)
                        )
                    )
                }
            ),
            "flags" to JsonArray(
                flags.map { f ->
                    JsonObject(
                        buildMap {
                            put("code", JsonPrimitive(f.code))
                            put("severity", JsonPrimitive(f.severity))
                            put("message", JsonPrimitive(f.message))
                            f.timestampMs?.let { put("timestampMs", JsonPrimitive(it)) }
                        }
                    )
                }
            ),
            "keyFrames" to JsonArray(
                keyFrames.map { kf ->
                    JsonObject(
                        mapOf(
                            "timestampMs" to JsonPrimitive(kf.timestampMs),
                            "reason" to JsonPrimitive(kf.reason)
                        )
                    )
                }
            )
        )
    )
    return Json.encodeToString(JsonObject.serializer(), root)
}

// ─────────────────────────────────────────────────────────────────────────
// Aggregation helpers (pure, no Android calls — directly unit-testable)
// ─────────────────────────────────────────────────────────────────────────

val METRIC_EXTRACTORS: Map<String, (PoseSample) -> Float> = mapOf(
    "kneeAngleL" to { s: PoseSample -> s.kneeAngleL },
    "kneeAngleR" to { s: PoseSample -> s.kneeAngleR },
    "hipAngle" to { s: PoseSample -> s.hipAngle },
    "torsoLeanDeg" to { s: PoseSample -> s.torsoLeanDeg },
    "shoulderTiltDeg" to { s: PoseSample -> s.shoulderTiltDeg },
    "stanceWidthRatio" to { s: PoseSample -> s.stanceWidthRatio },
    "handsForward" to { s: PoseSample -> s.handsForward }
)

fun computeAllMetricStats(samples: List<PoseSample>): Map<String, MetricStats> =
    METRIC_EXTRACTORS.mapNotNull { (name, extractor) ->
        val raw = computeStats(samples.map(extractor)) ?: return@mapNotNull null
        name to MetricStats(raw.min, raw.max, raw.mean, raw.p10, raw.p90)
    }.toMap()

/** Segments run between consecutive gates; empty when fewer than two gates are given. */
fun buildGateSegments(confident: List<PoseSample>, gateTimestampsMs: List<Long>): List<GateSegment> {
    if (gateTimestampsMs.size < 2) return emptyList()
    val gates = gateTimestampsMs.sorted()
    return (0 until gates.size - 1).map { i ->
        val start = gates[i]; val end = gates[i + 1]
        val inSegment = confident.filter { it.timestampMs in start..end }
        GateSegment(i, start, end, computeAllMetricStats(inSegment))
    }
}

fun buildFlags(
    confident: List<PoseSample>,
    allSamples: List<PoseSample>,
    gateTimestampsMs: List<Long>,
    trackedFraction: Float,
    stats: Map<String, MetricStats>
): List<TechniqueFlag> {
    val flags = mutableListOf<TechniqueFlag>()

    if (trackedFraction < 0.5f) {
        flags += TechniqueFlag(
            "LOW_TRACKING", 3,
            "Pose tracking held on only ${(trackedFraction * 100).toInt()}% of sampled frames — results unreliable.",
        )
    }

    if (confident.isNotEmpty()) {
        val meanKnee = confident.map { (it.kneeAngleL + it.kneeAngleR) / 2f }.average().toFloat()
        if (meanKnee > 150f) {
            flags += TechniqueFlag(
                "UPRIGHT", 2,
                "Average knee angle is ${round1(meanKnee)}°, suggesting an upright stance with limited flexion."
            )
        }
    }

    for (gate in gateTimestampsMs) {
        val near = confident.filter { abs(it.timestampMs - gate) <= 150 }
        val straight = near.firstOrNull { it.kneeAngleL > 155f || it.kneeAngleR > 155f }
        if (straight != null) {
            flags += TechniqueFlag(
                "STRAIGHT_LEGS_AT_GATE", 2,
                "Legs were nearly straight (>155°) within 150ms of a gate passage.",
                straight.timestampMs
            )
        }
    }

    if (confident.isNotEmpty()) {
        val backFraction = confident.count { it.handsForward < 0f }.toFloat() / confident.size
        if (backFraction > 0.4f) {
            flags += TechniqueFlag(
                "HANDS_BACK", 2,
                "Hands trailed behind the hips on ${(backFraction * 100).toInt()}% of tracked samples."
            )
        }
    }

    stats["shoulderTiltDeg"]?.let {
        if (abs(it.p90) > 20f) {
            flags += TechniqueFlag(
                "SHOULDER_TILT", 2,
                "Shoulder tilt reached ${round1(abs(it.p90))}° (90th percentile), indicating upper-body rotation/imbalance."
            )
        }
    }

    stats["stanceWidthRatio"]?.let {
        if (it.p10 < 0.8f) {
            flags += TechniqueFlag(
                "NARROW_STANCE", 1,
                "Stance narrowed to ${round1(it.p10)}× hip width on the tightest samples."
            )
        }
    }

    return flags
}

/** Picks 6-8 key frames: gate passages, notable poses, plus evenly-spaced fallbacks; sorted by time. */
fun selectKeyFrames(samples: List<PoseSample>, gateTimestampsMs: List<Long>, minCount: Int = 6, maxCount: Int = 8): List<KeyFrameRef> {
    val confident = samples.filter { it.confidence >= MIN_TRACK_CONFIDENCE }
    if (confident.isEmpty()) return emptyList()

    val picked = LinkedHashMap<Long, KeyFrameRef>()
    fun add(sample: PoseSample, reason: String) {
        picked.putIfAbsent(sample.timestampMs, KeyFrameRef(sample.timestampMs, reason, sample.cropRect))
    }

    for (gate in gateTimestampsMs) {
        val nearest = confident.minByOrNull { abs(it.timestampMs - gate) } ?: continue
        add(nearest, "Gate passage near ${gate}ms")
    }

    confident.minByOrNull { (it.kneeAngleL + it.kneeAngleR) / 2f }?.let { add(it, "Deepest knee flexion") }
    confident.maxByOrNull { (it.kneeAngleL + it.kneeAngleR) / 2f }?.let { add(it, "Most upright") }
    confident.maxByOrNull { abs(it.torsoLeanDeg) }?.let { add(it, "Max torso lean") }

    if (picked.size < minCount) {
        val step = max(1, confident.size / (minCount - picked.size + 1))
        var i = 0
        while (picked.size < minCount && i < confident.size) {
            add(confident[i], "Sampled frame")
            i += step
        }
    }
    var idx = 0
    while (picked.size < minCount && idx < confident.size) {
        add(confident[idx], "Sampled frame")
        idx++
    }

    return picked.values.sortedBy { it.timestampMs }.take(maxCount)
}

// ─────────────────────────────────────────────────────────────────────────
// Localize-then-track pose pipeline (Android)
// ─────────────────────────────────────────────────────────────────────────

private data class TrackState(
    val centerXNorm: Float,
    val centerYNorm: Float,
    val personHeightPx: Float
)

private data class TrackResult(
    val skeleton: SkeletonData, // keypoints mapped to full-frame normalized coords
    val crop: CropRect,         // pixel crop within the decoded frame
    val frameW: Int,
    val frameH: Int,
    val personHeightPx: Float,
    val bboxCenterXNorm: Float,
    val bboxCenterYNorm: Float,
    val hipCenterXNorm: Float
)

private data class PersonCheck(val plausible: Boolean, val heightFraction: Float)

@Singleton
class TechniqueAnalyzer @Inject constructor(
    private val pose: PoseEstimationModule
) {

    suspend fun analyze(
        clipPath: String,
        gateTimestampsMs: List<Long>,
        sampleIntervalMs: Long = 200,
        onProgress: (Float) -> Unit = {}
    ): TechniqueReport = withContext(Dispatchers.Default) {
        val health = pose.healthCheck()
        if (health.status != ModuleHealth.Status.OK) {
            throw IllegalStateException("TechniqueAnalyzer: pose model not loaded (${health.message})")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(clipPath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: throw IllegalStateException("TechniqueAnalyzer: could not read duration for $clipPath")

            val samples = mutableListOf<PoseSample>()
            val xHistory = mutableListOf<Float>()
            var track: TrackState? = null
            val totalSteps = max(1, (durationMs / sampleIntervalMs).toInt())
            var step = 0
            var tMs = 0L

            while (tMs < durationMs) {
                coroutineContext.ensureActive()
                val bitmap = retriever.getScaledFrameAtTime(
                    tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, SEARCH_FRAME_BOX, SEARCH_FRAME_BOX
                )
                if (bitmap != null) {
                    val result = (track?.let { trackFrame(bitmap, it) }) ?: searchFrame(bitmap)
                    if (result != null) {
                        xHistory += result.hipCenterXNorm
                        if (xHistory.size > 8) xHistory.removeAt(0)
                        val dirSign = travelDirectionSign(xHistory)
                        samples += buildSample(tMs, result, dirSign)
                        track = if (result.skeleton.confidence >= MIN_TRACK_CONFIDENCE) {
                            TrackState(result.bboxCenterXNorm, result.bboxCenterYNorm, result.personHeightPx)
                        } else null
                    } else {
                        track = null
                    }
                    bitmap.recycle()
                } else {
                    Log.w(TAG, "No frame decoded at ${tMs}ms for $clipPath")
                }
                step++
                onProgress((step.toFloat() / totalSteps).coerceIn(0f, 1f))
                tMs += sampleIntervalMs
            }
            onProgress(1f)

            val confident = samples.filter { it.confidence >= MIN_TRACK_CONFIDENCE }
            val trackedFraction = if (samples.isNotEmpty()) confident.size.toFloat() / samples.size else 0f
            val metricStats = computeAllMetricStats(confident)
            val segments = buildGateSegments(confident, gateTimestampsMs)
            val flags = buildFlags(confident, samples, gateTimestampsMs, trackedFraction, metricStats)
            val keyFrames = selectKeyFrames(samples, gateTimestampsMs)

            TechniqueReport(
                clipPath = clipPath,
                durationMs = durationMs,
                sampleIntervalMs = sampleIntervalMs,
                samples = samples,
                trackedFraction = trackedFraction,
                metricStats = metricStats,
                gateSegments = segments,
                flags = flags,
                keyFrames = keyFrames
            )
        } finally {
            retriever.release()
        }
    }

    private fun searchFrame(bitmap: Bitmap): TrackResult? {
        val w = bitmap.width; val h = bitmap.height
        val crops = mutableListOf(CropRect(0f, 0f, w.toFloat(), h.toFloat()))
        for (divisor in intArrayOf(2, 3)) {
            val side = h.toFloat() / divisor
            if (side < 32f) continue
            val stride = side / 2f
            var y = 0f
            while (y + side <= h) {
                var x = 0f
                while (x + side <= w) {
                    crops += CropRect(x, y, side, side)
                    x += stride
                }
                y += stride
            }
        }

        var bestSkeleton: SkeletonData? = null
        var bestCrop: CropRect? = null
        for (crop in crops) {
            val isFull = crop.x == 0f && crop.y == 0f && crop.width == w.toFloat() && crop.height == h.toFloat()
            val cropBitmap = if (isFull) bitmap else
                Bitmap.createBitmap(bitmap, crop.x.toInt(), crop.y.toInt(), crop.width.toInt(), crop.height.toInt())
            val skeleton = pose.estimatePoseFromBitmap(cropBitmap)
            if (!isFull) cropBitmap.recycle()

            val check = personPlausibility(skeleton)
            if (check.plausible && (bestSkeleton == null || skeleton.confidence > bestSkeleton!!.confidence)) {
                bestSkeleton = skeleton
                bestCrop = crop
            }
        }
        val skeleton = bestSkeleton ?: return null
        val crop = bestCrop ?: return null
        return toTrackResult(skeleton, crop, w, h)
    }

    private fun trackFrame(bitmap: Bitmap, state: TrackState): TrackResult? {
        val w = bitmap.width; val h = bitmap.height
        val cx = state.centerXNorm * w
        val cy = state.centerYNorm * h
        val side = max(2.5f * state.personHeightPx, 160f)
        val minSide = min(160f, min(w, h).toFloat())
        val crop = clampSquareCrop(cx, cy, side, w.toFloat(), h.toFloat(), minSide)

        val cropBitmap = Bitmap.createBitmap(bitmap, crop.x.toInt(), crop.y.toInt(), crop.width.toInt(), crop.height.toInt())
        val skeleton = pose.estimatePoseFromBitmap(cropBitmap)
        cropBitmap.recycle()

        val check = personPlausibility(skeleton)
        if (skeleton.confidence < MIN_TRACK_CONFIDENCE || !check.plausible) return null
        return toTrackResult(skeleton, crop, w, h)
    }

    private fun personPlausibility(skeleton: SkeletonData): PersonCheck {
        val kp = skeleton.keypoints.associateBy { it.id }
        fun conf(id: Int) = kp[id]?.confidence ?: 0f
        val hipDetected = conf(PoseEstimationModule.LEFT_HIP) >= MIN_KEYPOINT_CONFIDENCE ||
            conf(PoseEstimationModule.RIGHT_HIP) >= MIN_KEYPOINT_CONFIDENCE
        val kneeOrAnkleDetected = conf(PoseEstimationModule.LEFT_KNEE) >= MIN_KEYPOINT_CONFIDENCE ||
            conf(PoseEstimationModule.RIGHT_KNEE) >= MIN_KEYPOINT_CONFIDENCE ||
            conf(PoseEstimationModule.LEFT_ANKLE) >= MIN_KEYPOINT_CONFIDENCE ||
            conf(PoseEstimationModule.RIGHT_ANKLE) >= MIN_KEYPOINT_CONFIDENCE
        val ys = listOfNotNull(
            PoseEstimationModule.LEFT_SHOULDER, PoseEstimationModule.RIGHT_SHOULDER,
            PoseEstimationModule.LEFT_HIP, PoseEstimationModule.RIGHT_HIP,
            PoseEstimationModule.LEFT_KNEE, PoseEstimationModule.RIGHT_KNEE,
            PoseEstimationModule.LEFT_ANKLE, PoseEstimationModule.RIGHT_ANKLE
        ).mapNotNull { id -> kp[id]?.takeIf { it.confidence >= MIN_KEYPOINT_CONFIDENCE }?.y }
        val heightFraction = if (ys.size >= 2) (ys.max() - ys.min()) else 0f
        return PersonCheck(isPlausiblePerson(hipDetected, kneeOrAnkleDetected, heightFraction), heightFraction)
    }

    private fun toTrackResult(skeleton: SkeletonData, crop: CropRect, frameW: Int, frameH: Int): TrackResult {
        val check = personPlausibility(skeleton)
        val mappedKeypoints = skeleton.keypoints.map { kp ->
            kp.copy(
                x = (crop.x + kp.x * crop.width) / frameW,
                y = (crop.y + kp.y * crop.height) / frameH
            )
        }
        val mapped = skeleton.copy(keypoints = mappedKeypoints)
        val kp = mapped.keypoints.associateBy { it.id }
        val hipXs = listOfNotNull(kp[PoseEstimationModule.LEFT_HIP]?.x, kp[PoseEstimationModule.RIGHT_HIP]?.x)
        val hipCenterX = if (hipXs.isNotEmpty()) hipXs.average().toFloat() else 0.5f
        val detected = mapped.keypoints.filter { it.confidence >= MIN_KEYPOINT_CONFIDENCE }
        val bboxCx = if (detected.isNotEmpty()) (detected.minOf { it.x } + detected.maxOf { it.x }) / 2f else hipCenterX
        val bboxCy = if (detected.isNotEmpty()) (detected.minOf { it.y } + detected.maxOf { it.y }) / 2f else 0.5f
        val personHeightPx = check.heightFraction * crop.height
        return TrackResult(mapped, crop, frameW, frameH, personHeightPx, bboxCx, bboxCy, hipCenterX)
    }

    private fun buildSample(tMs: Long, r: TrackResult, dirSign: Float): PoseSample {
        val kp = r.skeleton.keypoints.associateBy { it.id }
        fun pt(id: Int): Point? = kp[id]?.takeIf { it.confidence >= MIN_KEYPOINT_CONFIDENCE }?.let { Point(it.x, it.y) }

        val lHip = pt(PoseEstimationModule.LEFT_HIP); val rHip = pt(PoseEstimationModule.RIGHT_HIP)
        val lKnee = pt(PoseEstimationModule.LEFT_KNEE); val rKnee = pt(PoseEstimationModule.RIGHT_KNEE)
        val lAnkle = pt(PoseEstimationModule.LEFT_ANKLE); val rAnkle = pt(PoseEstimationModule.RIGHT_ANKLE)
        val lShoulder = pt(PoseEstimationModule.LEFT_SHOULDER); val rShoulder = pt(PoseEstimationModule.RIGHT_SHOULDER)
        val lWrist = pt(PoseEstimationModule.LEFT_WRIST); val rWrist = pt(PoseEstimationModule.RIGHT_WRIST)

        val kneeL = if (lHip != null && lKnee != null && lAnkle != null) angleAt(lHip, lKnee, lAnkle) else 0f
        val kneeR = if (rHip != null && rKnee != null && rAnkle != null) angleAt(rHip, rKnee, rAnkle) else 0f

        val hipAngle = when {
            lShoulder != null && lHip != null && lKnee != null -> angleAt(lShoulder, lHip, lKnee)
            rShoulder != null && rHip != null && rKnee != null -> angleAt(rShoulder, rHip, rKnee)
            else -> 0f
        }

        val torsoLean = if (lShoulder != null && rShoulder != null && lHip != null && rHip != null) {
            val smX = (lShoulder.x + rShoulder.x) / 2f; val smY = (lShoulder.y + rShoulder.y) / 2f
            val hmX = (lHip.x + rHip.x) / 2f; val hmY = (lHip.y + rHip.y) / 2f
            leanAngleDeg(smX, smY, hmX, hmY)
        } else 0f

        val shoulderTilt = if (lShoulder != null && rShoulder != null)
            tiltAngleDeg(lShoulder.x, lShoulder.y, rShoulder.x, rShoulder.y) else 0f

        val stanceRatio = if (lAnkle != null && rAnkle != null && lHip != null && rHip != null) {
            val ankleDist = distance(lAnkle.x, lAnkle.y, rAnkle.x, rAnkle.y)
            val hipDist = distance(lHip.x, lHip.y, rHip.x, rHip.y)
            if (hipDist > 0f) ankleDist / hipDist else 0f
        } else 0f

        val handsForward = if ((lWrist != null || rWrist != null) && lHip != null && rHip != null && r.personHeightPx > 0f) {
            val meanWristX = listOfNotNull(lWrist?.x, rWrist?.x).average().toFloat()
            val hipCenterX = (lHip.x + rHip.x) / 2f
            val diffPx = (meanWristX - hipCenterX) * r.frameW
            (diffPx / r.personHeightPx) * dirSign
        } else 0f

        return PoseSample(
            timestampMs = tMs,
            kneeAngleL = kneeL,
            kneeAngleR = kneeR,
            hipAngle = hipAngle,
            torsoLeanDeg = torsoLean,
            shoulderTiltDeg = shoulderTilt,
            stanceWidthRatio = stanceRatio,
            handsForward = handsForward,
            personHeightPx = r.personHeightPx,
            confidence = r.skeleton.confidence,
            cropRect = NormalizedRect(
                r.crop.x / r.frameW, r.crop.y / r.frameH, r.crop.width / r.frameW, r.crop.height / r.frameH
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Key-frame extraction (JPEG crops for a language-model prompt / UI)
    // ─────────────────────────────────────────────────────────────────────

    suspend fun extractKeyFrames(
        clipPath: String,
        refs: List<KeyFrameRef>,
        maxSide: Int = 1024,
        jpegQuality: Int = 85
    ): List<KeyFrame> = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(clipPath)
            val out = mutableListOf<KeyFrame>()

            for (ref in refs) {
                coroutineContext.ensureActive()
                val bitmap = retriever.getScaledFrameAtTime(
                    ref.timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, KEYFRAME_DECODE_BOX, KEYFRAME_DECODE_BOX
                ) ?: continue
                val cropped = cropAndScale(bitmap, ref.cropRect, maxSide)
                out += KeyFrame(ref.timestampMs, ref.reason, jpegEncode(cropped, jpegQuality), cropped = true)
                if (cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
            }

            val midRef = refs.getOrNull(refs.size / 2) ?: refs.firstOrNull()
            if (midRef != null) {
                coroutineContext.ensureActive()
                val bitmap = retriever.getScaledFrameAtTime(
                    midRef.timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, KEYFRAME_DECODE_BOX, KEYFRAME_DECODE_BOX
                )
                if (bitmap != null) {
                    val scaled = scaleToMaxSide(bitmap, maxSide)
                    out += KeyFrame(midRef.timestampMs, "full-frame context", jpegEncode(scaled, jpegQuality), cropped = false)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                }
            }
            out
        } finally {
            retriever.release()
        }
    }

    private fun cropAndScale(bitmap: Bitmap, cropRect: NormalizedRect?, maxSide: Int): Bitmap {
        if (cropRect == null) return scaleToMaxSide(bitmap, maxSide)
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val cx = (cropRect.x + cropRect.width / 2f) * w
        val cy = (cropRect.y + cropRect.height / 2f) * h
        val personHeightPx = cropRect.height * h
        val side = max(3f * personHeightPx, 512f)
        val minSide = min(512f, min(w, h))
        val rect = clampSquareCrop(cx, cy, side, w, h, minSide)
        val cropped = Bitmap.createBitmap(bitmap, rect.x.toInt(), rect.y.toInt(), rect.width.toInt(), rect.height.toInt())
        return scaleToMaxSide(cropped, maxSide)
    }

    private fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
    }

    private fun jpegEncode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
