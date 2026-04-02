package com.gateshot.platform.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Ultra-wide context camera running simultaneously with the telephoto.
 *
 * The Oppo Find X9 Pro has a 50MP ultra-wide (15mm, f/2.0, 120° FoV) that
 * GateShot previously ignored. This class runs it as a low-resolution context
 * stream alongside the main telephoto capture, using Camera2 API directly
 * (separate from CameraX which manages the telephoto).
 *
 * The Dimensity 9500 supports concurrent multi-camera streams via
 * SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS. We use the ultra-wide
 * at a reduced resolution (640×480 or 1280×720) to minimize bandwidth.
 *
 * USE CASES:
 * 1. Gate detection — the 120° FoV captures the full course section. We detect
 *    gate pole positions (red/blue vertical lines) to auto-trigger the telephoto
 *    burst when a racer crosses a gate plane.
 * 2. Course mapping — consecutive wide frames are stitched into a panoramic
 *    course map for coaching overlay registration.
 * 3. Establishing shots — auto-capture a wide scene frame before each run.
 */
@Singleton
class ContextCamera @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var ultraWideCameraId: String? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    data class ContextFrame(
        val yPlane: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = yPlane.contentHashCode()
    }

    private val frameListeners = mutableListOf<(ContextFrame) -> Unit>()

    fun addFrameListener(listener: (ContextFrame) -> Unit) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: (ContextFrame) -> Unit) {
        frameListeners.remove(listener)
    }

    /**
     * Find the ultra-wide camera by its short focal length.
     * The ultra-wide has a ~2.5mm physical focal length (15mm equivalent on
     * its sensor), which is the shortest focal length in the system.
     */
    private fun findUltraWideCameraId(): String? {
        var bestId: String? = null
        var shortestFocal = Float.MAX_VALUE

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: continue

            for (fl in focalLengths) {
                if (fl < shortestFocal) {
                    shortestFocal = fl
                    bestId = id
                }
            }
        }

        // Only accept if focal length is significantly shorter than main (< 4mm physical)
        return if (shortestFocal < 4f) bestId else null
    }

    fun isAvailable(): Boolean = findUltraWideCameraId() != null

    /**
     * Start the ultra-wide context stream at low resolution.
     *
     * Uses Camera2 API directly, separate from the CameraX-managed telephoto.
     * The concurrent camera guarantee on Dimensity 9500 allows both streams.
     */
    @Suppress("MissingPermission")
    suspend fun start(resolution: Size = Size(1280, 720)) {
        if (_isActive.value) return

        val cameraId = findUltraWideCameraId()
            ?: throw IllegalStateException("Ultra-wide camera not found")
        ultraWideCameraId = cameraId

        // Start background thread for camera callbacks
        backgroundThread = HandlerThread("ContextCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Create ImageReader for YUV frames
        imageReader = ImageReader.newInstance(
            resolution.width, resolution.height,
            ImageFormat.YUV_420_888, 3
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val yPlane = image.planes[0]
                    val buffer = yPlane.buffer
                    val yBytes = ByteArray(buffer.remaining())
                    buffer.get(yBytes)

                    val frame = ContextFrame(
                        yPlane = yBytes,
                        width = image.width,
                        height = image.height,
                        timestamp = image.timestamp
                    )

                    frameListeners.forEach { listener ->
                        try { listener(frame) } catch (_: Exception) { }
                    }
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        }

        // Open the ultra-wide camera
        cameraDevice = openCamera(cameraId)

        // Create capture session
        val surfaces = listOf(imageReader!!.surface)
        captureSession = createCaptureSession(cameraDevice!!, surfaces)

        // Start repeating capture request
        val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader!!.surface)
            // Low power: 15fps is enough for context
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(10, 15))
            // Auto everything — we just need the image for gate detection
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()

        captureSession!!.setRepeatingRequest(captureRequest, null, backgroundHandler)
        _isActive.value = true
    }

    suspend fun stop() {
        captureSession?.stopRepeating()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        _isActive.value = false
    }

    /**
     * Capture a single full-resolution establishing shot from the ultra-wide.
     * Used before a run to capture the course context.
     */
    @Suppress("MissingPermission")
    suspend fun captureEstablishingShot(): ContextFrame? {
        val cameraId = ultraWideCameraId ?: findUltraWideCameraId() ?: return null
        val wasActive = _isActive.value

        // If context stream is running, grab a frame directly
        // Otherwise, do a one-shot capture at higher resolution
        if (!wasActive) {
            start(Size(1920, 1080))
        }

        return suspendCancellableCoroutine { cont ->
            val oneShot = object : (ContextFrame) -> Unit {
                override fun invoke(frame: ContextFrame) {
                    removeFrameListener(this)
                    cont.resume(frame)
                }
            }
            addFrameListener(oneShot)

            cont.invokeOnCancellation {
                removeFrameListener(oneShot)
            }
        }.also {
            if (!wasActive) stop()
        }
    }

    @Suppress("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cont.resume(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("Camera error: $error"))
                }
            }, backgroundHandler)
        }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<android.view.Surface>
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cont.resume(session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                cont.resumeWithException(
                    IllegalStateException("Context camera session configuration failed"))
            }
        }, backgroundHandler)
    }
}
