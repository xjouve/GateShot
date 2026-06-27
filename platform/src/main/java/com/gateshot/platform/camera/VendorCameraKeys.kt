package com.gateshot.platform.camera

import android.hardware.camera2.CaptureRequest

/**
 * Vendor-specific Camera2 keys exposed by the MediaTek Dimensity / Oppo
 * camera HAL on the Find X9 Pro. The public Android SDK does not surface
 * these, so we materialise them at runtime via reflection on the hidden
 * `CaptureRequest.Key(String, Class)` constructor.
 *
 * Tag inventory was harvested from `dumpsys media.camera` on a real device;
 * see `build/qa/vendor_tags.txt` for the full 816-tag catalogue.
 */
@Suppress("UNCHECKED_CAST", "PrivateApi")
object VendorCameraKeys {

    // ── Reflection ──────────────────────────────────────────────────────────

    private val keyCtor by lazy {
        CaptureRequest.Key::class.java
            .getDeclaredConstructor(String::class.java, Class::class.java)
            .apply { isAccessible = true }
    }

    private fun <T> key(name: String, type: Class<T>): CaptureRequest.Key<T> =
        keyCtor.newInstance(name, type) as CaptureRequest.Key<T>

    // ── Periscope flip ──────────────────────────────────────────────────────
    // MediaTek capture-control flip. int32[2] = {hflip, vflip}. Values 0/1.
    // Setting {0,1} flips vertically — the right transform for the Find X9
    // Pro periscope, whose sensor is mounted upside down.
    val FLIP_MODE: CaptureRequest.Key<IntArray> =
        key("com.mediatek.control.capture.flipmode", IntArray::class.java)

    // Per-zoom orientation override. int32 = sibling of FLIP_MODE on the Oppo
    // side; engages when zoom crosses periscope threshold.
    val ZOOM_ORIENTATION: CaptureRequest.Key<Int> =
        key("com.oplus.zoom.orientation", java.lang.Integer::class.java as Class<Int>)

    // ── Stabilization ───────────────────────────────────────────────────────
    // MediaTek granular EIS mode. Numeric mapping is best-effort:
    //   0 = OFF, 1 = STANDARD, 2 = SUPER, 3 = PANNING.
    // Validated by the device accepting the request silently; a value the
    // HAL doesn't support is dropped without crashing.
    val EIS_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.eisfeature.eismode", java.lang.Integer::class.java as Class<Int>)

    val PREVIEW_EIS: CaptureRequest.Key<Int> =
        key("com.mediatek.eisfeature.previeweis", java.lang.Integer::class.java as Class<Int>)

    // Oppo's higher-level video stabilization selector. Wraps OIS+EIS.
    //   0 = OFF, 1 = STANDARD, 2 = ULTRA/MAXIMUM, 3 = PANNING (best guess).
    val OPLUS_VIDEO_STAB_MODE: CaptureRequest.Key<Int> =
        key("com.oplus.video.stabilization.mode", java.lang.Integer::class.java as Class<Int>)

    // Oppo's *separate* video EIS mode — distinct from com.mediatek.eisfeature.eismode.
    // The native camera app sets THIS to engage the Super EIS pipeline.
    //   byte values, best guess: 0 = OFF, 1 = STANDARD, 2 = SUPER, 3 = PANNING.
    val OPLUS_VIDEO_EIS_MODE: CaptureRequest.Key<Byte> =
        key("com.oplus.camera.video.eis.mode", java.lang.Byte::class.java as Class<Byte>)

    // Master EIS engine enable. Setting this to 1 tells the HAL to actually
    // run the EIS pipeline; without it, eismode and video.eis.mode can both
    // be set without any visible stabilization.
    val OPLUS_EIS_WORKON: CaptureRequest.Key<Byte> =
        key("com.oplus.eis.workon", java.lang.Byte::class.java as Class<Byte>)

    // Super-EIS scene selector (int32). The native app picks a scene id
    // matching the user's chosen mode; 1 = generic super-EIS is a safe
    // default, higher values may unlock per-scene tuning.
    val OPLUS_VIDEO_SUPER_EIS_SCENES: CaptureRequest.Key<Int> =
        key("com.oplus.video.super.eis.scenes", java.lang.Integer::class.java as Class<Int>)

    // Per-stream EIS bypass mask. 0 = bypass nothing (apply EIS to all
    // streams); a non-zero mask tells the HAL to skip EIS on specific
    // streams. We always send 0 so EIS lands on every video stream.
    val OPLUS_EIS_BYPASS_STREAM: CaptureRequest.Key<Int> =
        key("com.oplus.eis.bypass.stream", java.lang.Integer::class.java as Class<Int>)

    // ── Gyro-assisted EIS feed ──────────────────────────────────────────────
    // The native camera app drives video EIS — at every zoom, but most visibly
    // on the teleconverter / periscope path — by feeding the HAL a rolling
    // buffer of timestamped gyroscope samples on every repeating request. The
    // HAL warps each frame from this motion data; without it the EIS engine has
    // no input and produces no stabilization (this is why setting the eismode /
    // video.eis.mode bytes alone did nothing). Format reverse-engineered from
    // build/qa/dump_native_teleconv.txt (native recording on camera device 6).

    // MediaTek gyro sample buffer. byte[N*24], N samples, newest first. Each
    // 24-byte record is little-endian:
    //   int64 timestamp_ns (SensorEvent.timestamp base) +
    //   float gx + float gy + float gz (rad/s) + int32 0 (padding).
    val GYRO_DATA: CaptureRequest.Key<ByteArray> =
        key("com.mediatek.3afeature.gyrodata", ByteArray::class.java)

    // Number of valid samples packed into GYRO_DATA.
    val GYRO_DATA_VALID_NUM: CaptureRequest.Key<Int> =
        key("com.mediatek.3afeature.gyrodatavalidnum", java.lang.Integer::class.java as Class<Int>)

    // Oppo's latest angular-velocity vector. float[3] = {gx, gy, gz} rad/s.
    val OPLUS_GYRO_DATA: CaptureRequest.Key<FloatArray> =
        key("com.oplus.gyro.data", FloatArray::class.java)

    // Oppo gyro magnitude gate — the L2 norm sqrt(gx²+gy²+gz²) of the latest
    // sample (verified against the dump despite the "Sqr" in the tag name).
    val OPLUS_GYRO_SQR_CUSTOM: CaptureRequest.Key<Float> =
        key("com.oplus.gyroSqrCutom", java.lang.Float::class.java as Class<Float>)

    // ── Recording-state gates ───────────────────────────────────────────────
    // The native app sets BOTH of these to 1 while a video is recording. The
    // HAL appears to arm its recording-tuned EIS only when told recording is
    // active — feeding gyro data without these leaves the EIS engine accepting
    // the data but not engaging. 0 = preview/idle, 1 = recording (confirmed in
    // build/qa/dump_native_teleconv.txt, device 6, both keys = 1).
    val MTK_RECORD_STATE: CaptureRequest.Key<Int> =
        key("com.mediatek.streamingfeature.recordState", java.lang.Integer::class.java as Class<Int>)

    val OPLUS_VIDEO_RECORD_STATE: CaptureRequest.Key<Int> =
        key("com.oplus.video.record.state", java.lang.Integer::class.java as Class<Int>)

    // ── HDR ─────────────────────────────────────────────────────────────────
    // MediaTek granular HDR mode (numeric, beyond the AOSP HDR10/DV enum).
    val HDR_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.hdrfeature.hdrMode", java.lang.Integer::class.java as Class<Int>)

    // ── Pro / Movie mode unlocks ────────────────────────────────────────────
    // Toggle that opens up the extended ISO range advertised by
    // com.oplus.pro.extension.iso.range. byte = 0/1.
    val PRO_EXT_ISO_SUPPORT: CaptureRequest.Key<Byte> =
        key("com.oplus.pro.extension.iso.support", java.lang.Byte::class.java as Class<Byte>)

    // LOG color profile (flat tone curve, requires grading).
    val MOVIE_LOG_ENABLE: CaptureRequest.Key<Byte> =
        key("com.oplus.movie.log.enable", java.lang.Byte::class.java as Class<Byte>)

    val MOVIE_HDR_ENABLE: CaptureRequest.Key<Byte> =
        key("com.oplus.movie.hdr.enable", java.lang.Byte::class.java as Class<Byte>)

    // ── Hardware tracking AF ────────────────────────────────────────────────
    // MediaTek native tracking AF — alternative to the software SubjectTracker.
    //   trackingafMode: 0 = OFF, 1 = ON.
    val TRACKING_AF_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.trackingaffeature.trackingafMode", java.lang.Integer::class.java as Class<Int>)

    // trackingafRegion: int32[5] = {x, y, w, h, weight} in sensor coords.
    val TRACKING_AF_REGION: CaptureRequest.Key<IntArray> =
        key("com.mediatek.trackingaffeature.trackingafRegion", IntArray::class.java)

    // trackingafCancel: int32 = 1 to release the tracked target.
    val TRACKING_AF_CANCEL: CaptureRequest.Key<Int> =
        key("com.mediatek.trackingaffeature.trackingafCancel", java.lang.Integer::class.java as Class<Int>)

    // ── Motion-Directed Pan/Tilt/Zoom (mdptz) — hardware subject framing ──
    // The Find X9 Pro periscope's auto-follow mode. Far more capable than
    // bare trackingafMode: it actually moves the in-sensor crop to keep the
    // subject framed as it moves.
    val MDPTZ_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.mdptzfeature.mdptzMode", java.lang.Integer::class.java as Class<Int>)

    // pickupROI: int32[5] = {x, y, w, h, weight} sensor coords identifying
    // the subject to lock on.
    val MDPTZ_PICKUP_ROI: CaptureRequest.Key<IntArray> =
        key("com.mediatek.mdptzfeature.pickupROI", IntArray::class.java)

    // ── 3A regions of interest (per-component) ─────────────────────────────
    // Each is int32[5] = {x, y, w, h, weight} in sensor active-array coords.
    val AE_ROI: CaptureRequest.Key<IntArray> =
        key("com.mediatek.3afeature.aeroi", IntArray::class.java)

    val AF_ROI: CaptureRequest.Key<IntArray> =
        key("com.mediatek.3afeature.afroi", IntArray::class.java)

    val AWB_ROI: CaptureRequest.Key<IntArray> =
        key("com.mediatek.3afeature.awbroi", IntArray::class.java)

    // AE metering mode (byte). Best-guess values:
    //   0 = average / center-weighted, 1 = spot, 2 = matrix/multi.
    val AE_METERING_MODE: CaptureRequest.Key<Byte> =
        key("com.mediatek.3afeature.aeMeteringMode", java.lang.Byte::class.java as Class<Byte>)

    // ── Manual WB Kelvin + tint (refined over our existing gain path) ──────
    val MANUAL_WB_TEMPERATURE: CaptureRequest.Key<Int> =
        key("com.oplus.manualWB.color_temperature", java.lang.Integer::class.java as Class<Int>)

    val MANUAL_WB_TONE: CaptureRequest.Key<Int> =
        key("com.oplus.manualWB.color_tone", java.lang.Integer::class.java as Class<Int>)

    // Most recent failure per vendor key — populated by applySafe so the dev
    // overlay can show silent rejections without grepping logcat. Cleared
    // whenever a previously-failing key starts succeeding again.
    private val failureSink = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun lastFailures(): Map<String, String> = failureSink.toMap()

    /**
     * Apply a single vendor key to a CaptureRequestOptions builder, swallowing
     * any reflection or unsupported-key errors so a missing key on a future
     * firmware update doesn't break the whole pipeline.
     */
    fun <T : Any> applySafe(
        builder: androidx.camera.camera2.interop.CaptureRequestOptions.Builder,
        key: CaptureRequest.Key<T>,
        value: T
    ) {
        try {
            builder.setCaptureRequestOption(key, value)
            failureSink.remove(key.name)
        } catch (e: Throwable) {
            failureSink[key.name] = e.message ?: e.javaClass.simpleName
            android.util.Log.w("VendorCameraKeys", "Failed to set ${key.name}: ${e.message}")
        }
    }

    /**
     * Session-level variant: applies a vendor key to a Camera2Interop.Extender
     * so it ships with the initial session configuration instead of a live
     * repeating request. Required for keys that the HAL treats as session
     * parameters (e.g. the four super-EIS keys — setting them on an already
     * bound session caused the HAL to drop the preview stream unrecoverably,
     * see TICKET-019).
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun <T : Any> applySafeExtender(
        extender: androidx.camera.camera2.interop.Camera2Interop.Extender<*>,
        key: CaptureRequest.Key<T>,
        value: T
    ) {
        try {
            extender.setCaptureRequestOption(key, value)
            failureSink.remove(key.name)
        } catch (e: Throwable) {
            failureSink[key.name] = e.message ?: e.javaClass.simpleName
            android.util.Log.w("VendorCameraKeys", "Failed to set ${key.name} on extender: ${e.message}")
        }
    }
}
