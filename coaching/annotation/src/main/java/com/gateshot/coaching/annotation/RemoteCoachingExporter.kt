package com.gateshot.coaching.annotation

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Remote Coaching Exporter — Package annotated clips for async review.
 *
 * Creates a self-contained .gateshot zip package containing:
 * - The video clip
 * - All voice-over audio files (pinned to timeline positions)
 * - All drawing annotations (vector overlays as JSON)
 * - Timing splits
 * - Metadata (athlete, session, gate timestamps)
 *
 * The recipient opens this in GateShot, reviews the clip with all annotations,
 * adds their own annotations, and sends back. Async coaching across time zones.
 */
class RemoteCoachingExporter(private val context: Context) {

    @Serializable
    data class CoachingPackage(
        val version: Int = 1,
        val clipUri: String,
        val athleteName: String = "",
        val bibNumber: Int? = null,
        val sessionName: String = "",
        val discipline: String = "",
        val date: String = "",
        val voiceAnnotations: List<VoiceAnnotation> = emptyList(),
        val drawingAnnotations: List<DrawingAnnotation> = emptyList(),
        val gateTimestamps: List<Long> = emptyList(),
        val splitTimes: List<SplitTimeEntry> = emptyList(),
        val coachNotes: String = "",
        val senderName: String = ""
    )

    @Serializable
    data class SplitTimeEntry(
        val gateNumber: Int,
        val timeMs: Long,
        val deltaMs: Long = 0  // vs reference
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Export an annotated clip as a .gateshot package (zip file).
     */
    fun exportPackage(pkg: CoachingPackage): File {
        val outputFile = File(context.cacheDir, "coaching_${System.currentTimeMillis()}.gateshot")

        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. Metadata JSON
            val metadataJson = json.encodeToString(pkg)
            zos.putNextEntry(ZipEntry("metadata.json"))
            zos.write(metadataJson.toByteArray())
            zos.closeEntry()

            // 2. Video clip
            val videoFile = File(pkg.clipUri)
            if (videoFile.exists()) {
                zos.putNextEntry(ZipEntry("clip${getExtension(pkg.clipUri)}"))
                videoFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 3. Voice-over audio files
            pkg.voiceAnnotations.forEachIndexed { idx, annotation ->
                val audioFile = File(annotation.audioFileUri)
                if (audioFile.exists()) {
                    zos.putNextEntry(ZipEntry("voiceover_${idx}.m4a"))
                    audioFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // 4. Drawing annotations (already in the metadata JSON, but also as standalone)
            if (pkg.drawingAnnotations.isNotEmpty()) {
                val drawingsJson = json.encodeToString(pkg.drawingAnnotations)
                zos.putNextEntry(ZipEntry("drawings.json"))
                zos.write(drawingsJson.toByteArray())
                zos.closeEntry()
            }
        }

        return outputFile
    }

    /**
     * Import a .gateshot package received from another coach.
     */
    fun importPackage(packageFile: File): CoachingPackage? {
        return try {
            val zipFile = java.util.zip.ZipFile(packageFile)
            val metadataEntry = zipFile.getEntry("metadata.json") ?: return null
            val metadataJson = zipFile.getInputStream(metadataEntry).bufferedReader().readText()
            json.decodeFromString<CoachingPackage>(metadataJson)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Share a coaching package via Android share intent.
     */
    fun sharePackage(packageFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            packageFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GateShot Coaching Review")
            putExtra(Intent.EXTRA_TEXT, "Open this .gateshot file in GateShot to review the annotated clip.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(Intent.createChooser(intent, "Share coaching package").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun getExtension(path: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot >= 0) path.substring(dot) else ".mp4"
    }
}
