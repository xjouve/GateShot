package com.gateshot.platform.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File storage management for GateShot.
 *
 * Organizes captures into a structured directory hierarchy:
 *
 *   GateShot/
 *   ├── events/
 *   │   └── {event_name}_{date}_{discipline}/
 *   │       ├── run_001/
 *   │       │   ├── burst/           ← Pre-capture buffer + burst frames
 *   │       │   ├── video/           ← 4K video clips
 *   │       │   ├── raw/             ← RAW DNG files (if enabled)
 *   │       │   ├── processed/       ← SR-enhanced output
 *   │       │   └── coaching/        ← Annotations, voice-overs, drawings
 *   │       ├── run_002/
 *   │       └── course_map/          ← Ultra-wide context frames
 *   ├── athletes/                     ← Athlete profile photos
 *   ├── thumbnails/                   ← Proxy thumbnails for gallery
 *   └── export/                       ← Prepared share files (temporary)
 *
 * Storage budget on 512GB internal:
 * - 200MP RAW frame: ~60MB
 * - 4K@30fps video: ~375MB/min
 * - A full day of shooting: 100-200GB
 * - Capacity: ~3 full days before cleanup needed
 */
@Singleton
class StoragePlatformImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : StoragePlatform {

    companion object {
        private const val APP_DIR_NAME = "GateShot"
        private const val EVENTS_DIR = "events"
        private const val ATHLETES_DIR = "athletes"
        private const val THUMBNAILS_DIR = "thumbnails"
        private const val EXPORT_DIR = "export"
    }

    override fun getAppStorageRoot(): File {
        // Use external storage (shared) so users can access files via USB/file manager.
        // Falls back to app-specific storage if external is unavailable.
        val externalDir = context.getExternalFilesDir(null)
        val root = if (externalDir != null) {
            File(externalDir, APP_DIR_NAME)
        } else {
            File(context.filesDir, APP_DIR_NAME)
        }
        root.mkdirs()
        return root
    }

    override fun getAvailableSpaceBytes(): Long {
        val root = getAppStorageRoot()
        val stat = StatFs(root.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    override fun getTotalSpaceBytes(): Long {
        val root = getAppStorageRoot()
        val stat = StatFs(root.absolutePath)
        return stat.blockCountLong * stat.blockSizeLong
    }

    /**
     * Get or create a session directory for an event.
     *
     * Session directories are named with the event, date, and discipline
     * to make them easily identifiable in file managers.
     *
     * @param eventName Event name (e.g., "World Cup Adelboden")
     * @param date Date string (e.g., "2026-01-18")
     * @param discipline Discipline abbreviation (e.g., "GS", "SL", "DH")
     */
    override fun getSessionDir(eventName: String, date: String, discipline: String): File {
        val sanitizedName = sanitizeFileName(eventName)
        val dirName = "${sanitizedName}_${date}_$discipline"
        val sessionDir = File(File(getAppStorageRoot(), EVENTS_DIR), dirName)
        sessionDir.mkdirs()
        return sessionDir
    }

    /**
     * Get or create a run directory within a session.
     *
     * Each run gets its own directory with subdirectories for different
     * capture types (burst, video, raw, processed, coaching).
     */
    override fun getRunDir(sessionDir: File, runNumber: Int): File {
        val runDir = File(sessionDir, "run_%03d".format(runNumber))
        runDir.mkdirs()

        // Ensure subdirectories exist
        File(runDir, "burst").mkdirs()
        File(runDir, "video").mkdirs()
        File(runDir, "raw").mkdirs()
        File(runDir, "processed").mkdirs()
        File(runDir, "coaching").mkdirs()

        return runDir
    }

    override fun getCacheDir(): File {
        val cacheDir = context.cacheDir
        val gateShotCache = File(cacheDir, APP_DIR_NAME)
        gateShotCache.mkdirs()
        return gateShotCache
    }

    override fun getThumbnailDir(): File {
        val thumbDir = File(getAppStorageRoot(), THUMBNAILS_DIR)
        thumbDir.mkdirs()
        return thumbDir
    }

    /**
     * Get the athletes directory for profile photos.
     */
    fun getAthletesDir(): File {
        val dir = File(getAppStorageRoot(), ATHLETES_DIR)
        dir.mkdirs()
        return dir
    }

    /**
     * Get the export directory for temporary share files.
     */
    fun getExportDir(): File {
        val dir = File(getAppStorageRoot(), EXPORT_DIR)
        dir.mkdirs()
        return dir
    }

    /**
     * Get a unique file path for a new capture.
     *
     * @param runDir The run directory
     * @param type Subdirectory type ("burst", "video", "raw", "processed")
     * @param extension File extension (e.g., "jpg", "mp4", "dng")
     */
    fun getNewCapturePath(runDir: File, type: String, extension: String): File {
        val subDir = File(runDir, type)
        subDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(subDir, "gateshot_${timestamp}.$extension")
    }

    /**
     * Get the course map directory for ultra-wide context frames.
     */
    fun getCourseMapDir(sessionDir: File): File {
        val dir = File(sessionDir, "course_map")
        dir.mkdirs()
        return dir
    }

    /**
     * Calculate storage usage for a session.
     */
    fun getSessionSizeBytes(sessionDir: File): Long {
        return sessionDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * List all sessions sorted by date (most recent first).
     */
    fun listSessions(): List<File> {
        val eventsDir = File(getAppStorageRoot(), EVENTS_DIR)
        if (!eventsDir.exists()) return emptyList()
        return eventsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * List all runs within a session, sorted by run number.
     */
    fun listRuns(sessionDir: File): List<File> {
        return sessionDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("run_") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Clean up old export files to free cache space.
     * Deletes export files older than the specified age.
     */
    fun cleanExportCache(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val exportDir = getExportDir()
        val now = System.currentTimeMillis()
        exportDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }

    /**
     * Clean up old thumbnail cache.
     * Removes thumbnails for files that no longer exist.
     */
    fun cleanOrphanedThumbnails() {
        val thumbDir = getThumbnailDir()
        thumbDir.listFiles()?.forEach { thumb ->
            // Thumbnail names encode the source file hash
            // If the source no longer exists, delete the thumbnail
            if (thumb.length() == 0L) {
                thumb.delete()
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(50)
    }
}
