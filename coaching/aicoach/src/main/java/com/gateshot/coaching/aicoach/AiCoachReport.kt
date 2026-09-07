package com.gateshot.coaching.aicoach

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A single finding (strength or correction) from the AI coach's review of a run.
 *
 * @property priority 1 = high priority, 2 = medium, 3 = low.
 */
@Serializable
data class Finding(
    val title: String,
    val detail: String,
    val timestampMs: Long? = null,
    val gateIndex: Int? = null,
    val priority: Int
)

@Serializable
data class Drill(
    val name: String,
    val description: String
)

/**
 * Structured coaching report produced by [com.gateshot.coaching.aicoach.AiCoachClient].
 * Persisted alongside a clip as `<clip>.aicoach.json`.
 */
@Serializable
data class AiCoachReport(
    val summary: String,
    val overallScore: Int,
    val strengths: List<Finding>,
    val corrections: List<Finding>,
    val drills: List<Drill>,
    val confidenceNote: String,
    val model: String,
    val createdAtMs: Long
) {
    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer(), this))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun load(file: File): AiCoachReport = json.decodeFromString(serializer(), file.readText())
    }
}
