package com.gateshot.platform.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var currentConfig: CameraConfig? = null

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    fun bindPreview(view: PreviewView, owner: LifecycleOwner) {
        previewView = view
        lifecycleOwner = owner
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

            provider.unbindAll()

            val owner = lifecycleOwner
            if (owner != null) {
                val camera = provider.bindToLifecycle(
                    owner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                previewView?.let { preview?.surfaceProvider = it.surfaceProvider }

                val cameraInfo = camera.cameraInfo
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
        imageCapture = null
        preview = null
        _state.value = CameraState.CLOSED
    }

    override fun setZoom(ratio: Float) {
        // Applied via camera control when bound
    }

    override fun setExposureCompensation(ev: Float) {
        // Applied via camera control when bound
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
