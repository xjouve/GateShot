package com.gateshot.ui.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File

private const val TAG = "TeleCapture"

/**
 * GateShot-owned telephoto video from the logical rear camera's periscope path,
 * stabilized in-app: camera (4K) -> [StabRenderer] gyro warp -> viewfinder + encoder.
 * The camera's own EIS/OIS requests are off so they cannot fight the gyro warp.
 */
class TeleCapture(private val context: Context, private val view: TextureView) {
    private val cameraId = "0"
    /** Camera zoom ratio that engages the periscope; with the 1.2x stabilizer
     *  crop it frames like the Oppo teleconverter's 10x video. */
    private val baseZoom = 3.03f
    @Volatile private var zoomLevel = 10
    private val manager = context.getSystemService(CameraManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("GateShotTeleCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val gyroThread = HandlerThread("GateShotGyro").apply { start() }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var recording = false
    private var activeArray = Rect()
    private var afRegions = 0
    private var aeRegions = 0

    val stabilizer = GyroStabilizer()
    private var renderer: StabRenderer? = null

    // Per-recording logs so a handheld clip can be re-fitted offline (sync, focal).
    @Volatile private var gyroLog: BufferedWriter? = null
    @Volatile private var frameLog: BufferedWriter? = null

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            stabilizer.onGyro(e.timestamp, e.values[0], e.values[1])
            gyroLog?.let { w ->
                try { w.write("${e.timestamp},${e.values[0]},${e.values[1]},${e.values[2]}\n") } catch (_: Exception) {}
            }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
    }

    private var lastResultLog = 0L
    @Volatile private var unlockAfterFocus = false
    /** Once a second, log what the HAL actually applied (stabilization, AF). */
    private val resultLogger = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (unlockAfterFocus && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                // The tap trigger locks the lens; a racer moves toward the camera,
                // so release it and let continuous AF keep tracking the region.
                unlockAfterFocus = false
                request?.let { b ->
                    try {
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        s.capture(b.build(), null, handler)
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    } catch (_: Exception) { }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastResultLog < 1000) return
            lastResultLog = now
            Log.i(TAG, "result videoStab=${result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)} " +
                "ois=${result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)} " +
                "afState=${result.get(CaptureResult.CONTROL_AF_STATE)} " +
                "afRegions=${result.get(CaptureResult.CONTROL_AF_REGIONS)?.joinToString()} " +
                "physical=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)} " +
                "zoom=${result.get(CaptureResult.CONTROL_ZOOM_RATIO)}")
        }
    }
    var onError: (String) -> Unit = {}
    var onRecording: (Boolean) -> Unit = {}
    var onSaved: (File) -> Unit = {}

    @SuppressLint("MissingPermission")
    fun open() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission is required")
            return
        }
        try {
            if (cameraId !in manager.cameraIdList) {
                onError("The rear camera is unavailable on this phone")
                return
            }
            val chars = manager.getCameraCharacteristics(cameraId)
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
            afRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            aeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            // 4K source so the stabilizer's crop costs no sharpness in the 1080p output.
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val stream = sizes.firstOrNull { it.width == 3840 && it.height == 2160 } ?: Size(1920, 1080)
            val texture = view.surfaceTexture ?: error("Viewfinder is not ready")
            val logo = try {
                android.graphics.BitmapFactory.decodeResource(context.resources, com.gateshot.R.drawable.watermark_logo)
            } catch (_: Exception) { null }
            renderer = StabRenderer(texture, view.width, view.height, stream.width, stream.height, stabilizer, logo)
            Log.i(TAG, "stream=${stream.width}x${stream.height} view=${view.width}x${view.height}")
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensors.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_FASTEST, Handler(gyroThread.looper))
            } ?: onError("No gyroscope: video will not be stabilized")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    onError("Camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onError("Could not open periscope camera ($error)")
                }
            }, handler)
        } catch (e: Exception) {
            onError("Periscope camera: ${e.message}")
        }
    }

    /** One session for the whole screen: recording only attaches the encoder to the renderer. */
    private fun configure() {
        val camera = device ?: return
        val target = renderer?.cameraSurface ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(target)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, baseZoom * cameraFactor(zoomLevel))
            // Our gyro warp is the stabilizer; HAL EIS/OIS would move the image
            // in ways the gyro model does not know about.
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            // Debug-only experiment (adb shell setprop debug.gateshot.ois 1): request
            // hardware OIS, to measure whether the periscope lens really stabilizes.
            val oisProbe = debugProp("debug.gateshot.ois") == "1"
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (oisProbe) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            if (oisProbe) Log.i(TAG, "OIS probe: requesting LENS_OPTICAL_STABILIZATION_MODE_ON")
            request = builder
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value
                    try {
                        value.setRepeatingRequest(builder.build(), resultLogger, handler)
                    } catch (e: Exception) {
                        onError("Camera stream: ${e.message}")
                    }
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    onError("Periscope stream could not start")
                }
            }, handler)
        } catch (e: Exception) {
            onError("Camera setup: ${e.message}")
        }
    }

    /**
     * Select 10x, 20x or 30x (teleconverter-equivalent). Level = 10x * camera
     * factor * crop / 1.2. At 20x/30x a 2.0 crop of the 4K stream does part of
     * the zoom: still 1:1 source pixels at 1080p, but the stabilizer gets ~25%
     * margin per side instead of ~7% (camera-only zoom lost the 20x A/B).
     */
    // 10x: 1.25, 4% tighter than the Oppo 10x framing, halves simulated sway
    // (the periscope cannot go wider: below zoom 3.03 the camera switches to lens 2).
    private fun crop(level: Int) = if (level <= 10) 1.25f else 2.0f
    // Never below 1 (zoom 3.03): lower ratios switch to the main camera (lens 2).
    private fun cameraFactor(level: Int) = maxOf(1f, level / 10f * 1.2f / crop(level))

    fun setZoomLevel(level: Int) {
        zoomLevel = level
        stabilizer.setView(cameraFactor(level), crop(level))
        val builder = request ?: return
        val live = session ?: return
        try {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, baseZoom * cameraFactor(level))
            live.setRepeatingRequest(builder.build(), resultLogger, handler)
        } catch (e: Exception) {
            onError("Zoom: ${e.message}")
        }
    }

    fun start() {
        val r = renderer ?: return
        if (recording || recorder != null || device == null) return
        try {
            val dir = File(context.getExternalFilesDir(null), "GateShot/videos").apply { mkdirs() }
            val base = "run_${System.currentTimeMillis()}"
            val output = File(dir, "$base.mp4")
            val mediaRecorder = MediaRecorder(context).apply {
                val audioAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (audioAllowed) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (audioAllowed) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(48_000)
                }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1920, 1080)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(16_000_000)
                // The renderer stores the upright portrait image rotated into the
                // landscape buffer; players rotate it back by 90 degrees.
                setOrientationHint(90)
                setOutputFile(output.absolutePath)
                prepare()
            }
            val logDir = File(context.getExternalFilesDir(null), "GateShot/stablogs").apply { mkdirs() }
            gyroLog = File(logDir, "${base}_gyro.csv").bufferedWriter().apply { write("t_ns,gx,gy,gz\n") }
            frameLog = File(logDir, "${base}_frames.csv").bufferedWriter().apply {
                write("frame_ts_ns,dthetax,dthetay,offx,offy,clamped,enabled,optx,opty,measx,measy,rawx,rawy,l1x,l1y\n")
            }
            // measx/measy: shift measured by the optical stage (ring px), NaN if skipped.
            // rawx/rawy: gyro angles (rad); l1x/l1y: camera path minus L1 path (ring px, 20x/30x).
            r.onFrame = { ts, c, ox, oy, m ->
                frameLog?.let { w ->
                    try { w.write("$ts,${c.dThetaX},${c.dThetaY},${c.ringOffX},${c.ringOffY},${c.clamped},${stabilizer.enabled},$ox,$oy,${m?.get(0) ?: ""},${m?.get(1) ?: ""},${c.rawX},${c.rawY},${m?.getOrNull(2) ?: ""},${m?.getOrNull(3) ?: ""}\n") }
                    catch (_: Exception) {}
                }
            }
            mediaRecorder.start()
            stabilizer.restartRecordingPath()
            r.setEncoder(mediaRecorder.surface, 1920, 1080)
            file = output
            recorder = mediaRecorder
            recording = true
            onRecording(true)
        } catch (e: Exception) {
            discardRecorder()
            onError("Could not start recording: ${e.message}")
        }
    }

    fun stop() {
        if (!recording) return
        recording = false
        try {
            // Detach the encoder before stopping it so no frame is drawn into a dead surface.
            renderer?.setEncoder(null, 0, 0)
            recorder?.stop()
            recorder?.release()
            recorder = null
            closeLogs()
            val saved = file
            file = null
            if (saved != null && saved.length() > 0L) onSaved(saved)
            else onError("No video was saved")
        } catch (e: Exception) {
            discardRecorder()
            onError("Recording failed: ${e.message}")
        }
        onRecording(false)
    }

    /** Focus the selected racer position and retain continuous autofocus there. */
    fun focusAt(x: Float, y: Float) {
        if (afRegions == 0 || activeArray.isEmpty) return
        // Undo the stabilizer crop: the tap is inside the central 1/zoom of the frame.
        val z = stabilizer.cropZoom
        val u = 0.5f + (x - 0.5f) / z
        val v = 0.5f + (y - 0.5f) / z
        // Upright portrait (u, v) -> landscape sensor coordinates, undoing the
        // 180-degree periscope inversion. Assumes the usual 90-degree rear sensor.
        val afX = 1f - v
        // The 16:9 stream is a vertical centre crop of the (taller) active array.
        val visible = ((9f / 16f) * activeArray.width() / activeArray.height()).coerceAtMost(1f)
        val afY = (1f - visible) / 2f + u * visible
        val size = (activeArray.width() * 0.12f).toInt()
        val cx = activeArray.left + (afX * activeArray.width()).toInt()
        val cy = activeArray.top + (afY * activeArray.height()).toInt()
        val rect = Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        rect.intersect(activeArray)
        val builder = request ?: return
        val live = session ?: return
        try {
            val region = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            if (aeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, region)
            setVendorInt(builder, "com.mediatek.trackingaffeature.trackingafMode", 1)
            setVendorIntArray(builder, "com.mediatek.trackingaffeature.trackingafRegion",
                intArrayOf(rect.left, rect.top, rect.width(), rect.height(), 1000))
            // A region change alone is advisory in CONTINUOUS_VIDEO; an explicit
            // trigger makes the HAL refocus on it now, then keep tracking.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            live.capture(builder.build(), null, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            unlockAfterFocus = true
            live.capture(builder.build(), null, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            live.setRepeatingRequest(builder.build(), resultLogger, handler)
            Log.i(TAG, "focusAt tap=($x,$y) region=$rect")
        } catch (e: CameraAccessException) {
            onError("Autofocus: ${e.message}")
        }
    }

    private fun debugProp(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as String
    } catch (_: Exception) { "" }

    @Suppress("UNCHECKED_CAST")
    private fun setVendorInt(builder: CaptureRequest.Builder, name: String, value: Int) {
        try {
            val ctor = CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            ctor.isAccessible = true
            builder.set(ctor.newInstance(name, java.lang.Integer::class.java) as CaptureRequest.Key<Int>, value)
        } catch (_: Exception) { /* Unsupported on other devices. */ }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setVendorIntArray(builder: CaptureRequest.Builder, name: String, value: IntArray) {
        try {
            val ctor = CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            ctor.isAccessible = true
            builder.set(ctor.newInstance(name, IntArray::class.java) as CaptureRequest.Key<IntArray>, value)
        } catch (_: Exception) { /* Unsupported on other devices. */ }
    }

    private fun closeLogs() {
        renderer?.onFrame = null
        val g = gyroLog; val f = frameLog
        gyroLog = null; frameLog = null
        try { g?.close() } catch (_: Exception) {}
        try { f?.close() } catch (_: Exception) {}
    }

    private fun discardRecorder() {
        try { renderer?.setEncoder(null, 0, 0) } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        closeLogs()
        file?.delete()
        file = null
        recording = false
        onRecording(false)
    }

    fun close() {
        if (recording) stop()
        sensors.unregisterListener(gyroListener)
        session?.close()
        session = null
        device?.close()
        device = null
        renderer?.release()
        renderer = null
        handler.post { thread.quitSafely() }
        gyroThread.quitSafely()
    }
}
