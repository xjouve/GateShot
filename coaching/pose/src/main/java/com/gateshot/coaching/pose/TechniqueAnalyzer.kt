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
private const val FULL_DECODE_BOX = 1920
private const val KEYFRAME_DECODE_BOX = 1920
private const val SALIENCY_WIDTH = 240
private const val PAN_MAX_SHIFT = 40
private const val SEED_CROP_MULTIPLIER = 3f
private const val SEED_CROP_MIN = 160f
private const val SEED_CROP_MAX = 640f
private const val FALLBACK_EVERY_N_UNTRACKED = 5
private val FALLBACK_CROP_SIDES = floatArrayOf(192f, 320f)
private const val DEFAULT_HEIGHT_ESTIMATE_PX = 120f

// ─────────────────────────────────────────────────────────────────────────
// Public data model
// ─────────────────────────────────────────────────────────────────────────

@Serializable
data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float)

@Serializable
data class PoseSample(
    val timestampMs: Long,
    val kneeAngleL: Float? = null,
    val kneeAngleR: Float? = null,
    val hipAngle: Float? = null,
    val torsoLeanDeg: Float? = null,
    val shoulderTiltDeg: Float? = null,
    val stanceWidthRatio: Float? = null,
    val handsForward: Float? = null,
    val personHeightPx: Float? = null,
    val confidence: Float,
    val cropRect: NormalizedRect?,
    val tracked: Boolean
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

/** Small debug summary of a run; defaulted fields so old sidecars still load with `ignoreUnknownKeys`. */
@Serializable
data class TrackingDebug(
    val searchFallbacks: Int = 0,
    val meanConfidence: Float = 0f,
    val medianPersonHeightPx: Float? = null
)

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
    val keyFrames: List<KeyFrameRef>,
    val debug: TrackingDebug = TrackingDebug()
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

val METRIC_EXTRACTORS: Map<String, (PoseSample) -> Float?> = mapOf(
    "kneeAngleL" to { s: PoseSample -> s.kneeAngleL },
    "kneeAngleR" to { s: PoseSample -> s.kneeAngleR },
    "hipAngle" to { s: PoseSample -> s.hipAngle },
    "torsoLeanDeg" to { s: PoseSample -> s.torsoLeanDeg },
    "shoulderTiltDeg" to { s: PoseSample -> s.shoulderTiltDeg },
    "stanceWidthRatio" to { s: PoseSample -> s.stanceWidthRatio },
    "handsForward" to { s: PoseSample -> s.handsForward }
)

/** A metric is included only when it has >= 5 non-null values among [samples]. */
fun computeAllMetricStats(samples: List<PoseSample>): Map<String, MetricStats> =
    METRIC_EXTRACTORS.mapNotNull { (name, extractor) ->
        val values = samples.mapNotNull(extractor)
        if (values.size < 5) return@mapNotNull null
        val raw = computeStats(values) ?: return@mapNotNull null
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
            "Racer too small or unclear to measure reliably — try footage filmed closer or at higher zoom"
        )
    }

    val kneeMeans = confident.mapNotNull { s ->
        if (s.kneeAngleL != null && s.kneeAngleR != null) (s.kneeAngleL + s.kneeAngleR) / 2f else null
    }
    if (kneeMeans.isNotEmpty()) {
        val meanKnee = kneeMeans.average().toFloat()
        if (meanKnee > 150f) {
            flags += TechniqueFlag(
                "UPRIGHT", 2,
                "Average knee angle is ${round1(meanKnee)}°, suggesting an upright stance with limited flexion."
            )
        }
    }

    for (gate in gateTimestampsMs) {
        val near = confident.filter { abs(it.timestampMs - gate) <= 150 }
        val straight = near.firstOrNull {
            (it.kneeAngleL != null && it.kneeAngleL > 155f) || (it.kneeAngleR != null && it.kneeAngleR > 155f)
        }
        if (straight != null) {
            flags += TechniqueFlag(
                "STRAIGHT_LEGS_AT_GATE", 2,
                "Legs were nearly straight (>155°) within 150ms of a gate passage.",
                straight.timestampMs
            )
        }
    }

    val handsValues = confident.mapNotNull { it.handsForward }
    if (handsValues.isNotEmpty()) {
        val backFraction = handsValues.count { it < 0f }.toFloat() / handsValues.size
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

/**
 * Picks 6-8 key frames from tracked samples: gate passages, notable poses,
 * plus evenly-spaced fallbacks; sorted by time. When no sample is tracked,
 * falls back to evenly-spaced full frames (no crop) from all samples.
 */
fun selectKeyFrames(samples: List<PoseSample>, gateTimestampsMs: List<Long>, minCount: Int = 6, maxCount: Int = 8): List<KeyFrameRef> {
    val confident = samples.filter { it.tracked }
    if (confident.isEmpty()) {
        if (samples.isEmpty()) return emptyList()
        val step = max(1, samples.size / minCount)
        val picked = mutableListOf<KeyFrameRef>()
        var i = 0
        while (i < samples.size && picked.size < maxCount) {
            picked += KeyFrameRef(samples[i].timestampMs, "Sampled frame (untracked)", null)
            i += step
        }
        return picked
    }

    val picked = LinkedHashMap<Long, KeyFrameRef>()
    fun add(sample: PoseSample, reason: String) {
        picked.putIfAbsent(sample.timestampMs, KeyFrameRef(sample.timestampMs, reason, sample.cropRect))
    }

    for (gate in gateTimestampsMs) {
        val nearest = confident.minByOrNull { abs(it.timestampMs - gate) } ?: continue
        add(nearest, "Gate passage near ${gate}ms")
    }

    val kneeSamples = confident.filter { it.kneeAngleL != null && it.kneeAngleR != null }
    kneeSamples.minByOrNull { (it.kneeAngleL!! + it.kneeAngleR!!) / 2f }?.let { add(it, "Deepest knee flexion") }
    kneeSamples.maxByOrNull { (it.kneeAngleL!! + it.kneeAngleR!!) / 2f }?.let { add(it, "Most upright") }
    confident.filter { it.torsoLeanDeg != null }.maxByOrNull { abs(it.torsoLeanDeg!!) }?.let { add(it, "Max torso lean") }

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

private data class PersonBBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

private data class LumaFrame(val data: FloatArray, val lw: Int, val lh: Int)

@Singleton
class TechniqueAnalyzer @Inject constructor(
    private val pose: PoseEstimationModule
) {

    suspend fun analyze(
        clipPath: String,
        gateTimestampsMs: List<Long>,
        sampleIntervalMs: Long = 200,
        initialHint: NormalizedRect? = null,
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

            val timestamps = mutableListOf<Long>()
            var t = 0L
            while (t < durationMs) { timestamps += t; t += sampleIntervalMs }
            val totalSteps = max(1, timestamps.size)

            val samples = mutableListOf<PoseSample>()
            val xHistory = mutableListOf<Float>()
            var track: TrackState? = null
            var prevLuma: LumaFrame? = null
            var consecutiveUntracked = 0
            var searchFallbacks = 0
            val allConfidences = mutableListOf<Float>()
            val trackedHeights = mutableListOf<Float>()

            for ((i, tMs) in timestamps.withIndex()) {
                coroutineContext.ensureActive()
                val bitmap = retriever.getScaledFrameAtTime(
                    tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, FULL_DECODE_BOX, FULL_DECODE_BOX
                )
                if (bitmap == null) {
                    Log.w(TAG, "No frame decoded at ${tMs}ms for $clipPath")
                    samples += untrackedSample(tMs, 0f)
                    allConfidences += 0f
                    track = null
                    consecutiveUntracked++
                    onProgress(((i + 1).toFloat() / totalSteps).coerceIn(0f, 1f))
                    continue
                }

                val w = bitmap.width; val h = bitmap.height
                val luma = downscaleLuma(bitmap, SALIENCY_WIDTH)

                val diffFrame = prevLuma ?: if (i + 1 < timestamps.size) decodeLumaOnly(retriever, timestamps[i + 1]) else null

                val candidates: List<SalientCandidate> = if (diffFrame != null && diffFrame.lw == luma.lw && diffFrame.lh == luma.lh) {
                    val (a, b) = if (prevLuma != null) diffFrame to luma else luma to diffFrame
                    val (dx, dy) = estimatePan(a.data, b.data, luma.lw, luma.lh, PAN_MAX_SHIFT)
                    val diff = alignedAbsDiff(a.data, b.data, luma.lw, luma.lh, dx, dy)
                    val blurred = boxBlur(diff, luma.lw, luma.lh, 2)
                    val energy = thresholdEnergyMap(blurred, 2f)
                    val prevXScaled = track?.let { it.centerXNorm * luma.lw }
                    val prevYScaled = track?.let { it.centerYNorm * luma.lh }
                    findSalientCandidates(energy, luma.lw, luma.lh, prevXScaled, prevYScaled)
                } else emptyList()

                val scaleX = w.toFloat() / luma.lw
                val scaleY = h.toFloat() / luma.lh

                var result: TrackResult? = null

                if (i == 0 && initialHint != null) {
                    val seedX = (initialHint.x + initialHint.width / 2f) * w
                    val seedY = (initialHint.y + initialHint.height / 2f) * h
                    val heightEstimate = (initialHint.height * h).coerceAtLeast(DEFAULT_HEIGHT_ESTIMATE_PX)
                    result = runPoseAtSeed(bitmap, seedX, seedY, heightEstimate, w, h)
                }

                if (result == null && candidates.isNotEmpty()) {
                    val top = candidates.first()
                    val seedX = top.centerX * scaleX
                    val seedY = top.centerY * scaleY
                    val heightEstimate = (top.height * scaleY).coerceAtLeast(24f)
                    result = runPoseAtSeed(bitmap, seedX, seedY, heightEstimate, w, h)
                } else if (result == null && candidates.isEmpty() && track != null) {
                    val seedX = track.centerXNorm * w
                    val seedY = track.centerYNorm * h
                    result = runPoseAtSeed(bitmap, seedX, seedY, track.personHeightPx, w, h)
                }

                if (result == null) {
                    consecutiveUntracked++
                    if (consecutiveUntracked % FALLBACK_EVERY_N_UNTRACKED == 0 && candidates.isNotEmpty()) {
                        searchFallbacks++
                        result = searchAroundCandidates(bitmap, candidates, scaleX, scaleY, w, h)
                    }
                }

                if (result != null) {
                    consecutiveUntracked = 0
                    xHistory += result.hipCenterXNorm
                    if (xHistory.size > 8) xHistory.removeAt(0)
                    val dirSign = travelDirectionSign(xHistory)
                    val sample = buildSample(tMs, result, dirSign)
                    samples += sample
                    allConfidences += sample.confidence
                    sample.personHeightPx?.let { trackedHeights += it }
                    track = TrackState(result.bboxCenterXNorm, result.bboxCenterYNorm, result.personHeightPx)
                    Log.d(
                        TAG,
                        "t=${tMs}ms seed=(${(result.bboxCenterXNorm * w).toInt()},${(result.bboxCenterYNorm * h).toInt()}) " +
                            "height=${result.personHeightPx.toInt()} conf=${sample.confidence} tracked=true"
                    )
                } else {
                    samples += untrackedSample(tMs, 0f)
                    allConfidences += 0f
                    track = null
                    Log.d(TAG, "t=${tMs}ms seed=none height=none conf=0.0 tracked=false")
                }

                prevLuma = luma
                bitmap.recycle()
                onProgress(((i + 1).toFloat() / totalSteps).coerceIn(0f, 1f))
            }
            onProgress(1f)

            val tracked = samples.filter { it.tracked }
            val trackedFraction = if (samples.isNotEmpty()) tracked.size.toFloat() / samples.size else 0f
            val metricStats = computeAllMetricStats(tracked)
            val segments = buildGateSegments(tracked, gateTimestampsMs)
            val flags = buildFlags(tracked, samples, gateTimestampsMs, trackedFraction, metricStats)
            val keyFrames = selectKeyFrames(samples, gateTimestampsMs)
            val meanConfidence = if (allConfidences.isNotEmpty()) allConfidences.average().toFloat() else 0f
            val medianHeight = if (trackedHeights.isNotEmpty()) percentile(trackedHeights.sorted(), 50f) else null
            val debug = TrackingDebug(searchFallbacks, meanConfidence, medianHeight)

            Log.i(
                TAG,
                "TechniqueAnalyzer run: $clipPath samples=${samples.size} tracked=${tracked.size} " +
                    "trackedFraction=$trackedFraction searchFallbacks=$searchFallbacks meanConfidence=$meanConfidence medianHeightPx=$medianHeight"
            )

            TechniqueReport(
                clipPath = clipPath,
                durationMs = durationMs,
                sampleIntervalMs = sampleIntervalMs,
                samples = samples,
                trackedFraction = trackedFraction,
                metricStats = metricStats,
                gateSegments = segments,
                flags = flags,
                keyFrames = keyFrames,
                debug = debug
            )
        } finally {
            retriever.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Motion-saliency racer localization
    // ─────────────────────────────────────────────────────────────────────

    private fun downscaleLuma(bitmap: Bitmap, targetWidth: Int): LumaFrame {
        val w = bitmap.width; val h = bitmap.height
        val lw = min(targetWidth, w)
        val lh = max(1, Math.round(h.toFloat() * lw / w))
        val scaled = Bitmap.createScaledBitmap(bitmap, lw, lh, true)
        val pixels = IntArray(lw * lh)
        scaled.getPixels(pixels, 0, lw, 0, 0, lw, lh)
        if (scaled !== bitmap) scaled.recycle()
        val data = FloatArray(lw * lh)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            data[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return LumaFrame(data, lw, lh)
    }

    private fun decodeLumaOnly(retriever: MediaMetadataRetriever, tMs: Long): LumaFrame? {
        val bitmap = retriever.getScaledFrameAtTime(
            tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, FULL_DECODE_BOX, FULL_DECODE_BOX
        ) ?: return null
        val frame = downscaleLuma(bitmap, SALIENCY_WIDTH)
        bitmap.recycle()
        return frame
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pose on a seed, with retries and strict validity
    // ─────────────────────────────────────────────────────────────────────

    private fun buildValidityInput(skeleton: SkeletonData, crop: CropRect): Pair<ValidityInput, PersonBBox>? {
        val kp = skeleton.keypoints.associateBy { it.id }
        fun conf(id: Int) = kp[id]?.confidence ?: 0f
        val ids = intArrayOf(
            PoseEstimationModule.LEFT_SHOULDER, PoseEstimationModule.RIGHT_SHOULDER,
            PoseEstimationModule.LEFT_HIP, PoseEstimationModule.RIGHT_HIP,
            PoseEstimationModule.LEFT_KNEE, PoseEstimationModule.RIGHT_KNEE,
            PoseEstimationModule.LEFT_ANKLE, PoseEstimationModule.RIGHT_ANKLE
        )
        val xs = mutableListOf<Float>(); val ys = mutableListOf<Float>()
        for (id in ids) {
            val k = kp[id]
            if (k != null && k.confidence >= MIN_TRACK_CONFIDENCE) {
                xs += crop.x + k.x * crop.width
                ys += crop.y + k.y * crop.height
            }
        }
        if (xs.isEmpty()) return null
        val heightPx = ys.max() - ys.min()
        val widthPx = (xs.max() - xs.min()).let { if (it <= 0f) 1f else it }
        val v = ValidityInput(
            overallConfidence = skeleton.confidence,
            leftHipConf = conf(PoseEstimationModule.LEFT_HIP),
            rightHipConf = conf(PoseEstimationModule.RIGHT_HIP),
            leftKneeConf = conf(PoseEstimationModule.LEFT_KNEE),
            rightKneeConf = conf(PoseEstimationModule.RIGHT_KNEE),
            leftAnkleConf = conf(PoseEstimationModule.LEFT_ANKLE),
            rightAnkleConf = conf(PoseEstimationModule.RIGHT_ANKLE),
            heightPx = heightPx,
            widthPx = widthPx
        )
        return v to PersonBBox(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun toTrackResult(skeleton: SkeletonData, crop: CropRect, frameW: Int, frameH: Int, bbox: PersonBBox): TrackResult {
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
        val personHeightPx = bbox.maxY - bbox.minY
        val bboxCx = ((bbox.minX + bbox.maxX) / 2f) / frameW
        val bboxCy = ((bbox.minY + bbox.maxY) / 2f) / frameH
        return TrackResult(mapped, crop, frameW, frameH, personHeightPx, bboxCx, bboxCy, hipCenterX)
    }

    private fun runPoseAtSeed(bitmap: Bitmap, seedX: Float, seedY: Float, heightEstimate: Float, w: Int, h: Int): TrackResult? {
        val baseSide = (SEED_CROP_MULTIPLIER * heightEstimate).coerceIn(SEED_CROP_MIN, SEED_CROP_MAX)
        val sides = floatArrayOf(baseSide, baseSide * 1.5f, baseSide * 0.7f)
        for (side in sides) {
            val minSide = min(side, min(w, h).toFloat())
            val crop = clampSquareCrop(seedX, seedY, side, w.toFloat(), h.toFloat(), minSide)
            val cropBitmap = Bitmap.createBitmap(bitmap, crop.x.toInt(), crop.y.toInt(), crop.width.toInt(), crop.height.toInt())
            val skeleton = pose.estimatePoseFromBitmap(cropBitmap)
            cropBitmap.recycle()
            val vp = buildValidityInput(skeleton, crop) ?: continue
            if (isValidTrack(vp.first)) return toTrackResult(skeleton, crop, w, h, vp.second)
        }
        return null
    }

    /** Fallback: full-res crops of 192/320px around the saliency's top-3 candidates — not a blind grid. */
    private fun searchAroundCandidates(
        bitmap: Bitmap, candidates: List<SalientCandidate>, scaleX: Float, scaleY: Float, w: Int, h: Int
    ): TrackResult? {
        for (c in candidates) {
            val cx = c.centerX * scaleX
            val cy = c.centerY * scaleY
            for (side in FALLBACK_CROP_SIDES) {
                val minSide = min(side, min(w, h).toFloat())
                val crop = clampSquareCrop(cx, cy, side, w.toFloat(), h.toFloat(), minSide)
                val cropBitmap = Bitmap.createBitmap(bitmap, crop.x.toInt(), crop.y.toInt(), crop.width.toInt(), crop.height.toInt())
                val skeleton = pose.estimatePoseFromBitmap(cropBitmap)
                cropBitmap.recycle()
                val vp = buildValidityInput(skeleton, crop) ?: continue
                if (isValidTrack(vp.first)) return toTrackResult(skeleton, crop, w, h, vp.second)
            }
        }
        return null
    }

    private fun untrackedSample(tMs: Long, confidence: Float): PoseSample = PoseSample(
        timestampMs = tMs,
        kneeAngleL = null, kneeAngleR = null, hipAngle = null,
        torsoLeanDeg = null, shoulderTiltDeg = null, stanceWidthRatio = null, handsForward = null,
        personHeightPx = null, confidence = confidence, cropRect = null, tracked = false
    )

    private fun buildSample(tMs: Long, r: TrackResult, dirSign: Float): PoseSample {
        val kp = r.skeleton.keypoints.associateBy { it.id }
        // Keypoints are normalized [0,1] to the full frame; angle/distance math must run in
        // PIXEL space (x*frameW, y*frameH) — on a non-square (9:16 or 16:9) frame, normalized
        // coordinates are anisotropically scaled and distort angles (a 90° knee can read as
        // 60° or 120°). Only keypoints at or above MIN_TRACK_CONFIDENCE feed any metric.
        fun pt(id: Int): Point? = kp[id]?.takeIf { it.confidence >= MIN_TRACK_CONFIDENCE }
            ?.let { Point(it.x * r.frameW, it.y * r.frameH) }

        val lHip = pt(PoseEstimationModule.LEFT_HIP); val rHip = pt(PoseEstimationModule.RIGHT_HIP)
        val lKnee = pt(PoseEstimationModule.LEFT_KNEE); val rKnee = pt(PoseEstimationModule.RIGHT_KNEE)
        val lAnkle = pt(PoseEstimationModule.LEFT_ANKLE); val rAnkle = pt(PoseEstimationModule.RIGHT_ANKLE)
        val lShoulder = pt(PoseEstimationModule.LEFT_SHOULDER); val rShoulder = pt(PoseEstimationModule.RIGHT_SHOULDER)
        val lWrist = pt(PoseEstimationModule.LEFT_WRIST); val rWrist = pt(PoseEstimationModule.RIGHT_WRIST)

        val kneeL = angleOrNull(lHip, lKnee, lAnkle)
        val kneeR = angleOrNull(rHip, rKnee, rAnkle)
        val hipAngle = angleOrNull(lShoulder, lHip, lKnee) ?: angleOrNull(rShoulder, rHip, rKnee)

        val torsoLean = if (lShoulder != null && rShoulder != null && lHip != null && rHip != null) {
            val smX = (lShoulder.x + rShoulder.x) / 2f; val smY = (lShoulder.y + rShoulder.y) / 2f
            val hmX = (lHip.x + rHip.x) / 2f; val hmY = (lHip.y + rHip.y) / 2f
            leanAngleDeg(smX, smY, hmX, hmY)
        } else null

        val shoulderTilt = shoulderTiltOrNull(lShoulder, rShoulder, lHip, rHip)
        val stanceRatio = stanceRatioOrNull(lAnkle, rAnkle, lHip, rHip)
        val handsForward = handsForwardOrNull(lWrist, rWrist, lHip, rHip, r.personHeightPx, dirSign)

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
            ),
            tracked = true
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
