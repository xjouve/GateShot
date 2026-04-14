package com.gateshot.platform.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

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

    private val resultKeyCtor by lazy {
        CaptureResult.Key::class.java
            .getDeclaredConstructor(String::class.java, Class::class.java)
            .apply { isAccessible = true }
    }

    private fun <T> key(name: String, type: Class<T>): CaptureRequest.Key<T> =
        keyCtor.newInstance(name, type) as CaptureRequest.Key<T>

    private fun <T> resultKey(name: String, type: Class<T>): CaptureResult.Key<T> =
        resultKeyCtor.newInstance(name, type) as CaptureResult.Key<T>

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

    // ── Hasselblad Haute Résolution (Quad Bayer remosaic) ──────────────────
    // Flip the 4:1 pixel-binning off so the sensor delivers its full native
    // resolution (50 MP main, 200 MP periscope). Set to 1 on the capture
    // request to enable; the HAL will return a larger JPEG matching the
    // sensor's native pixel array.
    val REMOSAIC_ENABLE: CaptureRequest.Key<Int> =
        key("com.mediatek.control.capture.remosaicenable", java.lang.Integer::class.java as Class<Int>)

    // Seamless variant — engages remosaic without tearing down the preview
    // session. Use both for belt-and-braces; MediaTek's HAL picks whichever
    // path is cheaper.
    val SEAMLESS_REMOSAIC_ENABLE: CaptureRequest.Key<Int> =
        key("com.mediatek.control.capture.seamless.remosaicenable", java.lang.Integer::class.java as Class<Int>)

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

    // ── AI Shutter — hardware best-shot picker ─────────────────────────────
    // Combines motion detection with shutter lag minimisation; the HAL picks
    // the sharpest frame from a short burst around the trigger.
    val AI_SHUTTER_ENABLE: CaptureRequest.Key<Int> =
        key("com.oplus.aishutter.enable", java.lang.Integer::class.java as Class<Int>)

    val AI_SHUTTER_MODE: CaptureRequest.Key<Byte> =
        key("com.mediatek.3afeature.aishutterMode", java.lang.Byte::class.java as Class<Byte>)

    // ── Exposure bracketing ────────────────────────────────────────────────
    // Oppo's bracket scheduler. Numeric mode is best-effort:
    //   0 = OFF, 1 = AEB ±1 EV, 2 = AEB ±2 EV (per stop count).
    val BRACKET_MODE: CaptureRequest.Key<Int> =
        key("com.oplus.BracketMode", java.lang.Integer::class.java as Class<Int>)

    val BRACKET_MODE_HAL: CaptureRequest.Key<Int> =
        key("com.oplus.BracketMode.hal", java.lang.Integer::class.java as Class<Int>)

    // ── AI Scene Detection (ASD) ───────────────────────────────────────────
    val ASD_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.facefeature.asdmode", java.lang.Integer::class.java as Class<Int>)

    val AI_SCENE_APP_ENABLE: CaptureRequest.Key<Int> =
        key("com.oplus.ai.scene.app.enable", java.lang.Integer::class.java as Class<Int>)

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

    // ── Slow-motion (SMVR) ─────────────────────────────────────────────────
    // MediaTek's high-fps recording switch. Numeric mode encodes the frame
    // rate target (typically 0=off, 1=120, 2=240, 3=480, 4=960).
    val SMVR_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.smvrfeature.smvrMode", java.lang.Integer::class.java as Class<Int>)

    val SMVR_V2_MODE: CaptureRequest.Key<Int> =
        key("com.mediatek.smvrfeature.smvrV2Mode", java.lang.Integer::class.java as Class<Int>)

    // ── Hyperlapse / fast-motion ───────────────────────────────────────────
    val FAST_MOTION_ENABLE: CaptureRequest.Key<Byte> =
        key("com.oplus.fastmotion.mode.enable", java.lang.Byte::class.java as Class<Byte>)

    // ── Pro torch (manual flashlight intensity ramp) ───────────────────────
    val PRO_TORCH_MODE: CaptureRequest.Key<Int> =
        key("com.oplus.ProTorchMode", java.lang.Integer::class.java as Class<Int>)

    // ── Hasselblad XCD filter / LUT selector ───────────────────────────────
    // Numeric ID matches the native camera's filter strip order:
    //   0 = Original, 1..N = XCD presets (Vintage / Néon / Flash chaud / …)
    val APP_FILTER_TYPE: CaptureRequest.Key<Int> =
        key("com.oplus.app.filter.type", java.lang.Integer::class.java as Class<Int>)

    // ── Manual WB Kelvin + tint (refined over our existing gain path) ──────
    val MANUAL_WB_TEMPERATURE: CaptureRequest.Key<Int> =
        key("com.oplus.manualWB.color_temperature", java.lang.Integer::class.java as Class<Int>)

    val MANUAL_WB_TONE: CaptureRequest.Key<Int> =
        key("com.oplus.manualWB.color_tone", java.lang.Integer::class.java as Class<Int>)

    // ── Alternate ultra-HR enable (sibling of remosaicenable) ──────────────
    val ULTRA_HIGH_RES_ENABLE: CaptureRequest.Key<Int> =
        key("com.oplus.ultra.high.resolution.enable", java.lang.Integer::class.java as Class<Int>)

    // ── Result-only keys (read from CaptureResult) ─────────────────────────
    // These are not settable; we read them via the capture callback so the
    // dev overlay can show what the HAL is reporting in real time.
    val RESULT_AE_LUX_INDEX: CaptureResult.Key<Int> =
        resultKey("com.mediatek.3afeature.aeLuxIndex", java.lang.Integer::class.java as Class<Int>)

    val RESULT_AE_AVG_BRIGHTNESS: CaptureResult.Key<Int> =
        resultKey("com.mediatek.3afeature.aeAverageBrightness", java.lang.Integer::class.java as Class<Int>)

    val RESULT_AWB_CCT: CaptureResult.Key<Int> =
        resultKey("com.mediatek.3afeature.awbCct", java.lang.Integer::class.java as Class<Int>)

    val RESULT_ASD_RESULT: CaptureResult.Key<Int> =
        resultKey("com.mediatek.facefeature.asdresult", java.lang.Integer::class.java as Class<Int>)

    val RESULT_ASD_SCENE_VALUE: CaptureResult.Key<Int> =
        resultKey("com.oplus.asd.scene.value", java.lang.Integer::class.java as Class<Int>)

    val RESULT_MOTION_DETECTED_FRAMES: CaptureResult.Key<Int> =
        resultKey("com.oplus.motion_detected_frames", java.lang.Integer::class.java as Class<Int>)

    val RESULT_AI_SHUT_EXIST_MOTION: CaptureResult.Key<Int> =
        resultKey("com.mediatek.3afeature.aishutExistMotion", java.lang.Integer::class.java as Class<Int>)

    val RESULT_TELE_EIS_ACTIVE: CaptureResult.Key<Byte> =
        resultKey("com.oplus.tele.eis.active", java.lang.Byte::class.java as Class<Byte>)

    /**
     * Best-effort read of a vendor result key from a CaptureResult. Returns
     * null on any reflection or HAL miss so the overlay just hides the row.
     */
    fun <T : Any> readSafe(result: android.hardware.camera2.CaptureResult, k: CaptureResult.Key<T>): T? {
        return try {
            result.get(k)
        } catch (_: Throwable) {
            null
        }
    }

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
}

/**
 * Snapshot of the vendor-key state we last asked the HAL to apply, plus any
 * silent failures from applySafe. The dev overlay reads this to show what is
 * actually live without the user having to grep logcat.
 */
data class VendorKeyReport(
    val flipModeApplied: Boolean = false,
    val zoomRatio: Float = 1f,
    val eisModeNumeric: Int = 0,
    val oplusStabModeNumeric: Int = 1,
    val hardwareTracking: Boolean = false,
    val logProfile: Boolean = false,
    val extendedIso: Boolean = false,
    val highResolution: Boolean = false,
    val failures: Map<String, String> = emptyMap(),

    // Settable feature state (echoed for the overlay)
    val aiShutter: Boolean = false,
    val bracketMode: Int = 0,
    val mdptzMode: Int = 0,
    val asdMode: Boolean = false,
    val aiSceneApp: Boolean = false,
    val aeMetering: Int = 0,
    val smvrMode: Int = 0,
    val fastMotion: Boolean = false,
    val proTorch: Int = 0,
    val filterPreset: Int = 0,
    val manualWbKelvin: Int? = null,
    val manualWbTint: Int? = null,
    val ultraHighRes: Boolean = false,

    // Result-only readouts (populated by the capture callback)
    val luxIndex: Int? = null,
    val avgBrightness: Int? = null,
    val awbCct: Int? = null,
    val asdSceneRaw: Int? = null,
    val asdSceneOplus: Int? = null,
    val motionFrames: Int? = null,
    val aiShutterMotion: Int? = null,
    val teleEisActive: Boolean? = null
)
