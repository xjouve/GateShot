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
 * Videos shared into GateShot are copied into app storage because the analysis
 * pipeline uses file paths.
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
        var sourceSizeBytes: Long = 0L

        resolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE
            ),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                if (dateIdx >= 0 && !cursor.isNull(dateIdx)) dateTakenMs = cursor.getLong(dateIdx)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sourceSizeBytes = cursor.getLong(sizeIdx)
            }
        }

        val dir = File(context.getExternalFilesDir(null), "GateShot/videos").apply { mkdirs() }
        val target = dedupe(dir, sanitize(displayName))

        if (!hasEnoughSpace(dir, sourceSizeBytes)) {
            throw java.io.IOException("Not enough free space to import $uri")
        }

        val input = resolver.openInputStream(uri) ?: return null
        var copiedBytes = 0L
        try {
            input.use { stream ->
                target.outputStream().use { output ->
                    copiedBytes = stream.copyTo(output)
                    output.fd.sync()
                }
            }
        } catch (e: Exception) {
            // A partial file must never be left behind for a failed/cancelled copy.
            target.delete()
            throw e
        }

        if (copiedBytes == 0L || target.length() != copiedBytes ||
            (sourceSizeBytes > 0L && target.length() != sourceSizeBytes)
        ) {
            target.delete()
            throw java.io.IOException("Incomplete video copy from $uri")
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

        /** Conservative safety margin left free after the copy, in bytes. */
        const val MIN_FREE_BYTES: Long = 50L * 1024 * 1024 // 50MB
    }
}

/**
 * True when [destDir]'s filesystem has room for [requiredBytes] plus a
 * [minFreeBytes] safety margin. [requiredBytes] may be unknown (<= 0, e.g.
 * the content resolver didn't report `SIZE`) — in that case only the margin
 * is required. Extracted as a standalone function so it's testable without
 * a real Android `File`/`ContentResolver` stack.
 */
internal fun hasEnoughSpace(
    destDir: File,
    requiredBytes: Long,
    minFreeBytes: Long = VideoImportManager.MIN_FREE_BYTES
): Boolean {
    val needed = if (requiredBytes > 0) requiredBytes + minFreeBytes else minFreeBytes
    return destDir.usableSpace >= needed
}
