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
    private var activeAfRegions: List<AfRegion> = emptyList()
    private var activeFocusDistance: Float? = null
    private var activeIspConfig = IspPipelineConfig()
    private var activeWbGains: WhiteBalanceGains? = null
    private var activeEvCompensation: Float = 0f

    // Capture metadata readback
    override var lastCaptureMetadata: CaptureMetadata? = null
        private set

    // RAW capture support
    private var rawImageReader: ImageReader? = null
    private var rawCamera2Id: String? = null

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

    override suspend fun open(config: CameraConfig) {
        // Invalidate any previous analysis fallback so it stops
        analysisGeneration++
        _state.value = CameraState.OPENING
        currentConfig = config

        try {
            val provider = getCameraProvider()
            cameraProvider = provider

            val cameraSelector = buildCameraSelector(config)

            // Build preview
            preview = Preview.Builder().build()

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
        activeAfRegions = emptyList()
        activeFocusDistance = null
        sensorArraySize = null
        _state.value = CameraState.CLOSED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera2 manual controls
    // ─────────────────────────────────────────────────────────────────────────

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun setExposureCompensation(ev: Float) {
        activeEvCompensation = ev
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
            opts.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (activeStabilization.opticalStabilization)
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )

            // ── Video stabilization (EIS) ───────────────────────────────────
            opts.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (activeStabilization.videoStabilization)
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
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

            // ── AF regions ──────────────────────────────────────────────────
            if (activeAfRegions.isNotEmpty()) {
                val sensorRect = sensorArraySize
                if (sensorRect != null) {
                    val meteringRects = activeAfRegions.map { region ->
                        toMeteringRectangle(region, sensorRect)
                    }.toTypedArray()

                    opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, meteringRects)
                    opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, meteringRects)
                    opts.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
            }

            // ── Manual focus distance (for macro) ───────────────────────────
            activeFocusDistance?.let { distance ->
                if (distance > 0f) {
                    opts.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_OFF
                    )
                    opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                }
            }

            camera2Control.setCaptureRequestOptions(opts.build())
        } catch (_: Exception) {
            // Camera2 interop not available or setting not supported
        }
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

        // JPEG quality from config — injected via Camera2 interop
        val camera2Extender = Camera2Interop.Extender(builder)
        camera2Extender.setCaptureRequestOption(
            CaptureRequest.JPEG_QUALITY,
            config.jpegQuality.toByte()
        )

        return builder.build()
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
                supportsFaceDetection = supportsFaceDetection
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
