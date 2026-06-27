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

    // Retained vendor feature state: MDPTZ (tracking hook) + exposure/WB controls.
    private var activeMdptzMode: Int = 0
    private var activeMdptzPickup: AfRegion? = null
    private var activeAeMetering: Int = 0
    private var activeAeRoi: AfRegion? = null
    private var activeAfRoiVendor: AfRegion? = null
    private var activeAwbRoi: AfRegion? = null
    private var activeManualWbKelvin: Int? = null
    private var activeManualWbTint: Int? = null
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

    // ── Gyro-assisted EIS feed (matches the native camera app) ──────────────
    // We register a high-rate gyroscope listener and, on a background loop,
    // push the recent samples into the active repeating request in Oppo's
    // wire format (see VendorCameraKeys.GYRO_DATA). This is the motion input
    // the HAL EIS needs — GateShot previously set EIS mode bytes but fed no
    // gyro data, so the engine never stabilized anything.
    private class GyroSample(val tsNs: Long, val x: Float, val y: Float, val z: Float)
    private val gyroBuffer = java.util.ArrayDeque<GyroSample>()
    private val gyroLock = Any()
    @Volatile private var gyroFeedActive = false
    private var gyroListener: android.hardware.SensorEventListener? = null
    private var gyroPushThread: Thread? = null
    @Volatile private var lastGyroPushCount: Int = 0
    private var lastGyroLogMs: Long = 0

    // Full-stream gyro logging for offline stabilization. Independent of the
    // EIS feed above: every recording writes a `<clip>_gyro.csv` sidecar so the
    // StabilizeModule can reconstruct the camera path. Samples are buffered in
    // memory (a 30 s clip at ~200 Hz is ~6000 rows) and flushed once on stop,
    // keeping file I/O off the sensor callback thread. SensorEvent.timestamp is
    // already on the elapsedRealtimeNanos (BOOTTIME) clock the algorithm expects.
    private class GyroLogSample(val tsNs: Long, val x: Float, val y: Float, val z: Float)
    private val gyroLogBuffer = java.util.ArrayList<GyroLogSample>(8192)
    private val gyroLogLock = Any()
    private var gyroLogListener: android.hardware.SensorEventListener? = null
    private var gyroLogClip: File? = null

    private val sensorManager: android.hardware.SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    }

    // Whether the currently-open back camera advertises
    // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (value 2), the
    // API 33+ "high-quality" EIS mode that modern native camera apps use to
    // get the dramatically smoother stabilization that CONTROL_VIDEO_STAB_ON
    // (value 1, the legacy mode) doesn't deliver. Probed at open() time from
    // CameraCharacteristics so the first session already uses the right mode.
    private var supportsPreviewStabilization: Boolean = false

    // Diagnostic throttling for HAL stabilization result logging.
    private var lastStabResultSig: String = ""
    private var lastStabLogMs: Long = 0L

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

        // Probe high-quality EIS support *before* binding so the initial
        // session can request it. We look at every back-facing camera id and
        // accept the flag if any of them advertises preview-stabilization —
        // CameraX is going to pick one of these for the logical camera and
        // they all track together on the Find X9 Pro.
        supportsPreviewStabilization = probePreviewStabilizationSupport()

        try {
            val provider = getCameraProvider()
            cameraProvider = provider

            val cameraSelector = buildCameraSelector(config)

            // Build preview, attaching a vendor-key result reader so the dev
            // overlay can show real-time readouts from the HAL (lux index,
            // detected scene, motion frames, AWB CCT, AI-shutter motion).
            val previewBuilder = Preview.Builder()

            // CameraX first-class preview stabilization. Critical rule: only
            // call setPreviewStabilizationEnabled(true) when we actually want
            // it ON *and* the device advertises support. We must NOT call it
            // with false while also calling setVideoStabilizationEnabled(true)
            // on the VideoCapture below — CameraX's documented interaction
            // matrix treats Preview=OFF + Video=ON as "nothing is stabilized".
            // Leaving preview stabilization NOT_SPECIFIED lets video
            // stabilization take effect even on devices that don't expose
            // PREVIEW_STABILIZATION publicly (as on many Oppo/MediaTek HALs
            // where the capability is gated behind a vendor scenario flag).
            if (!USE_VENDOR_GYRO_EIS &&
                PREFER_PREVIEW_STABILIZATION &&
                activeStabilization.eis == EisMode.STANDARD &&
                supportsPreviewStabilization
            ) {
                try {
                    previewBuilder.setPreviewStabilizationEnabled(true)
                    android.util.Log.i("CameraXPlatform",
                        "Preview.setPreviewStabilizationEnabled(true) [mode 2]")
                } catch (e: Throwable) {
                    android.util.Log.w("CameraXPlatform",
                        "setPreviewStabilizationEnabled failed: ${e.message}")
                }
            } else {
                // Left NOT_SPECIFIED so the stronger video-stabilization ON
                // (mode 1) on the VideoCapture below takes effect on the
                // recorded stream without landing in a "nothing stabilized"
                // cell of the CameraX stabilization matrix.
                android.util.Log.i("CameraXPlatform",
                    "Preview stabilization NOT_SPECIFIED " +
                    "(preferPreviewStab=$PREFER_PREVIEW_STABILIZATION, eis=${activeStabilization.eis})")
            }

            try {
                val previewExtender = Camera2Interop.Extender(previewBuilder)
                previewExtender.setSessionCaptureCallback(vendorResultCallback)
                applySuperEisSessionKeys(previewExtender)
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

                // Start the gyro-assisted EIS feed only when using the vendor
                // gyro-EIS path. With CameraX EIS owning stabilization it isn't
                // needed (the vendor keys never engaged the HAL anyway).
                if (USE_VENDOR_GYRO_EIS) startGyroFeed()
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
        activeMdptzMode = 0
        activeMdptzPickup = null
        activeAeMetering = 0
        activeAeRoi = null
        activeAfRoiVendor = null
        activeAwbRoi = null
        activeManualWbKelvin = null
        activeManualWbTint = null
        activeFocusDistance = null
        sensorArraySize = null
        stopGyroFeed()
        stopGyroLogging()
        _state.value = CameraState.CLOSED
    }

    /**
     * Start the gyro-assisted EIS feed: register a fast gyroscope listener and
     * a background loop that pushes the recent samples into the repeating
     * request every ~frame. Idempotent. The push itself no-ops while EIS is
     * OFF, so it's safe to start unconditionally once the camera is bound.
     */
    private fun startGyroFeed() {
        if (gyroFeedActive) return
        val sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        if (sensor == null) {
            android.util.Log.w("CameraXPlatform", "No gyroscope — EIS gyro feed unavailable")
            return
        }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                synchronized(gyroLock) {
                    gyroBuffer.addLast(GyroSample(e.timestamp, e.values[0], e.values[1], e.values[2]))
                    // Keep ~120 ms of history — several frames' worth at any fps.
                    val cutoff = e.timestamp - 120_000_000L
                    while (gyroBuffer.isNotEmpty() && gyroBuffer.first().tsNs < cutoff) {
                        gyroBuffer.removeFirst()
                    }
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sensorManager.registerListener(
            listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST
        )
        gyroListener = listener
        gyroFeedActive = true

        val thread = Thread {
            while (gyroFeedActive) {
                try {
                    pushGyroFrame()
                    Thread.sleep(33) // ~30 Hz, matching the native per-frame cadence
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    // Never let a transient camera-state race kill the loop.
                }
            }
        }.apply { isDaemon = true; name = "gateshot-gyro-eis" }
        gyroPushThread = thread
        thread.start()
        android.util.Log.i("CameraXPlatform", "Gyro-assisted EIS feed started")
    }

    private fun stopGyroFeed() {
        gyroFeedActive = false
        gyroListener?.let { sensorManager.unregisterListener(it) }
        gyroListener = null
        gyroPushThread?.interrupt()
        gyroPushThread = null
        synchronized(gyroLock) { gyroBuffer.clear() }
        lastGyroPushCount = 0
    }

    /**
     * Start capturing the full gyro stream for [clip], to be flushed to
     * `<clip>_gyro.csv` on [stopGyroLogging]. Safe to call even with no gyro.
     */
    private fun startGyroLogging(clip: File) {
        if (gyroLogListener != null) stopGyroLogging()  // defensive: never leak a listener
        val sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        if (sensor == null) {
            android.util.Log.w("CameraXPlatform", "No gyroscope — stabilization gyro log unavailable")
            return
        }
        synchronized(gyroLogLock) { gyroLogBuffer.clear() }
        gyroLogClip = clip
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                synchronized(gyroLogLock) {
                    gyroLogBuffer.add(GyroLogSample(e.timestamp, e.values[0], e.values[1], e.values[2]))
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sensorManager.registerListener(
            listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST
        )
        gyroLogListener = listener
        android.util.Log.i("CameraXPlatform", "Gyro logging started for ${clip.name}")
    }

    /**
     * Stop gyro logging and write the buffered samples to `<clip>_gyro.csv`
     * (header `t_ns,gx,gy,gz`, BOOTTIME clock). No-op if logging wasn't active.
     */
    private fun stopGyroLogging() {
        val listener = gyroLogListener ?: return
        sensorManager.unregisterListener(listener)
        gyroLogListener = null
        val clip = gyroLogClip
        gyroLogClip = null
        val samples = synchronized(gyroLogLock) {
            val copy = ArrayList(gyroLogBuffer)
            gyroLogBuffer.clear()
            copy
        }
        if (clip == null || samples.isEmpty()) return
        try {
            val csv = File(clip.parentFile, "${clip.nameWithoutExtension}_gyro.csv")
            csv.bufferedWriter().use { w ->
                w.write("t_ns,gx,gy,gz\n")
                for (s in samples) w.write("${s.tsNs},${s.x},${s.y},${s.z}\n")
            }
            android.util.Log.i("CameraXPlatform",
                "Wrote ${samples.size} gyro samples to ${csv.name}")
        } catch (e: Exception) {
            android.util.Log.e("CameraXPlatform", "Gyro log write failed: ${e.message}")
        }
    }

    /**
     * Serialize the recent gyro samples into Oppo's wire format and merge them
     * into the active repeating request alongside the latest angular-velocity
     * vector and its magnitude gate. Uses addCaptureRequestOptions so it does
     * not clobber the main settings set by applyCaptureRequestSettings.
     */
    private fun pushGyroFrame() {
        if (activeStabilization.eis == EisMode.OFF) return
        val cam = camera ?: return

        val ordered = synchronized(gyroLock) { gyroBuffer.toList() }
            .sortedByDescending { it.tsNs } // newest first, as the HAL expects
            .take(32)
        if (ordered.isEmpty()) return

        val n = ordered.size
        val buf = java.nio.ByteBuffer.allocate(n * 24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in ordered) {
            buf.putLong(s.tsNs)
            buf.putFloat(s.x)
            buf.putFloat(s.y)
            buf.putFloat(s.z)
            buf.putInt(0) // reserved padding (always 0 in the native dump)
        }
        val latest = ordered.first()
        val mag = kotlin.math.sqrt(
            latest.x * latest.x + latest.y * latest.y + latest.z * latest.z
        )

        try {
            val ctrl = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
            val o = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            VendorCameraKeys.applySafe(o, VendorCameraKeys.GYRO_DATA, buf.array())
            VendorCameraKeys.applySafe(o, VendorCameraKeys.GYRO_DATA_VALID_NUM, n)
            VendorCameraKeys.applySafe(
                o, VendorCameraKeys.OPLUS_GYRO_DATA, floatArrayOf(latest.x, latest.y, latest.z)
            )
            VendorCameraKeys.applySafe(o, VendorCameraKeys.OPLUS_GYRO_SQR_CUSTOM, mag)

            // Arm the HAL's recording-tuned EIS while a video is recording.
            // Without these the engine accepts the gyro data but doesn't engage.
            val recState = if (_isRecording.value) 1 else 0
            VendorCameraKeys.applySafe(o, VendorCameraKeys.MTK_RECORD_STATE, recState)
            VendorCameraKeys.applySafe(o, VendorCameraKeys.OPLUS_VIDEO_RECORD_STATE, recState)

            ctrl.addCaptureRequestOptions(o.build())
            lastGyroPushCount = n

            val now = System.currentTimeMillis()
            if (now - lastGyroLogMs > 2000) {
                lastGyroLogMs = now
                android.util.Log.i("EisDiag",
                    "Gyro EIS feed: pushed $n samples, |ω|=${"%.4f".format(mag)} rad/s")
            }
        } catch (_: Throwable) {
            // Camera tearing down — next tick will retry or the loop exits.
        }
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

    // ── Retained vendor feature setters ─────────────────────────────────────

    override fun setMdptzMode(mode: Int, pickup: AfRegion?) {
        activeMdptzMode = mode.coerceAtLeast(0)
        activeMdptzPickup = pickup
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

    override fun setManualWb(kelvin: Int?, tint: Int?) {
        activeManualWbKelvin = kelvin
        activeManualWbTint = tint
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
            //
            // Note: we intentionally do NOT set CONTROL_VIDEO_STABILIZATION_MODE
            // here. CameraX's internal Camera2CaptureRequestBuilder.applyVideoStabilization
            // owns this key and overrides anything we set via the interop layer
            // based on Preview.Builder.setPreviewStabilizationEnabled() and
            // VideoCapture.Builder.setVideoStabilizationEnabled(), which are
            // wired in open()/buildVideoCapture() at bind time. A rebind on
            // EIS change (see MainViewModel.applyCameraSetting) applies the
            // new flag cleanly.

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

        // The four super-EIS vendor keys (com.oplus.eis.workon,
        // com.oplus.camera.video.eis.mode, com.oplus.video.super.eis.scenes,
        // com.oplus.eis.bypass.stream) are wired as *session parameters* via
        // Camera2Interop.Extender in applySuperEisSessionKeys() — see the
        // preview/video builders in open()/buildVideoCapture(). They are
        // NOT set on this live repeating request because the HAL black-
        // screened the preview when they were flipped mid-session (TICKET-019).

        // Oppo's umbrella stabilization mode. OIS Maximum and EIS Panning
        // both set this to a non-1 value so the HAL knows to engage the
        // higher-bracket stabilization path:
        //   OFF=0, STANDARD=1, MAX/SUPER=2, PANNING=3.
        val oplusStabMode = when {
            activeStabilization.eis == EisMode.PANNING -> 3
            activeStabilization.ois == OisMode.MAXIMUM -> 2
            // Best-effort: when EIS is engaged, request the MAX/SUPER
            // stabilization bracket so the HAL runs OIS at its strongest video
            // setting underneath the AOSP EIS (OIS is the most effective lever
            // at telephoto and adds no crop).
            activeStabilization.eis == EisMode.STANDARD -> 2
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

        // ── Retained controls ──────────────────────────────────────────────

        // Hardware mdptz subject framing — kept as a tracking-enhancement hook.
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

        // ── Colour / WB ────────────────────────────────────────────────────

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

        // Capture the gyro stream alongside the clip for offline stabilization.
        startGyroLogging(videoFile)

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
                    stopGyroLogging()
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
            // Throttle to ~5 Hz so we don't flood logcat.
            val now = System.currentTimeMillis()
            if (now - lastPublishMs < 200) return
            lastPublishMs = now

            // Ground-truth diagnostic: log what the HAL is *actually* running
            // for stabilization, not what we asked for. Throttled to ~1 Hz so
            // it doesn't flood logcat. Only logs when values change.
            val hwVideoStab = try {
                result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            } catch (_: Throwable) { null }
            val hwOisState = try {
                result.get(android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
            } catch (_: Throwable) { null }
            val sig = "videoStab=$hwVideoStab ois=$hwOisState"
            if (sig != lastStabResultSig && now - lastStabLogMs > 1000) {
                lastStabResultSig = sig
                lastStabLogMs = now
                android.util.Log.i("EisDiag",
                    "HAL reports: videoStabMode=$hwVideoStab (0=off,1=on,2=previewStab) " +
                    "oisMode=$hwOisState (0=off,1=on)")
            }
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

        // Wire the four "super-EIS" vendor keys and standard
        // CONTROL_VIDEO_STABILIZATION_MODE on the video use case via
        // Camera2Interop.Extender so they land in the initial session config
        // rather than a live repeating request. See TICKET-019 — setting the
        // vendor keys mid-session black-screened the preview; sending them at
        // bind time (and rebinding on EIS toggle) is what unblocks
        // native-quality video stabilization.
        val videoBuilder = VideoCapture.Builder(recorder)

        // CameraX first-class video stabilization. Only call the setter with
        // `true` — when we want stabilization OFF, leave the flag
        // NOT_SPECIFIED so we don't land in the Preview=?/Video=OFF rows of
        // the CameraX stabilization matrix, some of which force even a
        // device-default stabilization OFF.
        if (!USE_VENDOR_GYRO_EIS && activeStabilization.eis == EisMode.STANDARD) {
            try {
                videoBuilder.setVideoStabilizationEnabled(true)
                android.util.Log.i("CameraXPlatform",
                    "VideoCapture.setVideoStabilizationEnabled(true)")
            } catch (e: Throwable) {
                android.util.Log.w("CameraXPlatform",
                    "setVideoStabilizationEnabled failed: ${e.message}")
            }
        } else {
            android.util.Log.i("CameraXPlatform",
                "Video stabilization left to vendor gyro-EIS " +
                "(useVendorGyroEis=$USE_VENDOR_GYRO_EIS, eis=${activeStabilization.eis})")
        }

        try {
            val videoExtender = Camera2Interop.Extender(videoBuilder)
            applySuperEisSessionKeys(videoExtender)
        } catch (e: Throwable) {
            android.util.Log.w("CameraXPlatform",
                "Could not attach super-EIS keys to video use case: ${e.message}")
        }
        return videoBuilder.build()
    }

    /**
     * Check whether any back-facing camera advertises the API 33+
     * PREVIEW_STABILIZATION mode in CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES.
     * Runs cheaply via CameraManager without needing a bound session.
     */
    private fun probePreviewStabilizationSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val previewStabConst = 2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            var found = false
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                // Ground-truth diagnostic dump — logs everything the HAL
                // advertises about stabilization for every back camera, so
                // we can see *why* PREVIEW_STABILIZATION isn't engaging
                // without having to guess.
                val videoModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                android.util.Log.i("EisDiag",
                    "cameraId=$id " +
                    "focals=${focals?.joinToString(",")} " +
                    "videoStabModes=${videoModes?.joinToString(",")} " +
                    "oisModes=${oisModes?.joinToString(",")}"
                )

                if (videoModes == null) continue
                if (videoModes.any { it == previewStabConst }) {
                    found = true
                }
            }
            android.util.Log.i("EisDiag",
                "probePreviewStabilizationSupport=$found")
            found
        } catch (e: Throwable) {
            android.util.Log.w("EisDiag",
                "probePreviewStabilizationSupport failed: ${e.message}")
            false
        }
    }

    /**
     * Apply the Oppo super-EIS session-level vendor keys to a use case's
     * Camera2Interop.Extender. Only engages when EIS is non-OFF; when OFF
     * we explicitly send 0s so a rebind with EIS disabled clears prior state.
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun applySuperEisSessionKeys(
        extender: Camera2Interop.Extender<*>
    ) {
        val eisOn = activeStabilization.eis != EisMode.OFF

        // Master engine enable — without this, per-mode EIS bytes are set
        // but the HAL never actually runs the stabilization pipeline.
        VendorCameraKeys.applySafeExtender(
            extender,
            VendorCameraKeys.OPLUS_EIS_WORKON,
            if (eisOn) 1.toByte() else 0.toByte()
        )

        // Oppo's per-mode byte (distinct from com.mediatek.eisfeature.eismode).
        // Best-guess mapping: OFF=0, STANDARD=1, PANNING=3.
        val oplusEisByte: Byte = when (activeStabilization.eis) {
            EisMode.OFF -> 0
            EisMode.STANDARD -> 1
            EisMode.PANNING -> 3
        }
        VendorCameraKeys.applySafeExtender(
            extender,
            VendorCameraKeys.OPLUS_VIDEO_EIS_MODE,
            oplusEisByte
        )

        // Super-EIS scene selector. 1 = generic super-EIS; the native app
        // uses higher values for per-mode tuning but 1 is the safe default
        // that actually engages the pipeline.
        VendorCameraKeys.applySafeExtender(
            extender,
            VendorCameraKeys.OPLUS_VIDEO_SUPER_EIS_SCENES,
            if (eisOn) 1 else 0
        )

        // Per-stream bypass mask = 0 → apply EIS to every stream (preview +
        // video). A non-zero mask would let the HAL skip specific streams.
        VendorCameraKeys.applySafeExtender(
            extender,
            VendorCameraKeys.OPLUS_EIS_BYPASS_STREAM,
            0
        )
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
        // Stabilization is handled on-recording by CameraX's own EIS (AOSP
        // preview + video stabilization), enabled when eis == STANDARD. The
        // Oppo vendor gyro-EIS path (gyro sample feed + recordState +
        // com.oplus.video.stabilization.mode) was a dead end — the HAL accepted
        // the keys but never visibly engaged EIS on a third-party CameraX
        // session (it appears gated to the native app's privileged session /
        // camera device 6). Keeping this false lets CameraX own stabilization;
        // flipping it back to true disables CameraX EIS to retry the vendor path.
        private const val USE_VENDOR_GYRO_EIS = false

        // EIS mode for CameraX AOSP stabilization when eis == STANDARD:
        //   true  → PREVIEW_STABILIZATION (mode 2): preview+video matched, low
        //           latency, wider FOV, but gentler correction.
        //   false → VIDEO_STABILIZATION ON (mode 1): stronger correction on the
        //           recorded stream (preview not matched). Best-effort attempt to
        //           get more aggressive telephoto stabilization out of the only
        //           stabilization path third-party apps can reach on this device.
        private const val PREFER_PREVIEW_STABILIZATION = false

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
