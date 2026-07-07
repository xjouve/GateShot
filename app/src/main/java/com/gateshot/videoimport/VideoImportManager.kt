package com.gateshot.videoimport

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.session.CreateSessionRequest
import com.gateshot.session.SessionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports videos recorded outside GateShot (native camera app) into
 * GateShot's own storage so every downstream consumer keeps working
 * unchanged: sidecar files (`<clip>.gates`) land next to the mp4, the
 * session DB records a MediaEntity row, ExoPlayer/MediaMetadataRetriever
 * get plain file paths.
 *
 * Videos are COPIED, not referenced: Photo Picker URI grants don't survive
 * process death, and the analysis pipeline is file-path based throughout.
 * The Library's delete action is the pressure valve for the duplicated
 * storage.
 */
@Singleton
class VideoImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val endpointRegistry: EndpointRegistry,
    private val eventBus: EventBus
) {

    /** Copies each URI into GateShot/videos and records it in the session DB. */
    suspend fun import(uris: List<Uri>): List<File> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()
        ensureSessionAndRun()
        uris.mapNotNull { uri ->
            try {
                importOne(uri)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import failed for $uri: ${e.message}")
                null
            }
        }
    }

    private suspend fun importOne(uri: Uri): File? {
        val resolver = context.contentResolver
        var displayName: String? = null
        var dateTakenMs: Long? = null

        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                if (dateIdx >= 0 && !cursor.isNull(dateIdx)) dateTakenMs = cursor.getLong(dateIdx)
            }
        }

        val dir = File(context.getExternalFilesDir(null), "GateShot/videos").apply { mkdirs() }
        val target = dedupe(dir, sanitize(displayName))

        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        if (target.length() == 0L) {
            target.delete()
            return null
        }

        // Library and Replay sort by lastModified — stamp it with the capture
        // time so ordering reflects when the run was skied, not imported.
        dateTakenMs?.takeIf { it > 0 }?.let { target.setLastModified(it) }

        // SessionFeatureModule records the MediaEntity row off this event.
        eventBus.publish(
            AppEvent.NativeCaptureCompleted(
                fileUri = "file://${target.absolutePath}",
                isVideo = true
            )
        )
        return target
    }

    /**
     * The session module's media sink silently drops events without an
     * active session AND run, so guarantee both before importing.
     */
    private suspend fun ensureSessionAndRun() {
        val info = endpointRegistry
            .call<Unit, SessionInfo?>("session/current", Unit)
            .dataOrNull()
        if (info == null) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            // session/create auto-starts run 1
            endpointRegistry.call<CreateSessionRequest, Any>(
                "session/create",
                CreateSessionRequest(eventName = "Imported $today", discipline = "")
            )
        } else if (info.activeRunNumber == null) {
            endpointRegistry.call<Unit, Any>("session/run/start", Unit)
        }
    }

    private fun sanitize(displayName: String?): String {
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: "imported_${System.currentTimeMillis()}.mp4"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.endsWith(".mp4", ignoreCase = true)) safe else "$safe.mp4"
    }

    private fun dedupe(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$i.$ext")
            i++
        }
        return candidate
    }

    companion object {
        private const val TAG = "VideoImport"
    }
}
