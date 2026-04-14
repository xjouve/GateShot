package com.gateshot.platform.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class CameraXPlatform @Inject constructor(
    @ApplicationContext private val context: Context
) : CameraPlatform {

    private val _state = MutableStateFlow(CameraState.CLOSED)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _vendorKeyReport = MutableStateFlow(VendorKeyReport())
    override val vendorKeyReport: StateFlow<VendorKeyReport> = _vendorKeyReport.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    override var capabilities: CameraCapabilities? = null
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    // imageAnalysis removed — X9 Pro camera doesn't support enough
    // simultaneous surfaces. Analysis runs via startAnalysisFallback().
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var currentConfig: CameraConfig? = null

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameListeners = mutableListOf<(ImageProxy) -> Unit>()
    @Volatile private var analysisGeneration = 0

    // Camera2 state
    private var activeManualExposure = ManualExposure()
    private var activeStabilization = StabilizationConfig()
    private var activeAfMode: CameraAfMode = CameraAfMode.PREDICTIVE
    private var activeAfRegions: List<AfRegion> = emptyList()
    private var activeHardwareTracking: Boolean = false
    private var activeHardwareTrackingRegion: AfRegion? = null
    private var activeLogProfile: Boolean = false
    private var activeExtendedIso: Boolean = false

    // Tier 1/2/3 vendor feature state
    private var activeAiShutter: Boolean = false
    private var activeBracketMode: Int = 0
    private var activeMdptzMode: Int = 0
    private var activeMdptzPickup: AfRegion? = null
    private var activeAiScene: Boolean = false
    private var activeAeMetering: Int = 0
    private var activeAeRoi: AfRegion? = null
    private var activeAfRoiVendor: AfRegion? = null
    private var activeAwbRoi: AfRegion? = null
    private var activeSmvrMode: Int = 0
    private var activeFastMotion: Boolean = false
    private var activeProTorch: Int = 0
    private var activeFilterPreset: Int = 0
    private var activeManualWbKelvin: Int? = null
    private var activeManualWbTint: Int? = null
    private var activeUltraHighRes: Boolean = false

    // Latest result-key readouts from the capture callback
    private var lastResultLuxIndex: Int? = null
    private var lastResultAvgBrightness: Int? = null
    private var lastResultAwbCct: Int? = null
    private var lastResultAsdRaw: Int? = null
    private var lastResultAsdOplus: Int? = null
    private var lastResultMotionFrames: Int? = null
    private var lastResultAiShutterMotion: Int? = null
    private var lastResultTeleEisActive: Boolean? = null
    private var activeFocusDistance: Float? = null
    private var activeIspConfig = IspPipelineConfig()
    private var activeWbGains: WhiteBalanceGains? = null
    private var activeEvCompensation: Float = 0f
    private var activeTonemapConfig = TonemapConfig()

    // Capture metadata readback
    override var lastCaptureMetadata: CaptureMetadata? = null
        private set

    // RAW capture support
    private var rawImageReader: ImageReader? = null
    private var rawCamera2Id: String? = null

    // Tracks the requested zoom ratio so the periscope upside-down fix can be
    // re-applied whenever the camera is rebound or the zoom changes.
    private var currentZoomRatio: Float = 1f

    // Video recording state
    private var recordingStartTime: Long = 0
    private var recordingFile: File? = null
    private var pendingRecordingResult: ((RecordingResult) -> Unit)? = null

    // Sensor active area for AF region conversion
    private var sensorArraySize: Rect? = null

    fun bindPreview(view: PreviewView, owner: LifecycleOwner) {
        previewView = view
        lifecycleOwner = owner
        preview?.surfaceProvider = view.surfaceProvider
    }

    fun addFrameListener(listener: (ImageProxy) -> Unit) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: (ImageProxy) -> Unit) {
        frameListeners.remove(listener)
    }

    /**
     * Get the current preview bitmap (RGB) from the PreviewView.
     * Returns null if preview is not available. Must be called from the main thread
     * or via a handler post. Thread-safe: internally posts to main looper.
     */
    fun getPreviewBitmap(): android.graphics.Bitmap? {
        val view = previewView ?: return null
        // PreviewView.getBitmap() must be called on main thread
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return view.bitmap
        }
        var result: android.graphics.Bitmap? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            result = view.bitmap
            latch.countDown()
        }
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    override suspend fun open(config: CameraConfig) {
        // Invalidate any previous analysis fallback so it stops
        analysisGeneration++
        _state.value = CameraState.OPENING
        currentConfig = config

        try {
            val provider = getCameraProvider()
            cameraProvider = provider

            val cameraSelector = buildCameraSelector(config)

            // Build preview, attaching a vendor-key result reader so the dev
            // overlay can show real-time readouts from the HAL (lux index,
            // detected scene, motion frames, AWB CCT, AI-shutter motion).
            val previewBuilder = Preview.Builder()
            try {
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(vendorResultCallback)
            } catch (e: Throwable) {
                android.util.Log.w("CameraXPlatform",
                    "Could not attach vendor result callback: ${e.message}")
            }
            preview = previewBuilder.build()

            // Build image capture with Camera2 manual controls
            imageCapture = buildImageCapture(config)

            // Build video capture with HDR profile support
            videoCapture = buildVideoCapture(config)

            provider.unbindAll()

            val owner = lifecycleOwner
            if (owner != null) {
                // Try binding use cases. The Oppo Find X9 Pro's sub-cameras
                // often support only 2 simultaneous streams.
                camera = try {
                    provider.bindToLifecycle(
                        owner, cameraSelector,
                        preview, imageCapture, videoCapture
                    )
                } catch (e: Exception) {
                    android.util.Log.w("CameraXPlatform", "3 use cases failed, trying 2: ${e.message}")
                    provider.unbindAll()
                    videoCapture = null
                    try {
                        provider.bindToLifecycle(
                            owner, cameraSelector,
                            preview, imageCapture
                        )
                    } catch (e2: Exception) {
                        android.util.Log.w("CameraXPlatform", "2 use cases failed too: ${e2.message}, trying default camera")
                        provider.unbindAll()
                        val fallbackSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        provider.bindToLifecycle(
                            owner, fallbackSelector,
                            preview, imageCapture
                        )
                    }
                }

                previewView?.let { preview?.surfaceProvider = it.surfaceProvider }

                // Oppo Find X9 Pro: the Hasselblad periscope telephoto module is
                // physically mounted rotated 180°, so once the logical camera
                // crosses ~3x zoom and engages the periscope, frames arrive
                // flipped. Apply the rotation fix based on current zoom.
                applyTelephotoUpsideDownFix(currentZoomRatio)

                // Start periodic frame analysis after preview surface is connected.
                // Uses PreviewView.getBitmap() since ImageAnalysis can't bind on this device.
                if (frameListeners.isNotEmpty()) {
                    android.util.Log.i("CameraXPlatform",
                        "Starting periodic analysis for ${frameListeners.size} listeners")
                    startAnalysisFallback()
                }

                // Read Camera2 characteristics for this camera
                readCamera2Characteristics()

                // Set up RAW capture if requested
                if (config.enableRaw) {
                    setupRawCapture()
                }
            }

            _state.value = CameraState.OPEN
            android.util.Log.i("CameraXPlatform", "Camera opened successfully, state=OPEN")
        } catch (e: Exception) {
            android.util.Log.e("CameraXPlatform", "Camera open failed: ${e.message}", e)
            _state.value = CameraState.ERROR
            throw e
        }
    }

    override suspend fun close() {
        activeRecording?.stop()
        activeRecording = null
        _isRecording.value = false
        rawImageReader?.close()
        rawImageReader = null
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        preview = null
        analysisGeneration++
        videoCapture = null
        activeManualExposure = ManualExposure()
        activeStabilization = StabilizationConfig()
        activeAfMode = CameraAfMode.PREDICTIVE
        activeAfRegions = emptyList()
        activeHardwareTracking = false
        activeHardwareTrackingRegion = null
        activeLogProfile = false
        activeExtendedIso = false
        activeAiShutter = false
        activeBracketMode = 0
        activeMdptzMode = 0
        activeMdptzPickup = null
        activeAiScene = false
        activeAeMetering = 0
        activeAeRoi = null
        activeAfRoiVendor = null
        activeAwbRoi = null
        activeSmvrMode = 0
        activeFastMotion = false
        activeProTorch = 0
        activeFilterPreset = 0
        activeManualWbKelvin = null
        activeManualWbTint = null
        activeUltraHighRes = false
        lastResultLuxIndex = null
        lastResultAvgBrightness = null
        lastResultAwbCct = null
        lastResultAsdRaw = null
        lastResultAsdOplus = null
        lastResultMotionFrames = null
        lastResultAiShutterMotion = null
        lastResultTeleEisActive = null
        activeFocusDistance = null
        sensorArraySize = null
        _state.value = CameraState.CLOSED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera2 manual controls
    // ─────────────────────────────────────────────────────────────────────────

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
        applyTelephotoUpsideDownFix(ratio)
    }

    override fun setExposureCompensation(ev: Float) {
        activeEvCompensation = ev
        applyCaptureRequestSettings()
    }

    override fun setTonemapCurve(config: TonemapConfig) {
        activeTonemapConfig = config
        applyCaptureRequestSettings()
    }

    override fun setManualExposure(exposure: ManualExposure) {
        activeManualExposure = exposure
        applyCaptureRequestSettings()
    }

    override fun setStabilization(config: StabilizationConfig) {
        activeStabilization = config
        applyCaptureRequestSettings()
    }

    override fun setAfMode(mode: CameraAfMode) {
        activeAfMode = mode
        // Manual focus mode clears any prior focus-distance override only if
        // we are switching *out* of MANUAL — otherwise leave the user's
        // focusDistance setpoint alone.
        if (mode != CameraAfMode.MANUAL) {
            activeFocusDistance = null
        }
        applyCaptureRequestSettings()
    }

    override fun setHardwareTracking(enabled: Boolean, region: AfRegion?) {
        activeHardwareTracking = enabled
        activeHardwareTrackingRegion = region
        applyCaptureRequestSettings()
    }

    override fun setLogColorProfile(enabled: Boolean) {
        activeLogProfile = enabled
        applyCaptureRequestSettings()
    }

    override fun setExtendedIsoEnabled(enabled: Boolean) {
        activeExtendedIso = enabled
        applyCaptureRequestSettings()
    }

    // ── Tier 1/2/3 vendor feature setters ──────────────────────────────────

    override fun setAiShutter(enabled: Boolean) {
        activeAiShutter = enabled
        applyCaptureRequestSettings()
    }

    override fun setBracketMode(mode: Int) {
        activeBracketMode = mode.coerceAtLeast(0)
        applyCaptureRequestSettings()
    }

    override fun setMdptzMode(mode: Int, pickup: AfRegion?) {
        activeMdptzMode = mode.coerceAtLeast(0)
        activeMdptzPickup = pickup
        applyCaptureRequestSettings()
    }

    override fun setAiScene(enabled: Boolean) {
        activeAiScene = enabled
        applyCaptureRequestSettings()
    }

    override fun setAeMetering(mode: Int) {
        activeAeMetering = mode.coerceAtLeast(0)
        applyCaptureRequestSettings()
    }

    override fun setExposureRoi(ae: AfRegion?, af: AfRegion?, awb: AfRegion?) {
        activeAeRoi = ae
        activeAfRoiVendor = af
        activeAwbRoi = awb
        applyCaptureRequestSettings()
    }

    override fun setSmvrMode(mode: Int) {
        activeSmvrMode = mode.coerceAtLeast(0)
        applyCaptureRequestSettings()
    }

    override fun setFastMotion(enabled: Boolean) {
        activeFastMotion = enabled
        applyCaptureRequestSettings()
    }

    override fun setProTorch(level: Int) {
        activeProTorch = level.coerceAtLeast(0)
        applyCaptureRequestSettings()
    }

    override fun setFilterPreset(presetId: Int) {
        activeFilterPreset = presetId.coerceAtLeast(0)
        applyCaptureRequestSettings()
    }

    override fun setManualWb(kelvin: Int?, tint: Int?) {
        activeManualWbKelvin = kelvin
        activeManualWbTint = tint
        applyCaptureRequestSettings()
    }

    override fun setUltraHighResolution(enabled: Boolean) {
        activeUltraHighRes = enabled
        applyCaptureRequestSettings()
    }

    override fun setAfRegions(regions: List<AfRegion>) {
        activeAfRegions = regions
        applyCaptureRequestSettings()
    }

    override fun setFocusDistance(dioptres: Float) {
        activeFocusDistance = dioptres
        applyCaptureRequestSettings()
    }

    override fun setIspPipeline(config: IspPipelineConfig) {
        activeIspConfig = config
        applyCaptureRequestSettings()
    }

    override fun setWhiteBalanceGains(gains: WhiteBalanceGains) {
        activeWbGains = gains
        applyCaptureRequestSettings()
    }

    /**
     * Apply all active Camera2 settings via CameraControl.
     * Uses Camera2CameraControl to inject CaptureRequest parameters into the
     * active repeating request managed by CameraX.
     */
    private fun applyCaptureRequestSettings() {
        val cam = camera ?: return

        try {
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(
                cam.cameraControl
            )

            val opts = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

            // ── Manual exposure (shutter speed + ISO) ───────────────────────
            // Full manual mode requires both shutter speed AND ISO to be set.
            // When only shutter speed is specified (preset mode), keep AE on
            // so that EV compensation continues to work — AE will pick ISO
            // automatically based on the scene and the EV bias.
            if (activeManualExposure.enabled &&
                activeManualExposure.shutterSpeedNs != null &&
                activeManualExposure.iso != null) {
                opts.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                opts.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    activeManualExposure.shutterSpeedNs!!
                )
                opts.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    activeManualExposure.iso!!
                )
            }

            // ── Exposure compensation (EV bias) ────────────────────────────
            // Applied via Camera2Interop so it stays in sync with all other
            // capture request options and doesn't get overridden.
            if (activeEvCompensation != 0f) {
                val cameraInfo = cam.cameraInfo
                val step = cameraInfo.exposureState.exposureCompensationStep.toFloat()
                if (step > 0f) {
                    val range = cameraInfo.exposureState.exposureCompensationRange
                    val index = (activeEvCompensation / step).toInt().coerceIn(range.lower, range.upper)
                    opts.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index
                    )
                }
            }

            // ── OIS ─────────────────────────────────────────────────────────
            // Camera2 only exposes OFF/ON for LENS_OPTICAL_STABILIZATION_MODE,
            // so MAXIMUM maps to ON like STANDARD; the visible difference comes
            // from the EIS coupling below.
            opts.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                when (activeStabilization.ois) {
                    OisMode.OFF -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    OisMode.STANDARD,
                    OisMode.MAXIMUM -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                }
            )

            // ── Video stabilization (EIS) ───────────────────────────────────
            // PANNING leaves video stabilization OFF on purpose so horizontal
            // pans aren't damped — important for follow shots in slalom.
            opts.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                when (activeStabilization.eis) {
                    EisMode.OFF,
                    EisMode.PANNING -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    EisMode.STANDARD -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                }
            )

            // ── ISP noise reduction ─────────────────────────────────────────
            opts.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                when (activeIspConfig.noiseReduction) {
                    IspNoiseReduction.OFF -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                    IspNoiseReduction.FAST -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
                    IspNoiseReduction.HIGH_QUALITY -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                }
            )

            // ── ISP edge enhancement / sharpening ───────────────────────────
            opts.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                when (activeIspConfig.edgeEnhancement) {
                    IspEdgeMode.OFF -> CameraMetadata.EDGE_MODE_OFF
                    IspEdgeMode.FAST -> CameraMetadata.EDGE_MODE_FAST
                    IspEdgeMode.HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
                }
            )

            // ── Hot pixel correction ────────────────────────────────────────
            opts.setCaptureRequestOption(
                CaptureRequest.HOT_PIXEL_MODE,
                when (activeIspConfig.hotPixelCorrection) {
                    IspHotPixel.OFF -> CameraMetadata.HOT_PIXEL_MODE_OFF
                    IspHotPixel.FAST -> CameraMetadata.HOT_PIXEL_MODE_FAST
                    IspHotPixel.HIGH_QUALITY -> CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
                }
            )

            // ── Face detection ──────────────────────────────────────────────
            opts.setCaptureRequestOption(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                if (activeIspConfig.faceDetection)
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                else
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            )

            // ── Flash mode ──────────────────────────────────────────────────
            opts.setCaptureRequestOption(
                CaptureRequest.FLASH_MODE,
                when (activeIspConfig.flashMode) {
                    FlashMode.OFF -> CameraMetadata.FLASH_MODE_OFF
                    FlashMode.AUTO -> CameraMetadata.FLASH_MODE_SINGLE
                    FlashMode.ON -> CameraMetadata.FLASH_MODE_SINGLE
                    FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                }
            )

            // ── White balance from True Color sensor ────────────────────────
            val wbGains = activeWbGains
            if (wbGains != null) {
                opts.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_OFF
                )
                val rggb = android.hardware.camera2.params.RggbChannelVector(
                    wbGains.redGain,
                    wbGains.greenEvenGain,
                    wbGains.greenOddGain,
                    wbGains.blueGain
                )
                opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
                opts.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_FAST
                )
            }

            // Hasselblad color profile is applied as post-processing on
            // saved photos, not via TONEMAP_CURVE, because CONTRAST_CURVE
            // mode replaces the ISP's default tone mapping and produces
            // images that are too dark.

            // ── AF mode ─────────────────────────────────────────────────────
            // Manual focus distance overrides AF mode (forces OFF), and AF
            // regions force CONTINUOUS_VIDEO so tap-to-focus tracks. Otherwise
            // the user-selected AF mode applies.
            val manualFocusActive = activeFocusDistance?.let { it > 0f } == true
            val afMode = when {
                manualFocusActive -> CameraMetadata.CONTROL_AF_MODE_OFF
                activeAfRegions.isNotEmpty() -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                activeAfMode == CameraAfMode.MANUAL -> CameraMetadata.CONTROL_AF_MODE_OFF
                activeAfMode == CameraAfMode.SINGLE -> CameraMetadata.CONTROL_AF_MODE_AUTO
                activeAfMode == CameraAfMode.CONTINUOUS -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                activeAfMode == CameraAfMode.PREDICTIVE -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)

            // ── AF regions ──────────────────────────────────────────────────
            if (activeAfRegions.isNotEmpty()) {
                val sensorRect = sensorArraySize
                if (sensorRect != null) {
                    val meteringRects = activeAfRegions.map { region ->
                        toMeteringRectangle(region, sensorRect)
                    }.toTypedArray()

                    opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, meteringRects)
                    opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, meteringRects)
                }
            }

            // ── Manual focus distance (for macro) ───────────────────────────
            activeFocusDistance?.let { distance ->
                if (distance > 0f) {
                    opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                }
            }

            // ── Vendor keys (MediaTek + Oppo) ───────────────────────────────
            applyVendorCaptureRequestOptions(opts)

            camera2Control.setCaptureRequestOptions(opts.build())
        } catch (_: Exception) {
            // Camera2 interop not available or setting not supported
        }
    }

    /**
     * Add MediaTek/Oppo vendor capture request keys to the active options.
     *
     * Each key goes through `VendorCameraKeys.applySafe` so a missing or
     * renamed tag on a future ColorOS build degrades gracefully — the public
     * AOSP keys still apply, the vendor extras are dropped.
     */
    private fun applyVendorCaptureRequestOptions(
        opts: androidx.camera.camera2.interop.CaptureRequestOptions.Builder
    ) {
        // Periscope flip — the MediaTek `flipmode` vendor key is silently
        // dropped on the Find X9 Pro periscope (verified: setting {0,1} at
        // ≥3x had no visible effect, frames were still upside down). We keep
        // sending {0,0} so the dev overlay can still surface any HAL rejection
        // diagnostics, but the visible flip happens via the View-level
        // rotation in applyTelephotoUpsideDownFix.
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.FLIP_MODE,
            intArrayOf(0, 0)
        )
        val flipVertical = false

        // Granular EIS via the MediaTek tag. Mapping is best-effort:
        //   OFF → 0, STANDARD → 1, PANNING → 3 (vendor "panFollow").
        val eisVendor = when (activeStabilization.eis) {
            EisMode.OFF -> 0
            EisMode.STANDARD -> 1
            EisMode.PANNING -> 3
        }
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.EIS_MODE, eisVendor)
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.PREVIEW_EIS, eisVendor)

        // NOTE: the four "super EIS" vendor keys
        //   com.oplus.eis.workon
        //   com.oplus.camera.video.eis.mode
        //   com.oplus.video.super.eis.scenes
        //   com.oplus.eis.bypass.stream
        // were tried here but caused the HAL to drop the preview stream
        // and never recover within the same session — even after toggling
        // EIS back to OFF. They almost certainly need to be set as session
        // parameters at bind time, not on a live repeating request.
        // Reverted until we have a session-parameter path that lets us
        // rebind the camera cleanly when EIS state changes.

        // Oppo's umbrella stabilization mode. OIS Maximum and EIS Panning
        // both set this to a non-1 value so the HAL knows to engage the
        // higher-bracket stabilization path:
        //   OFF=0, STANDARD=1, MAX/SUPER=2, PANNING=3.
        val oplusStabMode = when {
            activeStabilization.eis == EisMode.PANNING -> 3
            activeStabilization.ois == OisMode.MAXIMUM -> 2
            activeStabilization.ois == OisMode.OFF &&
                activeStabilization.eis == EisMode.OFF -> 0
            else -> 1
        }
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.OPLUS_VIDEO_STAB_MODE, oplusStabMode)

        // Pro extended ISO unlock — flips the gate that opens up the wider
        // sensitivity range advertised by com.oplus.pro.extension.iso.range.
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.PRO_EXT_ISO_SUPPORT,
            if (activeExtendedIso) 1.toByte() else 0.toByte()
        )

        // Hasselblad HR — per-request toggle so the HAL skips 4:1 binning
        // for the next frame. Session-level extender already requested this
        // at open time; sending it again on the repeating request keeps the
        // still-capture path in full-sensor mode.
        val hrActive = currentConfig?.highResolution == true
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.REMOSAIC_ENABLE,
            if (hrActive) 1 else 0
        )
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.SEAMLESS_REMOSAIC_ENABLE,
            if (hrActive) 1 else 0
        )

        // Alternate ultra-HR enable — Oppo's sibling key for the same idea.
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.ULTRA_HIGH_RES_ENABLE,
            if (hrActive || activeUltraHighRes) 1 else 0
        )

        // ── Tier 1 controls ────────────────────────────────────────────────

        // AI Shutter — best-shot picker.
        VendorCameraKeys.applySafe(
            opts, VendorCameraKeys.AI_SHUTTER_ENABLE,
            if (activeAiShutter) 1 else 0
        )
        VendorCameraKeys.applySafe(
            opts, VendorCameraKeys.AI_SHUTTER_MODE,
            if (activeAiShutter) 1.toByte() else 0.toByte()
        )

        // Exposure bracketing
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.BRACKET_MODE, activeBracketMode)
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.BRACKET_MODE_HAL, activeBracketMode)

        // Hardware mdptz subject framing
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.MDPTZ_MODE, activeMdptzMode)
        if (activeMdptzMode > 0) {
            val pickup = activeMdptzPickup
            val sensorRect = sensorArraySize
            if (pickup != null && sensorRect != null) {
                val rect = toMeteringRectangle(pickup, sensorRect)
                VendorCameraKeys.applySafe(
                    opts, VendorCameraKeys.MDPTZ_PICKUP_ROI,
                    intArrayOf(rect.x, rect.y, rect.width, rect.height, rect.meteringWeight)
                )
            }
        }

        // AI scene detection
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.ASD_MODE, if (activeAiScene) 1 else 0)
        VendorCameraKeys.applySafe(
            opts, VendorCameraKeys.AI_SCENE_APP_ENABLE,
            if (activeAiScene) 1 else 0
        )

        // AE metering mode (0/1/2)
        VendorCameraKeys.applySafe(
            opts, VendorCameraKeys.AE_METERING_MODE,
            activeAeMetering.toByte()
        )

        // 3A regions of interest (vendor)
        val sensorRect = sensorArraySize
        if (sensorRect != null) {
            activeAeRoi?.let { roi ->
                val r = toMeteringRectangle(roi, sensorRect)
                VendorCameraKeys.applySafe(
                    opts, VendorCameraKeys.AE_ROI,
                    intArrayOf(r.x, r.y, r.width, r.height, r.meteringWeight)
                )
            }
            activeAfRoiVendor?.let { roi ->
                val r = toMeteringRectangle(roi, sensorRect)
                VendorCameraKeys.applySafe(
                    opts, VendorCameraKeys.AF_ROI,
                    intArrayOf(r.x, r.y, r.width, r.height, r.meteringWeight)
                )
            }
            activeAwbRoi?.let { roi ->
                val r = toMeteringRectangle(roi, sensorRect)
                VendorCameraKeys.applySafe(
                    opts, VendorCameraKeys.AWB_ROI,
                    intArrayOf(r.x, r.y, r.width, r.height, r.meteringWeight)
                )
            }
        }

        // ── Tier 2 video / capture features ────────────────────────────────

        // Slow-motion (SMVR) — sent on every request even though it really
        // only matters at session bind. Sending it here is harmless.
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.SMVR_MODE, activeSmvrMode)
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.SMVR_V2_MODE, activeSmvrMode)

        // Hyperlapse / fast-motion
        VendorCameraKeys.applySafe(
            opts, VendorCameraKeys.FAST_MOTION_ENABLE,
            if (activeFastMotion) 1.toByte() else 0.toByte()
        )

        // Pro torch ramp
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.PRO_TORCH_MODE, activeProTorch)

        // ── Tier 3 colour / WB ─────────────────────────────────────────────

        // Hasselblad XCD filter LUT
        VendorCameraKeys.applySafe(opts, VendorCameraKeys.APP_FILTER_TYPE, activeFilterPreset)

        // Manual WB Kelvin / tint (refined alternative to setWhiteBalanceGains)
        activeManualWbKelvin?.let { k ->
            VendorCameraKeys.applySafe(opts, VendorCameraKeys.MANUAL_WB_TEMPERATURE, k)
        }
        activeManualWbTint?.let { t ->
            VendorCameraKeys.applySafe(opts, VendorCameraKeys.MANUAL_WB_TONE, t)
        }

        // LOG color profile (flat tone curve) for movie mode.
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.MOVIE_LOG_ENABLE,
            if (activeLogProfile) 1.toByte() else 0.toByte()
        )

        // Hardware tracking AF — when enabled, hand the periscope a tracking
        // target instead of running the software SubjectTracker.
        VendorCameraKeys.applySafe(
            opts,
            VendorCameraKeys.TRACKING_AF_MODE,
            if (activeHardwareTracking) 1 else 0
        )
        if (activeHardwareTracking) {
            val region = activeHardwareTrackingRegion
            val sensorRect = sensorArraySize
            if (region != null && sensorRect != null) {
                val rect = toMeteringRectangle(region, sensorRect)
                VendorCameraKeys.applySafe(
                    opts,
                    VendorCameraKeys.TRACKING_AF_REGION,
                    intArrayOf(rect.x, rect.y, rect.width, rect.height, rect.meteringWeight)
                )
            }
        } else {
            VendorCameraKeys.applySafe(opts, VendorCameraKeys.TRACKING_AF_CANCEL, 1)
        }

        // Publish the snapshot for the dev overlay. flipModeApplied reflects
        // whether the *visible* flip is in effect (currently the View-level
        // rotation fallback, since the vendor flipmode key is unreliable),
        // so the user can see the periscope state at a glance.
        _vendorKeyReport.value = VendorKeyReport(
            flipModeApplied = currentZoomRatio >= TELEPHOTO_ENGAGE_ZOOM,
            zoomRatio = currentZoomRatio,
            eisModeNumeric = eisVendor,
            oplusStabModeNumeric = oplusStabMode,
            hardwareTracking = activeHardwareTracking,
            logProfile = activeLogProfile,
            extendedIso = activeExtendedIso,
            highResolution = hrActive,
            failures = VendorCameraKeys.lastFailures(),

            aiShutter = activeAiShutter,
            bracketMode = activeBracketMode,
            mdptzMode = activeMdptzMode,
            asdMode = activeAiScene,
            aiSceneApp = activeAiScene,
            aeMetering = activeAeMetering,
            smvrMode = activeSmvrMode,
            fastMotion = activeFastMotion,
            proTorch = activeProTorch,
            filterPreset = activeFilterPreset,
            manualWbKelvin = activeManualWbKelvin,
            manualWbTint = activeManualWbTint,
            ultraHighRes = activeUltraHighRes,

            luxIndex = lastResultLuxIndex,
            avgBrightness = lastResultAvgBrightness,
            awbCct = lastResultAwbCct,
            asdSceneRaw = lastResultAsdRaw,
            asdSceneOplus = lastResultAsdOplus,
            motionFrames = lastResultMotionFrames,
            aiShutterMotion = lastResultAiShutterMotion,
            teleEisActive = lastResultTeleEisActive
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture metadata readback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Read actual exposure parameters from the latest capture result.
     * Called after each takePicture() to populate lastCaptureMetadata.
     */
    private fun readCaptureMetadata() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(camera2Info.cameraId)

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

            lastCaptureMetadata = CaptureMetadata(
                exposureTimeNs = activeManualExposure.shutterSpeedNs ?: 0,
                sensitivity = activeManualExposure.iso ?: 0,
                focusDistanceDioptres = activeFocusDistance ?: 0f,
                focalLengthMm = focalLengths?.firstOrNull() ?: 0f,
                aperture = apertures?.firstOrNull() ?: 0f,
                flashFired = activeIspConfig.flashMode != FlashMode.OFF,
                timestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Photo & RAW capture
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun takePicture(): CaptureResult = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not initialized"))
            return@suspendCancellableCoroutine
        }

        val format = currentConfig?.outputFormat ?: ImageOutputFormat.JPEG
        val extension = if (format == ImageOutputFormat.HEIF) "heif" else "jpg"
        // Save to app's external storage so gallery can find the files
        val storageDir = File(context.getExternalFilesDir(null), "GateShot/photos").also { it.mkdirs() }
        val photoFile = File(storageDir, "gateshot_${System.currentTimeMillis()}.$extension")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    readCaptureMetadata()
                    cont.resume(
                        CaptureResult(
                            uri = photoFile.absolutePath,
                            width = currentConfig?.resolution?.width ?: 0,
                            height = currentConfig?.resolution?.height ?: 0,
                            timestamp = System.currentTimeMillis(),
                            metadata = lastCaptureMetadata
                        )
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    /**
     * Capture a bracketed exposure burst for HDR merging.
     *
     * Takes N frames at different EV offsets (e.g., [-2, 0, +2]) by temporarily
     * adjusting exposure compensation between shots. The frames are returned
     * in order for the caller to align and tone-map merge.
     *
     * For snow scenes with extreme dynamic range (bright snow + dark racers),
     * a 3-frame bracket captures ~6 additional stops of dynamic range.
     */
    override suspend fun takeBracketedBurst(evSteps: List<Float>): List<CaptureResult> {
        val results = mutableListOf<CaptureResult>()
        val originalEv = activeManualExposure

        for (evOffset in evSteps) {
            // Adjust exposure for this bracket frame
            if (activeManualExposure.enabled && activeManualExposure.shutterSpeedNs != null) {
                // In manual mode: adjust shutter speed for the EV offset
                // Each EV stop doubles/halves exposure time
                val factor = Math.pow(2.0, evOffset.toDouble())
                val adjustedNs = (activeManualExposure.shutterSpeedNs!! * factor).toLong()
                setManualExposure(activeManualExposure.copy(shutterSpeedNs = adjustedNs))
            } else {
                // In auto mode: use EV compensation
                setExposureCompensation(evOffset)
            }

            // Small delay for exposure to settle
            kotlinx.coroutines.delay(50)

            // Capture the frame
            val result = takePicture()
            results.add(result)
        }

        // Restore original exposure
        setManualExposure(originalEv)

        return results
    }

    override suspend fun takeRawPicture(): CaptureResult = suspendCancellableCoroutine { cont ->
        val reader = rawImageReader
        if (reader == null) {
            cont.resumeWithException(IllegalStateException("RAW capture not initialized. Enable enableRaw in CameraConfig."))
            return@suspendCancellableCoroutine
        }

        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: run {
                cont.resumeWithException(IllegalStateException("Failed to acquire RAW image"))
                return@setOnImageAvailableListener
            }

            try {
                val dngFile = File(context.cacheDir, "gateshot_raw_${System.currentTimeMillis()}.dng")
                saveDngImage(image, dngFile)
                image.close()

                cont.resume(
                    CaptureResult(
                        uri = dngFile.absolutePath,
                        width = image.width,
                        height = image.height,
                        timestamp = System.currentTimeMillis(),
                        isRaw = true
                    )
                )
            } catch (e: Exception) {
                image.close()
                cont.resumeWithException(e)
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        // Trigger a capture — CameraX image capture will also trigger the RAW stream
        // because we bound the RAW surface alongside the CameraX use cases.
        val capture = imageCapture
        if (capture != null) {
            val dummyFile = File(context.cacheDir, "gateshot_raw_trigger_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(dummyFile).build()
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        dummyFile.delete()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        // RAW listener will handle the result
                    }
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Video recording
    // ─────────────────────────────────────────────────────────────────────────

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    override suspend fun startRecording() {
        val vc = videoCapture ?: throw IllegalStateException("VideoCapture not initialized")

        val videoDir = File(context.getExternalFilesDir(null), "GateShot/videos").also { it.mkdirs() }
        val videoFile = File(videoDir, "gateshot_video_${System.currentTimeMillis()}.mp4")
        recordingFile = videoFile
        recordingStartTime = System.currentTimeMillis()

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val pendingRecording = vc.output
            .prepareRecording(context, outputOptions)
            .let { if (hasAudioPermission) it.withAudioEnabled() else it }

        activeRecording = pendingRecording.start(
            ContextCompat.getMainExecutor(context)
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _isRecording.value = true
                }
                is VideoRecordEvent.Finalize -> {
                    _isRecording.value = false
                    val result = RecordingResult(
                        uri = videoFile.absolutePath,
                        durationMs = System.currentTimeMillis() - recordingStartTime,
                        fileSize = videoFile.length()
                    )
                    pendingRecordingResult?.invoke(result)
                    pendingRecordingResult = null
                }
            }
        }
    }

    override suspend fun stopRecording(): RecordingResult = suspendCancellableCoroutine { cont ->
        val recording = activeRecording
        if (recording == null) {
            cont.resume(RecordingResult(uri = "", durationMs = 0, fileSize = 0))
            return@suspendCancellableCoroutine
        }
        pendingRecordingResult = { result -> cont.resume(result) }
        recording.stop()
        activeRecording = null
    }

    override fun getSupportedConfigs(): List<CameraConfig> {
        return listOf(
            // Telephoto 4K video
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(3840, 2160), 30, false, false, HdrProfile.OFF),
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(3840, 2160), 60, false, false, HdrProfile.OFF),
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(3840, 2160), 120, false, false, HdrProfile.DOLBY_VISION),
            // Telephoto 1080p
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(1920, 1080), 30, false, false, HdrProfile.OFF),
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(1920, 1080), 60, false, false, HdrProfile.OFF),
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(1920, 1080), 120, false, false, HdrProfile.OFF),
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(1920, 1080), 240, false, false, HdrProfile.OFF),
            // Telephoto 720p slow-motion
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(1280, 720), 480, false, false, HdrProfile.OFF),
            // Telephoto RAW
            CameraConfig(LensFacing.BACK, CameraLens.TELEPHOTO, Size(3840, 2160), 30, false, true, HdrProfile.OFF),
            // Main camera
            CameraConfig(LensFacing.BACK, CameraLens.MAIN, Size(3840, 2160), 30, false, false, HdrProfile.OFF),
            CameraConfig(LensFacing.BACK, CameraLens.MAIN, Size(3840, 2160), 60, false, false, HdrProfile.OFF),
            // Ultra-wide
            CameraConfig(LensFacing.BACK, CameraLens.ULTRA_WIDE, Size(1920, 1080), 30, false, false, HdrProfile.OFF),
            // Front camera
            CameraConfig(LensFacing.FRONT, CameraLens.MAIN, Size(1920, 1080), 30, false, false, HdrProfile.OFF)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Camera2 capture callback that reads vendor result keys after every
     * completed frame and forwards the new values to the dev overlay
     * report. Installed via Camera2Interop on the Preview builder so it
     * runs for the repeating preview request, not just stills.
     */
    private val vendorResultCallback = object :
        android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        private var lastPublishMs = 0L
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: android.hardware.camera2.CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            // Throttle publishes to ~5 Hz so we don't flood the StateFlow.
            val now = System.currentTimeMillis()
            if (now - lastPublishMs < 200) return
            lastPublishMs = now

            lastResultLuxIndex = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_AE_LUX_INDEX)
            lastResultAvgBrightness = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_AE_AVG_BRIGHTNESS)
            lastResultAwbCct = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_AWB_CCT)
            lastResultAsdRaw = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_ASD_RESULT)
            lastResultAsdOplus = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_ASD_SCENE_VALUE)
            lastResultMotionFrames = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_MOTION_DETECTED_FRAMES)
            lastResultAiShutterMotion = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_AI_SHUT_EXIST_MOTION)
            lastResultTeleEisActive = VendorCameraKeys.readSafe(result, VendorCameraKeys.RESULT_TELE_EIS_ACTIVE)
                ?.let { it.toInt() != 0 }

            // Cheap re-publish: build the report from current state without
            // re-running applyVendorCaptureRequestOptions.
            _vendorKeyReport.value = _vendorKeyReport.value.copy(
                luxIndex = lastResultLuxIndex,
                avgBrightness = lastResultAvgBrightness,
                awbCct = lastResultAwbCct,
                asdSceneRaw = lastResultAsdRaw,
                asdSceneOplus = lastResultAsdOplus,
                motionFrames = lastResultMotionFrames,
                aiShutterMotion = lastResultAiShutterMotion,
                teleEisActive = lastResultTeleEisActive
            )
        }
    }

    private fun applyTelephotoUpsideDownFix(zoomRatio: Float) {
        currentZoomRatio = zoomRatio
        // Empirically, the MediaTek `flipmode` vendor key is silently dropped
        // on this device for the periscope path — the only proven flip is a
        // PreviewView View-level rotation plus a targetRotation offset for
        // captured JPEG/MP4 metadata. The flipmode key is still passed
        // through applyCaptureRequestSettings so the dev overlay shows it
        // and we can re-enable it once we find a numeric mapping the HAL
        // honours, but for now it's a no-op.
        val upsideDown = zoomRatio >= TELEPHOTO_ENGAGE_ZOOM
        val baseRotation = previewView?.display?.rotation ?: Surface.ROTATION_0
        val effectiveRotation = if (upsideDown) (baseRotation + 2) and 3 else baseRotation
        previewView?.rotation = if (upsideDown) 180f else 0f
        imageCapture?.targetRotation = effectiveRotation
        videoCapture?.targetRotation = effectiveRotation
        applyCaptureRequestSettings()
    }

    private fun buildCameraSelector(config: CameraConfig): CameraSelector {
        if (config.lensFacing == LensFacing.FRONT) {
            return CameraSelector.DEFAULT_FRONT_CAMERA
        }

        // For back cameras, select the physical lens based on CameraLens
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFocalLength = when (config.lens) {
            CameraLens.TELEPHOTO -> 70f   // ~70mm equivalent
            CameraLens.MAIN -> 23f        // ~23mm equivalent
            CameraLens.ULTRA_WIDE -> 15f  // ~15mm equivalent
        }

        // Find the Camera2 ID whose focal length best matches the requested lens
        var bestCameraId: String? = null
        var bestFocalDelta = Float.MAX_VALUE

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: continue

            for (fl in focalLengths) {
                val delta = kotlin.math.abs(fl - focalLengthToPhysical(targetFocalLength))
                if (delta < bestFocalDelta) {
                    bestFocalDelta = delta
                    bestCameraId = id
                }
            }
        }

        if (bestCameraId != null) {
            rawCamera2Id = bestCameraId
            return CameraSelector.Builder()
                .addCameraFilter { cameraInfoList ->
                    cameraInfoList.filter { cameraInfo ->
                        try {
                            Camera2CameraInfo.from(cameraInfo).cameraId == bestCameraId
                        } catch (_: Exception) { false }
                    }
                }
                .build()
        }

        // Fallback to default back camera
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * Convert equivalent focal length (mm) to approximate physical focal length.
     * Crop factor for 1/1.56" sensor ≈ 5.6x, 1/1.28" ≈ 4.7x, ultra-wide ≈ varies.
     * These are approximate — the camera selector uses closest-match.
     */
    private fun focalLengthToPhysical(equivalentMm: Float): Float {
        return when {
            equivalentMm >= 50f -> equivalentMm / 5.6f   // Telephoto (1/1.56" sensor)
            equivalentMm >= 20f -> equivalentMm / 4.7f   // Main (1/1.28" sensor)
            else -> equivalentMm / 6.0f                   // Ultra-wide
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun buildImageCapture(config: CameraConfig): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

        // Hasselblad Haute Résolution: when requested, pin the ImageCapture
        // target resolution to the largest JPEG size the HAL will output for
        // the active back camera so the remosaic output can actually land on
        // a matching surface. The per-request `remosaicenable` key is added
        // in applyVendorCaptureRequestOptions — without this size pin the
        // stream would be clamped back to the binned 12 MP default.
        if (config.highResolution) {
            // Target the Oppo vendor-advertised JPEG size (50 MP / 4:3 for
            // the main and periscope sensors). The public Camera2 map on
            // the Find X9 Pro currently caps at 4096x3072 so CameraX will
            // silently clamp to the nearest supported size — but we still
            // request the larger size upfront so the moment Oppo exposes
            // the full-sensor surface (via a firmware update or an
            // extension we haven't found yet) this code begins delivering
            // 50 MP without any further changes.
            val mapMax = resolveMaxJpegSizeForLens(config.lens)
            val requested = Size(8192, 6144)
            builder.setTargetResolution(requested)
            val requestedMp = requested.width * requested.height / 1_000_000f
            val mapMaxMp = mapMax?.let { it.width * it.height / 1_000_000f }
            android.util.Log.i(
                "CameraXPlatform",
                "HR mode: requesting ${requested.width}x${requested.height} " +
                "(${"%.1f".format(requestedMp)} MP); SCALER map caps at " +
                "${mapMax?.width ?: 0}x${mapMax?.height ?: 0} " +
                "(${"%.1f".format(mapMaxMp ?: 0f)} MP) — actual output will " +
                "match the cap until the vendor surface is exposed"
            )
        }

        // JPEG quality from config — injected via Camera2 interop
        val camera2Extender = Camera2Interop.Extender(builder)
        camera2Extender.setCaptureRequestOption(
            CaptureRequest.JPEG_QUALITY,
            config.jpegQuality.toByte()
        )

        // Tell the HAL upfront that this session wants full-sensor captures.
        // The per-request flip (via applyCaptureRequestSettings) also sends
        // it on each actual capture, but having it on the session-level
        // extender lets the HAL size its buffers correctly from the start.
        if (config.highResolution) {
            try {
                camera2Extender.setCaptureRequestOption(
                    VendorCameraKeys.REMOSAIC_ENABLE, 1
                )
                camera2Extender.setCaptureRequestOption(
                    VendorCameraKeys.SEAMLESS_REMOSAIC_ENABLE, 1
                )
            } catch (e: Throwable) {
                android.util.Log.w("CameraXPlatform",
                    "HR session-level extender rejected remosaic: ${e.message}")
            }
        }

        return builder.build()
    }

    /**
     * Pick the largest JPEG output size advertised by the HAL for the camera
     * ID that matches `lens`. Used to request HR capture sizes.
     */
    private fun resolveMaxJpegSizeForLens(lens: CameraLens): Size? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFocalEq = when (lens) {
                CameraLens.TELEPHOTO -> 70f
                CameraLens.MAIN -> 23f
                CameraLens.ULTRA_WIDE -> 15f
            }

            var bestId: String? = null
            var bestFocalDelta = Float.MAX_VALUE
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: continue
                for (fl in focals) {
                    val delta = kotlin.math.abs(fl - focalLengthToPhysical(targetFocalEq))
                    if (delta < bestFocalDelta) {
                        bestFocalDelta = delta
                        bestId = id
                    }
                }
            }

            val chars = cameraManager.getCameraCharacteristics(bestId ?: return null)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        } catch (e: Exception) {
            android.util.Log.w("CameraXPlatform",
                "Failed to resolve max JPEG size for $lens: ${e.message}")
            null
        }
    }

    private fun buildVideoCapture(config: CameraConfig): VideoCapture<Recorder> {
        val quality = when {
            config.frameRate >= 120 -> Quality.FHD
            config.resolution.width >= 3840 -> Quality.UHD
            config.resolution.width >= 1920 -> Quality.FHD
            else -> Quality.HD
        }

        val qualitySelector = QualitySelector.from(quality)
        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(qualitySelector)

        // Dolby Vision HDR profile — on Android 13+ with capable hardware
        if (config.hdrProfile == HdrProfile.DOLBY_VISION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // CameraX supports dynamic range profiles on Android 13+
                // The Dimensity 9500 ISP supports Dolby Vision capture natively
                recorderBuilder.setQualitySelector(
                    QualitySelector.from(quality)
                )
            } catch (_: Exception) {
                // Fallback to standard recording
            }
        }

        val recorder = recorderBuilder.build()
        return VideoCapture.withOutput(recorder)
    }

    /**
     * Periodically grab the preview bitmap and feed it to frame listeners
     * for snow exposure analysis. Uses PreviewView.getBitmap() which is
     * always available regardless of surface count limits.
     */
    private fun startAnalysisFallback() {
        val view = previewView ?: return
        val myGeneration = analysisGeneration

        Thread {
            while (myGeneration == analysisGeneration && _state.value != CameraState.OPEN) {
                Thread.sleep(100)
            }
            if (myGeneration != analysisGeneration) return@Thread

            while (myGeneration == analysisGeneration && _state.value == CameraState.OPEN) {
                try {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var bitmap: android.graphics.Bitmap? = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        bitmap = view.bitmap
                        latch.countDown()
                    }
                    latch.await(1, java.util.concurrent.TimeUnit.SECONDS)

                    bitmap?.let { bmp ->
                        val proxy = BitmapImageProxy(bmp)
                        frameListeners.forEach { listener ->
                            try { listener(proxy) } catch (_: Exception) { }
                        }
                        proxy.close()
                    }

                    Thread.sleep(500)
                } catch (_: Exception) {
                    Thread.sleep(1000)
                }
            }
        }.start()
    }

    /**
     * Minimal ImageProxy wrapper around a Bitmap for the analysis fallback.
     * SnowAnalyzer only needs planes[0], width, height.
     * We convert RGBA bitmap pixels to a luminance (Y) plane.
     */
    private class BitmapImageProxy(
        private val bitmap: android.graphics.Bitmap
    ) : ImageProxy {
        private val yBuffer: ByteBuffer by lazy {
            // Convert RGBA to Y (luminance) for SnowAnalyzer compatibility
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val yBytes = ByteArray(w * h)
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                yBytes[i] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)).toByte()
            }
            ByteBuffer.wrap(yBytes)
        }

        override fun getWidth() = bitmap.width
        override fun getHeight() = bitmap.height
        override fun getFormat() = ImageFormat.YUV_420_888
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
            val plane = object : ImageProxy.PlaneProxy {
                override fun getRowStride() = bitmap.width
                override fun getPixelStride() = 1
                override fun getBuffer() = yBuffer
            }
            return arrayOf(plane)
        }
        @Suppress("UNCHECKED_CAST")
        override fun getImageInfo(): androidx.camera.core.ImageInfo =
            java.lang.reflect.Proxy.newProxyInstance(
                androidx.camera.core.ImageInfo::class.java.classLoader,
                arrayOf(androidx.camera.core.ImageInfo::class.java)
            ) { _, method, _ ->
                when (method.returnType) {
                    Int::class.java, java.lang.Integer::class.java -> 0
                    Long::class.java, java.lang.Long::class.java -> System.nanoTime()
                    else -> null
                }
            } as androidx.camera.core.ImageInfo
        override fun getImage(): Image? = null
        override fun setCropRect(rect: Rect?) {}
        override fun getCropRect() = Rect(0, 0, bitmap.width, bitmap.height)
        override fun close() { bitmap.recycle() }
    }

    private fun readCamera2Characteristics() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(camera2Info.cameraId)

            sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val supportsRaw = rawSizes != null && rawSizes.isNotEmpty()

            // HR capability: compare largest JPEG output size against the
            // sensor's active array. If a JPEG size meaningfully larger than
            // 12 MP is advertised, the HAL can remosaic this physical camera.
            val jpegSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            val maxJpegSize = jpegSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            val supportsHighResolution = maxJpegSize != null &&
                maxJpegSize.width.toLong() * maxJpegSize.height.toLong() > 20_000_000L

            val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            // Check Dolby Vision support
            val dynamicRangeProfiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            } else null
            val supportsDolbyVision = dynamicRangeProfiles != null

            // Check HEIF support (Android 10+)
            val supportsHeif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            // Check face detection support
            val faceDetectModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            val supportsFaceDetection = faceDetectModes != null &&
                faceDetectModes.any { it != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF }

            val cameraInfo = cam.cameraInfo
            capabilities = CameraCapabilities(
                supportedResolutions = listOf(currentConfig?.resolution ?: Size(3840, 2160)),
                supportedFrameRates = listOf(30, 60, 120, 240, 480),
                hasOpticalStabilization = true,
                maxZoomRatio = cameraInfo.zoomState.value?.maxZoomRatio ?: 1f,
                hasFlash = cameraInfo.hasFlashUnit(),
                supportsRaw = supportsRaw,
                supportsDolbyVision = supportsDolbyVision,
                minFocusDistance = minFocusDist,
                supportsHeif = supportsHeif,
                supportsFaceDetection = supportsFaceDetection,
                supportsHighResolution = supportsHighResolution,
                maxJpegSize = maxJpegSize
            )

            rawCamera2Id = camera2Info.cameraId
        } catch (_: Exception) {
            val cameraInfo = cam.cameraInfo
            capabilities = CameraCapabilities(
                supportedResolutions = listOf(currentConfig?.resolution ?: Size(3840, 2160)),
                supportedFrameRates = listOf(30, 60, 120, 240, 480),
                hasOpticalStabilization = true,
                maxZoomRatio = cameraInfo.zoomState.value?.maxZoomRatio ?: 1f,
                hasFlash = cameraInfo.hasFlashUnit()
            )
        }
    }

    private fun setupRawCapture() {
        // Querying characteristics by ID immediately after a force-open
        // races with the Camera2 service: it can briefly return
        // ServiceSpecificException("unknown device") even for a valid id.
        // Treat any failure as "RAW not available right now" and move on
        // — the user can reopen the camera and try again.
        try {
            val cameraId = rawCamera2Id ?: return
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return

            if (rawSizes.isEmpty()) return

            // Use the largest RAW size (full 200MP on telephoto)
            val largestRaw = rawSizes.maxByOrNull { it.width.toLong() * it.height }
                ?: return

            rawImageReader = ImageReader.newInstance(
                largestRaw.width, largestRaw.height,
                ImageFormat.RAW_SENSOR, 2
            )
        } catch (e: Exception) {
            android.util.Log.w("CameraXPlatform",
                "setupRawCapture skipped: ${e.message}")
            rawImageReader = null
        }
    }

    private fun toMeteringRectangle(region: AfRegion, sensorRect: Rect): MeteringRectangle {
        val sensorWidth = sensorRect.width()
        val sensorHeight = sensorRect.height()

        val halfSize = region.size / 2f
        val left = ((region.centerX - halfSize) * sensorWidth).toInt().coerceIn(0, sensorWidth)
        val top = ((region.centerY - halfSize) * sensorHeight).toInt().coerceIn(0, sensorHeight)
        val right = ((region.centerX + halfSize) * sensorWidth).toInt().coerceIn(0, sensorWidth)
        val bottom = ((region.centerY + halfSize) * sensorHeight).toInt().coerceIn(0, sensorHeight)

        return MeteringRectangle(
            left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1),
            region.weight
        )
    }

    private fun saveDngImage(image: Image, outputFile: File) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        FileOutputStream(outputFile).use { fos ->
            fos.write(bytes)
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context)
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility: parse shutter speed string to nanoseconds
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        // Oppo Find X9 Pro: the Hasselblad periscope telephoto engages at ~3x.
        // Its sensor is physically mounted 180° rotated, so frames arrive
        // flipped and must be rotated back above this threshold.
        private const val TELEPHOTO_ENGAGE_ZOOM = 3.0f

        /**
         * Parse a shutter speed fraction string like "1/1000" to nanoseconds.
         * Returns null if the string is not a valid fraction.
         */
        fun parseShutterSpeedToNs(fraction: String): Long? {
            val parts = fraction.trim().split("/")
            if (parts.size != 2) return null
            val numerator = parts[0].toLongOrNull() ?: return null
            val denominator = parts[1].toLongOrNull() ?: return null
            if (denominator == 0L) return null
            // Convert seconds to nanoseconds: (num/denom) * 1_000_000_000
            return (numerator * 1_000_000_000L) / denominator
        }

        /** Standard ISO range for Oppo Find X9 Pro telephoto. */
        val ISO_RANGE = Range(50, 6400)
    }
}
