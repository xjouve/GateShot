package com.gateshot.platform.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
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
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var currentConfig: CameraConfig? = null

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameListeners = mutableListOf<(ImageProxy) -> Unit>()

    // Video recording state for stopRecording to return
    private var recordingStartTime: Long = 0
    private var recordingFile: File? = null
    private var pendingRecordingResult: ((RecordingResult) -> Unit)? = null

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
        _state.value = CameraState.OPENING
        currentConfig = config

        try {
            val provider = getCameraProvider()
            cameraProvider = provider

            val cameraSelector = when (config.lensFacing) {
                LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            }

            preview = Preview.Builder().build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        frameListeners.forEach { listener ->
                            try { listener(imageProxy) } catch (_: Exception) { }
                        }
                        imageProxy.close()
                    }
                }

            // Video recorder
            val qualitySelector = QualitySelector.from(
                when {
                    config.frameRate >= 120 -> Quality.FHD
                    config.resolution.width >= 3840 -> Quality.UHD
                    config.resolution.width >= 1920 -> Quality.FHD
                    else -> Quality.HD
                }
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            provider.unbindAll()

            val owner = lifecycleOwner
            if (owner != null) {
                // Bind preview + imageCapture + imageAnalysis + videoCapture
                // Note: CameraX may not support all 4 simultaneously on all devices.
                // Fallback: drop imageAnalysis if binding fails.
                camera = try {
                    provider.bindToLifecycle(
                        owner, cameraSelector,
                        preview, imageCapture, imageAnalysis, videoCapture
                    )
                } catch (_: Exception) {
                    // Fallback: bind without imageAnalysis
                    provider.unbindAll()
                    imageAnalysis = null
                    provider.bindToLifecycle(
                        owner, cameraSelector,
                        preview, imageCapture, videoCapture
                    )
                }

                previewView?.let { preview?.surfaceProvider = it.surfaceProvider }

                val cameraInfo = camera!!.cameraInfo
                capabilities = CameraCapabilities(
                    supportedResolutions = listOf(config.resolution),
                    supportedFrameRates = listOf(30, 60, 120, 240, 480),
                    hasOpticalStabilization = true,
                    maxZoomRatio = cameraInfo.zoomState.value?.maxZoomRatio ?: 1f,
                    hasFlash = cameraInfo.hasFlashUnit()
                )
            }

            _state.value = CameraState.OPEN
        } catch (e: Exception) {
            _state.value = CameraState.ERROR
            throw e
        }
    }

    override suspend fun close() {
        activeRecording?.stop()
        activeRecording = null
        _isRecording.value = false
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        preview = null
        imageAnalysis = null
        videoCapture = null
        _state.value = CameraState.CLOSED
    }

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun setExposureCompensation(ev: Float) {
        val cameraInfo = camera?.cameraInfo ?: return
        val range = cameraInfo.exposureState.exposureCompensationRange
        val step = cameraInfo.exposureState.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val index = (ev / step).toInt().coerceIn(range.lower, range.upper)
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    override suspend fun takePicture(): CaptureResult = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not initialized"))
            return@suspendCancellableCoroutine
        }

        val photoFile = File(
            context.cacheDir,
            "gateshot_${System.currentTimeMillis()}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    cont.resume(
                        CaptureResult(
                            uri = photoFile.absolutePath,
                            width = currentConfig?.resolution?.width ?: 0,
                            height = currentConfig?.resolution?.height ?: 0,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    override suspend fun startRecording() {
        val vc = videoCapture ?: throw IllegalStateException("VideoCapture not initialized")

        val videoFile = File(
            context.cacheDir,
            "gateshot_video_${System.currentTimeMillis()}.mp4"
        )
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
        // Oppo Find X9 Pro actual capabilities
        return listOf(
            // 4K video
            CameraConfig(LensFacing.BACK, android.util.Size(3840, 2160), 30, true),
            CameraConfig(LensFacing.BACK, android.util.Size(3840, 2160), 60, true),
            CameraConfig(LensFacing.BACK, android.util.Size(3840, 2160), 120, true),  // 4K Dolby Vision
            // 1080p
            CameraConfig(LensFacing.BACK, android.util.Size(1920, 1080), 30, true),
            CameraConfig(LensFacing.BACK, android.util.Size(1920, 1080), 60, true),
            CameraConfig(LensFacing.BACK, android.util.Size(1920, 1080), 120, true),
            CameraConfig(LensFacing.BACK, android.util.Size(1920, 1080), 240, true),  // Slow-motion
            // 720p slow-motion
            CameraConfig(LensFacing.BACK, android.util.Size(1280, 720), 480, true),   // Max slow-motion
            // Front camera
            CameraConfig(LensFacing.FRONT, android.util.Size(1920, 1080), 30, false)
        )
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context)
            )
        }
}
