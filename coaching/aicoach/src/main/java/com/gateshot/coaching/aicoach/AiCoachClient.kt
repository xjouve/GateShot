package com.gateshot.coaching.aicoach

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** One sampled video frame handed to the coach for review. */
data class KeyFrame(
    val timestampMs: Long,
    val reason: String,
    val jpegBytes: ByteArray,
    val cropped: Boolean
)

/** Metadata about the run being analyzed, used to steer the coaching prompt. */
data class RunContext(
    val discipline: String?,
    val athleteName: String?,
    val athleteLevel: String?,
    val coachNotes: String?,
    val gateCount: Int
)

/** Error raised by [AiCoachClient]. [kind] lets callers show a targeted message/retry action. */
class AiCoachException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    enum class Kind { NO_API_KEY, AUTH, RATE_LIMIT, NETWORK, REFUSED, INVALID_RESPONSE, OTHER }
}

/**
 * Java-friendly (plain getters/setters, no-arg constructor) mirror of [AiCoachReport], used as the
 * target class for the Anthropic Java SDK's typed structured-output path
 * (`MessageCreateParams.builder().outputConfig(Class)`), which relies on Jackson reflection rather
 * than the kotlinx-serialization model used for on-disk persistence. Mapped to [AiCoachReport] by
 * [AiCoachReportJson.toReport] after the call returns.
 */
class AiCoachReportJson {
    @JsonPropertyDescription("2-4 sentence overall summary of the run's technique")
    var summary: String = ""

    @JsonPropertyDescription("Overall technique score from 1 (needs major work) to 10 (excellent)")
    var overallScore: Int = 0

    @JsonPropertyDescription("Concrete things the athlete is doing well")
    var strengths: MutableList<FindingJson> = mutableListOf()

    @JsonPropertyDescription("Concrete, technique-specific corrections, most impactful first")
    var corrections: MutableList<FindingJson> = mutableListOf()

    @JsonPropertyDescription("Suggested training drills that address the corrections above")
    var drills: MutableList<DrillJson> = mutableListOf()

    @JsonPropertyDescription("Honest note on uncertainty, e.g. if the racer was small, blurred, or partly out of frame")
    var confidenceNote: String = ""
}

class FindingJson {
    var title: String = ""
    var detail: String = ""
    var timestampMs: Long? = null
    var gateIndex: Int? = null

    @JsonPropertyDescription("Priority: 1 = high, 2 = medium, 3 = low")
    var priority: Int = 2
}

class DrillJson {
    var name: String = ""
    var description: String = ""
}

private fun AiCoachReportJson.toReport(model: String, createdAtMs: Long): AiCoachReport = AiCoachReport(
    summary = summary,
    overallScore = overallScore,
    strengths = strengths.map { it.toFinding() },
    corrections = corrections.map { it.toFinding() },
    drills = drills.map { Drill(it.name, it.description) },
    confidenceNote = confidenceNote,
    model = model,
    createdAtMs = createdAtMs
)

private fun FindingJson.toFinding() = Finding(
    title = title,
    detail = detail,
    timestampMs = timestampMs,
    gateIndex = gateIndex,
    priority = priority
)

/**
 * Sends sampled frames plus on-device pose metrics for a single ski run to Claude for expert
 * coaching feedback, returned as a structured [AiCoachReport].
 */
@Singleton
class AiCoachClient @Inject constructor() {

    companion object {
        /** Model id is pinned exactly per Anthropic guidance for this integration - no date suffix. */
        const val MODEL = "claude-opus-5"
        private const val MAX_TOKENS = 4096L

        // Cost model: ~1600 input tokens per 1024px image (Claude vision), $5/M input, $25/M output,
        // assuming ~1500 output tokens for a typical report.
        private const val INPUT_TOKENS_PER_IMAGE = 1600.0
        private const val INPUT_PRICE_PER_MILLION_USD = 5.0
        private const val OUTPUT_PRICE_PER_MILLION_USD = 25.0
        private const val ASSUMED_OUTPUT_TOKENS = 1500.0
        private const val CHARS_PER_TOKEN = 4.0

        /**
         * The system prompt sent with every analysis request. Pulled out as a pure function so it
         * can be unit-tested without a network call.
         */
        fun buildSystemPrompt(): String = """
            You are an expert alpine ski-racing coach analyzing a single timed run from sampled
            video frames plus on-device pose metrics. Give concrete, technique-specific coaching
            covering: stance, angulation/inclination, pressure timing relative to the gate, line,
            upper-body discipline, pole plant, and arm position. Prioritize corrections by impact
            (1 = high, 2 = medium, 3 = low) and tie feedback to specific frames or gates whenever
            you can.

            Pose metric definitions (heuristic - on-device, not ground truth):
            - Knee angle: the angle at the knee formed by the hip-knee-ankle points.
            - Torso lean: the athlete's torso angle measured against true vertical.
            - Shoulder tilt: the deviation of the shoulder line from horizontal.
            - Stance width ratio: lateral distance between feet, normalized by hip width.
            - Hands-forward: how far the hands sit in front of the hips, expressed in person
              heights, as a proxy for pole-plant and upper-body position.
            Any flags derived from these metrics are heuristic thresholds, not certainties.

            The images you receive are: one full-frame context image showing the whole course
            setting, followed by tightly cropped frames around the racer at the listed timestamps.
            Frames may be small, motion-blurred, or partially out of the shot - when that limits
            what you can see, say so plainly rather than guessing with false confidence.

            Respond only with the structured report fields you were given a schema for.
        """.trimIndent()

        /**
         * The user-turn text (frame labels, technique JSON, run context) sent alongside the image
         * blocks. Pulled out as a pure function so tests can assert labels/definitions are present
         * without making a network call.
         */
        fun buildUserText(frames: List<KeyFrame>, techniqueJson: String, context: RunContext): String {
            val sb = StringBuilder()
            sb.appendLine("Sampled frames for this run (frame 1 is full-frame context; the rest are crops around the racer):")
            frames.forEachIndexed { index, frame ->
                sb.appendLine(frameLabel(index, frame))
            }
            sb.appendLine()
            sb.appendLine("On-device pose / technique metrics for this run, as JSON:")
            sb.appendLine(techniqueJson)
            sb.appendLine()
            sb.appendLine("Run context:")
            sb.appendLine("- Discipline: ${context.discipline ?: "unknown"}")
            sb.appendLine("- Athlete: ${context.athleteName ?: "unknown"} (level: ${context.athleteLevel ?: "unknown"})")
            sb.appendLine("- Gate count: ${context.gateCount}")
            context.coachNotes?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- Coach notes: $it") }
            return sb.toString()
        }

        internal fun frameLabel(index: Int, frame: KeyFrame): String {
            val seconds = frame.timestampMs / 1000.0
            val suffix = if (frame.cropped) "" else " (full-frame context)"
            return "Frame ${index + 1} — t=${"%.1f".format(seconds)}s — ${frame.reason}$suffix"
        }

        /**
         * Rough pre-flight cost estimate the UI can show before calling [analyze]. Uses ~1600
         * input tokens per 1024px image plus the technique JSON text, at $5/M input and $25/M
         * output tokens (assuming ~1500 output tokens for a typical report).
         */
        fun estimateCostUsd(frames: Int, techniqueJsonChars: Int): Double {
            val imageInputTokens = frames * INPUT_TOKENS_PER_IMAGE
            val textInputTokens = techniqueJsonChars / CHARS_PER_TOKEN
            val inputTokens = imageInputTokens + textInputTokens
            val inputCost = inputTokens / 1_000_000.0 * INPUT_PRICE_PER_MILLION_USD
            val outputCost = ASSUMED_OUTPUT_TOKENS / 1_000_000.0 * OUTPUT_PRICE_PER_MILLION_USD
            return inputCost + outputCost
        }
    }

    suspend fun analyze(
        apiKey: String,
        frames: List<KeyFrame>,
        techniqueJson: String,
        context: RunContext
    ): AiCoachReport = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw AiCoachException(AiCoachException.Kind.NO_API_KEY, "No Anthropic API key configured")
        }

        try {
            val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

            val contentBlocks = mutableListOf<ContentBlockParam>()
            contentBlocks.add(
                ContentBlockParam.ofText(
                    TextBlockParam.builder().text(buildUserText(frames, techniqueJson, context)).build()
                )
            )
            frames.forEachIndexed { index, frame ->
                contentBlocks.add(
                    ContentBlockParam.ofText(TextBlockParam.builder().text(frameLabel(index, frame)).build())
                )
                val base64 = Base64.getEncoder().encodeToString(frame.jpegBytes)
                contentBlocks.add(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(base64)
                                    .build()
                            )
                            .build()
                    )
                )
            }

            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(buildSystemPrompt())
                .outputConfig(AiCoachReportJson::class.java)
                .addUserMessageOfBlockParams(contentBlocks)
                .build()

            val response = client.messages().create(params)

            val stopReason = response.stopReason().map { it.toString() }.orElse("")
            if (stopReason.equals("refusal", ignoreCase = true)) {
                val explanation = response.stopDetails()
                    .flatMap { it.explanation() }
                    .orElse("Claude declined to analyze this run")
                throw AiCoachException(AiCoachException.Kind.REFUSED, explanation)
            }

            val structuredBlock = response.content().firstOrNull { it.text().isPresent }?.text()?.orElse(null)
                ?: throw AiCoachException(AiCoachException.Kind.INVALID_RESPONSE, "No structured content in response")

            val reportJson: AiCoachReportJson = structuredBlock.text()
                ?: throw AiCoachException(AiCoachException.Kind.INVALID_RESPONSE, "Structured output payload was empty")

            reportJson.toReport(model = MODEL, createdAtMs = System.currentTimeMillis())
        } catch (e: AiCoachException) {
            throw e
        } catch (e: UnauthorizedException) {
            throw AiCoachException(AiCoachException.Kind.AUTH, "Anthropic API key was rejected", e)
        } catch (e: RateLimitException) {
            throw AiCoachException(AiCoachException.Kind.RATE_LIMIT, "Anthropic API rate limit exceeded", e)
        } catch (e: AnthropicIoException) {
            throw AiCoachException(AiCoachException.Kind.NETWORK, "Network error contacting Anthropic API", e)
        } catch (e: AnthropicServiceException) {
            throw AiCoachException(AiCoachException.Kind.OTHER, e.message ?: "Anthropic service error", e)
        } catch (e: Exception) {
            throw AiCoachException(AiCoachException.Kind.OTHER, e.message ?: "Unknown error analyzing run", e)
        }
    }
}
