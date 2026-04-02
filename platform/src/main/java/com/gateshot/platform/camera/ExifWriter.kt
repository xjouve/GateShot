package com.gateshot.platform.camera

import android.content.ContentValues
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gateshot.platform.sensor.GpsLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EXIF metadata writer and MediaStore integration.
 *
 * Writes capture metadata (GPS, exposure, ISO, orientation, camera model)
 * to JPEG files and registers them with the Android MediaStore so they
 * appear in the system gallery and other apps.
 *
 * EXIF tags written:
 * - GPS coordinates (latitude, longitude, altitude)
 * - Exposure time (1/1000 format)
 * - ISO sensitivity
 * - Focal length (physical mm)
 * - F-number (aperture)
 * - Flash status
 * - Camera make/model
 * - Software (GateShot version)
 * - Orientation
 * - Date/time
 * - Image dimensions
 * - Compass heading (from magnetometer)
 */
@Singleton
class ExifWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val CAMERA_MAKE = "OPPO"
        private const val CAMERA_MODEL = "Find X9 Pro"
        private const val SOFTWARE = "GateShot"
    }

    /**
     * Write EXIF metadata to a JPEG file.
     */
    fun writeExif(
        filePath: String,
        metadata: CaptureMetadata?,
        gps: GpsLocation? = null,
        compassHeading: Float? = null,
        lensDescription: String? = null
    ) {
        if (!filePath.endsWith(".jpg", ignoreCase = true) &&
            !filePath.endsWith(".jpeg", ignoreCase = true)) return

        try {
            val exif = ExifInterface(filePath)

            // Camera identification
            exif.setAttribute(ExifInterface.TAG_MAKE, CAMERA_MAKE)
            exif.setAttribute(ExifInterface.TAG_MODEL, CAMERA_MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)

            // Lens description (e.g., "70mm f/2.1 Telephoto + Hasselblad 3.28x")
            lensDescription?.let {
                exif.setAttribute(ExifInterface.TAG_LENS_MAKE, "Hasselblad")
                exif.setAttribute(ExifInterface.TAG_LENS_MODEL, it)
            }

            // Date/time
            val dateFormat = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            val dateString = dateFormat.format(java.util.Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateString)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)

            // Capture metadata
            if (metadata != null) {
                // Exposure time as rational (e.g., 1/1000)
                if (metadata.exposureTimeNs > 0) {
                    val exposureSec = metadata.exposureTimeNs / 1_000_000_000.0
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureSec.toString())
                }

                // ISO
                if (metadata.sensitivity > 0) {
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                        metadata.sensitivity.toString())
                }

                // Focal length as rational (e.g., 12.5mm physical = "125/10")
                if (metadata.focalLengthMm > 0) {
                    val numerator = (metadata.focalLengthMm * 10).toInt()
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "$numerator/10")
                }

                // Aperture (f-number)
                if (metadata.aperture > 0) {
                    val numerator = (metadata.aperture * 10).toInt()
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, "$numerator/10")
                }

                // Flash
                exif.setAttribute(ExifInterface.TAG_FLASH,
                    if (metadata.flashFired) "1" else "0")
            }

            // GPS coordinates
            if (gps != null) {
                exif.setLatLong(gps.latitude, gps.longitude)

                if (gps.altitude != 0.0) {
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE,
                        "${kotlin.math.abs(gps.altitude).toInt()}/1")
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF,
                        if (gps.altitude >= 0) "0" else "1")
                }
            }

            // Compass heading (GPS direction)
            if (compassHeading != null) {
                val heading = ((compassHeading % 360) + 360) % 360
                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION,
                    "${(heading * 100).toInt()}/100")
                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
            }

            // Orientation
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString())

            exif.saveAttributes()
        } catch (_: Exception) { }
    }

    /**
     * Register a media file with the Android MediaStore.
     * This makes it visible in the system gallery, Google Photos, etc.
     */
    fun registerWithMediaStore(
        filePath: String,
        mimeType: String = "image/jpeg",
        displayName: String? = null
    ): Uri? {
        val file = File(filePath)
        if (!file.exists()) return null

        val name = displayName ?: file.nameWithoutExtension

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.SIZE, file.length())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/GateShot")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, contentValues) ?: return null

        try {
            // Copy file content to MediaStore
            resolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Mark as no longer pending
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }

            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    /**
     * Register a video file with metadata.
     */
    fun registerVideoWithMediaStore(
        filePath: String,
        durationMs: Long,
        gps: GpsLocation? = null
    ): Uri? {
        val file = File(filePath)
        if (!file.exists()) return null

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.nameWithoutExtension)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.SIZE, file.length())
            put(MediaStore.Video.Media.DURATION, durationMs)

            gps?.let {
                put(MediaStore.Video.Media.LATITUDE, it.latitude)
                put(MediaStore.Video.Media.LONGITUDE, it.longitude)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/GateShot")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, contentValues) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }

            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }
}
