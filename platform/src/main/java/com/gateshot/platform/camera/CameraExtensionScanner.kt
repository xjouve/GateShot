package com.gateshot.platform.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot probe of Camera2 Extensions API (HDR / NIGHT / BOKEH / AUTO / FACE_RETOUCH /
 * EYES_FREE_VIDEOGRAPHY). The key datum is the max JPEG size advertised *per extension*:
 * if Oppo exposes NIGHT or HDR at 8192×6144 on cam 0, that's the missing path to
 * full-sensor HR capture without reverse-engineering vendor keys.
 *
 * Results go to logcat under tag "GateShot-Ext" at INFO level.
 */
@Singleton
class CameraExtensionScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @SuppressLint("NewApi")
    fun scan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "skip: API ${Build.VERSION.SDK_INT} < 31 (CameraExtensionCharacteristics unavailable)")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run {
            Log.w(TAG, "CameraManager unavailable")
            return
        }

        val ids = runCatching { manager.cameraIdList }.getOrElse {
            Log.w(TAG, "cameraIdList failed: ${it.message}")
            return
        }

        Log.i(TAG, "=== Camera2 Extensions probe (device=${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}) ===")
        Log.i(TAG, "cameras found: ${ids.toList()}")

        for (id in ids) {
            probeCamera(manager, id)
        }
        Log.i(TAG, "=== End of probe ===")
    }

    @SuppressLint("NewApi")
    private fun probeCamera(manager: CameraManager, id: String) {
        val base = runCatching { manager.getCameraCharacteristics(id) }.getOrElse {
            Log.w(TAG, "cam[$id] getCameraCharacteristics failed: ${it.message}")
            return
        }
        val facing = when (base.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXT"
            else -> "?"
        }
        val focals = base.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.joinToString(",") { "%.1f".format(it) } ?: "?"
        val physicalIds = base.physicalCameraIds.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "-"

        // Baseline: max JPEG size via standard Camera2 (this is what we've been capped to).
        val baseJpegMax = base.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height } ?: Size(0, 0)

        Log.i(TAG, "cam[$id] facing=$facing focals=[$focals] physical=[$physicalIds] baseJpegMax=${sz(baseJpegMax)}")

        val extChars = runCatching { manager.getCameraExtensionCharacteristics(id) }.getOrElse {
            Log.i(TAG, "  ext: none (${it.javaClass.simpleName}: ${it.message})")
            return
        }

        val supported = runCatching { extChars.supportedExtensions }.getOrElse {
            Log.w(TAG, "  supportedExtensions failed: ${it.message}")
            return
        }

        if (supported.isEmpty()) {
            Log.i(TAG, "  no extensions advertised")
            return
        }

        Log.i(TAG, "  extensions: ${supported.joinToString { extName(it) }}")
        for (ext in supported) {
            val jpegSizes = runCatching { extChars.getExtensionSupportedSizes(ext, ImageFormat.JPEG) }
                .getOrElse { emptyList() }
            val yuvSizes = runCatching { extChars.getExtensionSupportedSizes(ext, ImageFormat.YUV_420_888) }
                .getOrElse { emptyList() }
            val maxJpeg = jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            val maxYuv = yuvSizes.maxByOrNull { it.width.toLong() * it.height }
            val captureLatency = runCatching {
                @Suppress("DEPRECATION")
                extChars.getEstimatedCaptureLatencyRangeMillis(ext, maxJpeg ?: Size(1920, 1080), ImageFormat.JPEG)
            }.getOrNull()
            val unlockHr = maxJpeg != null && baseJpegMax.width > 0 &&
                (maxJpeg.width.toLong() * maxJpeg.height) > (baseJpegMax.width.toLong() * baseJpegMax.height)

            Log.i(
                TAG,
                "    ${extName(ext)}: jpegMax=${sz(maxJpeg)} yuvMax=${sz(maxYuv)} latency=$captureLatency${if (unlockHr) " [UNLOCKS_HR]" else ""}"
            )
        }
    }

    private fun sz(s: Size?): String = s?.let { "${it.width}x${it.height}" } ?: "-"

    private fun extName(code: Int): String = when (code) {
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTOMATIC"
        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
        else -> "EXT_$code"
    }

    companion object {
        private const val TAG = "GateShot-Ext"
    }
}
