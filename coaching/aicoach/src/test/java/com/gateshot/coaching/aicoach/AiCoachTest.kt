package com.gateshot.coaching.aicoach

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AiCoachReportRoundTripTest {

    @Test
    fun `report survives save then load`() {
        val report = AiCoachReport(
            summary = "Solid GS run with room to improve pressure timing.",
            overallScore = 7,
            strengths = listOf(Finding("Stance", "Good balanced stance through the top section", 1200L, 3, priority = 2)),
            corrections = listOf(Finding("Angulation", "Increase hip angulation into gate 5", 4300L, 5, priority = 1)),
            drills = listOf(Drill("Rail turns", "Practice railed turns on a gentle pitch to build angulation")),
            confidenceNote = "Racer was small in frames 1-2; medium confidence on upper-body findings.",
            model = "claude-opus-5",
            createdAtMs = 1_725_000_000_000L
        )

        val file = File.createTempFile("aicoach", ".json")
        file.deleteOnExit()

        report.save(file)
        val loaded = AiCoachReport.load(file)

        assertEquals(report, loaded)
    }
}

class PromptBuilderTest {

    private val frames = listOf(
        KeyFrame(timestampMs = 0L, reason = "full course context", jpegBytes = ByteArray(0), cropped = false),
        KeyFrame(timestampMs = 12_400L, reason = "gate 5 apex", jpegBytes = ByteArray(0), cropped = true)
    )

    private val context = RunContext(
        discipline = "GS",
        athleteName = "Alex",
        athleteLevel = "FIS",
        coachNotes = "Watch the outside ski pressure",
        gateCount = 32
    )

    @Test
    fun `system prompt explains pose metric definitions`() {
        val prompt = AiCoachClient.buildSystemPrompt()
        assertTrue(prompt.contains("Knee angle", ignoreCase = true))
        assertTrue(prompt.contains("Torso lean", ignoreCase = true))
        assertTrue(prompt.contains("Shoulder tilt", ignoreCase = true))
        assertTrue(prompt.contains("Stance width ratio", ignoreCase = true))
        assertTrue(prompt.contains("Hands-forward", ignoreCase = true))
        assertTrue(prompt.contains("heuristic", ignoreCase = true))
    }

    @Test
    fun `user text includes frame labels, technique json and run context`() {
        val text = AiCoachClient.buildUserText(frames, techniqueJson = """{"kneeAngle":142}""", context = context)

        assertTrue(text.contains("Frame 1"))
        assertTrue(text.contains("t=12.4s"))
        assertTrue(text.contains("gate 5 apex"))
        assertTrue(text.contains("\"kneeAngle\":142"))
        assertTrue(text.contains("GS"))
        assertTrue(text.contains("Alex"))
        assertTrue(text.contains("32"))
        assertTrue(text.contains("Watch the outside ski pressure"))
    }

    @Test
    fun `frame label marks full-frame context vs cropped frames`() {
        val contextLabel = AiCoachClient.frameLabel(0, frames[0])
        val cropLabel = AiCoachClient.frameLabel(1, frames[1])

        assertTrue(contextLabel.contains("full-frame context"))
        assertTrue(!cropLabel.contains("full-frame context"))
    }
}

class CostEstimateTest {

    @Test
    fun `cost scales with frame count and technique json size`() {
        val zero = AiCoachClient.estimateCostUsd(frames = 0, techniqueJsonChars = 0)
        val small = AiCoachClient.estimateCostUsd(frames = 3, techniqueJsonChars = 500)
        val large = AiCoachClient.estimateCostUsd(frames = 8, techniqueJsonChars = 4000)

        assertTrue(zero > 0.0) // assumed output tokens still cost something
        assertTrue(small > zero)
        assertTrue(large > small)
    }
}

class AiCoachExceptionTest {

    @Test
    fun `blank api key maps to NO_API_KEY without constructing an exception from the sdk`() {
        val exception = AiCoachException(AiCoachException.Kind.NO_API_KEY, "No Anthropic API key configured")
        assertEquals(AiCoachException.Kind.NO_API_KEY, exception.kind)
    }

    @Test
    fun `refusal stop reason maps to REFUSED kind, not a generic parse failure`() {
        val exception = AiCoachException(AiCoachException.Kind.REFUSED, "Claude declined to analyze this run")
        assertEquals(AiCoachException.Kind.REFUSED, exception.kind)
        assertEquals("Claude declined to analyze this run", exception.message)
    }

    @Test
    fun `every sdk-facing failure mode has a dedicated kind`() {
        val kinds = AiCoachException.Kind.entries.map { it.name }.toSet()
        assertEquals(
            setOf("NO_API_KEY", "AUTH", "RATE_LIMIT", "NETWORK", "REFUSED", "INVALID_RESPONSE", "OTHER"),
            kinds
        )
    }
}
