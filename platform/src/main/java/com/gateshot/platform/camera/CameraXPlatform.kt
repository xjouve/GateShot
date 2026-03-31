package com.gateshot.platform.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

    override var capabilities: CameraCapabilities? = null
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var currentConfig: CameraConfig? = null

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameListeners = mutableListOf<(ImageProxy) -> Unit>()

    fun bindPreview(view: PreviewView, owner: LifecycleOwner) {
        previewView = view
        lifecycleOwner = owner
        // If camera was already opened, rebind preview surface
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
                        // Dispatch to all registered frame listeners (burst buffer, snow exposure, etc.)
                        frameListeners.forEach { listener ->
                            try {
                                listener(imageProxy)
                            } catch (_: Exception) { }
                        }
                        imageProxy.close()
                    }
                }

            provider.unbindAll()

            val owner = lifecycleOwner
            if (owner != null) {
                camera = provider.bindToLifecycle(
                    owner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

                previewView?.let { preview?.surfaceProvider = it.surfaceProvider }

                val cameraInfo = camera!!.cameraInfo
                capabilities = CameraCapabilities(
                    supportedResolutions = listOf(config.resolution),
                    supportedFrameRates = listOf(30, 60),
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
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        preview = null
        imageAnalysis = null
        _state.value = CameraState.CLOSED
    }

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun setExposureCompensation(ev: Float) {
        // CameraX exposure compensation is in index steps, not raw EV.
        // Convert EV to nearest index using the compensation range/step from CameraInfo.
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

    override suspend fun startRecording() {
        // Video recording requires VideoCapture use case — will be expanded in video module
    }

    override suspend fun stopRecording(): RecordingResult {
        return RecordingResult(uri = "", durationMs = 0, fileSize = 0)
    }

    override fun getSupportedConfigs(): List<CameraConfig> {
        return listOf(
            CameraConfig(LensFacing.BACK, android.util.Size(3840, 2160), 30),
            CameraConfig(LensFacing.BACK, android.util.Size(1920, 1080), 60),
            CameraConfig(LensFacing.BACK, android.util.Size(1920, 1080), 120),
            CameraConfig(LensFacing.FRONT, android.util.Size(1920, 1080), 30)
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
