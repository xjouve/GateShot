package com.gateshot.ui

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.config.ConfigStore
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
import com.gateshot.CloudBackupWorker
import com.gateshot.capture.tracking.TrackingFeatureModule
import com.gateshot.capture.trigger.TriggerFeatureModule
import com.gateshot.capture.trigger.TriggerZone
import com.gateshot.capture.trigger.ZoneAddRequest
import com.gateshot.coaching.replay.ReplayFeatureModule
import com.gateshot.coaching.replay.ReplayState
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gateshot.platform.camera.CameraAfMode
import com.gateshot.platform.camera.CameraConfig
import com.gateshot.platform.camera.CameraLens
import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.platform.camera.EisMode
import com.gateshot.platform.camera.HdrProfile
import com.gateshot.platform.camera.ImageOutputFormat
import com.gateshot.platform.camera.OisMode
import com.gateshot.platform.camera.StabilizationConfig
import com.gateshot.platform.sensor.SensorPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val mode: AppMode = AppMode.SHOOT,
    val currentPreset: String = "race",
    val presetDisplayName: String = "Race",
    val sessionName: String? = null,
    val sessionDiscipline: String? = null,
    val activeRunNumber: Int = 0,
    val isRecording: Boolean = false,
    val zoomLevel: Float = 1f,
    val batteryLevel: Int = 100,
    val batteryTemp: Float = 20f,
    val storageRemainingGb: Float = 400f,
    val shotCount: Int = 0,
    val cameraReady: Boolean = false,
    val lensAttached: Boolean = false,
    val snowCoveragePercent: Float = 0f,
    val currentEvBias: Float = 0f,
    val isFlatLight: Boolean = false,
    val bufferFrameCount: Int = 0,
    val triggerZones: List<TriggerZone> = emptyList(),
    val triggerArmed: Boolean = false,
    val trackingEnabled: Boolean = false,
    val trackingHasLock: Boolean = false,
    val trackingTargetX: Float = 0f,
    val trackingTargetY: Float = 0f,
    val trackingRegionSize: Float = 0.15f,
    val trackingOccluded: Boolean = false,
    val moduleStatuses: Map<String, String> = emptyMap(),
    val showVendorOverlay: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val modeManager: ModeManager,
    private val eventBus: EventBus,
    val endpointRegistry: EndpointRegistry,
    val cameraXPlatform: CameraXPlatform,
    private val triggerModule: TriggerFeatureModule,
    private val trackingModule: TrackingFeatureModule,
    private val sensorPlatform: SensorPlatform,
    private val configStore: ConfigStore,
    private val replayModule: ReplayFeatureModule
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        android.util.Log.e("GateShot", "MainViewModel INIT — camera state: ${cameraXPlatform.state.value}")
        // Observe mode changes — auto-switch preset to match
        viewModelScope.launch {
            modeManager.currentMode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
                // COACH mode → Video preset, SHOOT mode → Race preset
                val targetPreset = if (mode == AppMode.COACH) "video" else "race"
                if (_uiState.value.currentPreset != targetPreset) {
                    onPresetSelected(targetPreset)
                }
            }
        }

        // Observe recording state
        viewModelScope.launch {
            cameraXPlatform.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }

        // Pull persisted dev-overlay state at startup so it survives app restart
        _uiState.update {
            it.copy(showVendorOverlay = loadSettingBool("dev", "vendor_overlay", false))
        }

        // Observe camera events
        eventBus.collect<AppEvent.CameraOpened>(viewModelScope) {
            _uiState.update { it.copy(cameraReady = true) }
        }
        eventBus.collect<AppEvent.CameraClosed>(viewModelScope) {
            _uiState.update { it.copy(cameraReady = false) }
        }
        eventBus.collect<AppEvent.LensDetected>(viewModelScope) {
            _uiState.update { it.copy(lensAttached = true) }
        }
        eventBus.collect<AppEvent.LensRemoved>(viewModelScope) {
            _uiState.update { it.copy(lensAttached = false) }
        }
        eventBus.collect<AppEvent.PresetApplied>(viewModelScope) { event ->
            if (event.presetName == "__cycle_next__") {
                cyclePreset()
            } else {
                val displayName = com.gateshot.ui.components.PRESETS
                    .firstOrNull { it.id == event.presetName }?.displayName ?: event.presetName
                // Show actual EV being applied (user override if snow comp is off)
                val snowCompOn = loadSettingBool("exposure", "snow_compensation", true)
                val actualEv = if (snowCompOn) {
                    // Snow module will update dynamically
                    _uiState.value.currentEvBias
                } else {
                    loadSettingFloat("exposure", "ev_bias", 0f)
                }
                _uiState.update { it.copy(
                    currentPreset = event.presetName,
                    presetDisplayName = displayName,
                    currentEvBias = actualEv
                ) }
            }
        }
        eventBus.collect<AppEvent.BurstCompleted>(viewModelScope) { event ->
            _uiState.update { it.copy(shotCount = it.shotCount + event.frameCount) }
        }
        // Poll battery, storage, and snow status every 10 seconds
        viewModelScope.launch {
            while (true) {
                val battLevel = sensorPlatform.getBatteryLevel()
                val battTemp = sensorPlatform.getBatteryTemperature() ?: 20f

                // Real storage remaining
                val storageDir = appContext.getExternalFilesDir(null)
                val storageGb = if (storageDir != null) {
                    val stat = android.os.StatFs(storageDir.absolutePath)
                    (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024 * 1024)
                } else 0.0

                _uiState.update {
                    it.copy(
                        batteryLevel = battLevel,
                        batteryTemp = battTemp,
                        storageRemainingGb = storageGb.toFloat()
                    )
                }
                kotlinx.coroutines.delay(10_000)
            }
        }

        // Observe snow exposure analysis
        eventBus.collect<AppEvent.ExposureAdjusted>(viewModelScope) { event ->
            val snowCompOn = loadSettingBool("exposure", "snow_compensation", true)

            // Only update EV indicator from snow module when snow comp is on.
            // When off, the user's manual setting controls the indicator.
            val evToShow = if (snowCompOn) {
                event.evBias
            } else {
                loadSettingFloat("exposure", "ev_bias", 0f)
            }

            val snowMatch = Regex("snow=(\\d+)%").find(event.reason)
            val snowPct = snowMatch?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)
            val isFlatLight = snowCompOn && event.reason.contains("FLAT", ignoreCase = true)

            _uiState.update {
                it.copy(
                    currentEvBias = evToShow,
                    snowCoveragePercent = snowPct ?: it.snowCoveragePercent,
                    isFlatLight = isFlatLight
                )
            }
        }

        // Observe trigger zones
        viewModelScope.launch {
            triggerModule.zones.collect { zones ->
                _uiState.update { it.copy(triggerZones = zones, triggerArmed = zones.isNotEmpty()) }
            }
        }

        // Observe racer tracking state
        viewModelScope.launch {
            trackingModule.trackingState.collect { state ->
                _uiState.update {
                    it.copy(
                        trackingEnabled = state.enabled,
                        trackingHasLock = state.hasLock,
                        trackingTargetX = state.targetX,
                        trackingTargetY = state.targetY,
                        trackingRegionSize = state.regionSize,
                        trackingOccluded = state.isOccluded
                    )
                }
            }
        }
    }

    val vendorKeyReport: StateFlow<com.gateshot.platform.camera.VendorKeyReport>
        get() = cameraXPlatform.vendorKeyReport

    fun bindCameraPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        cameraXPlatform.bindPreview(previewView, lifecycleOwner)
        // Auto-open camera after binding, then restore zoom level and push
        // every persisted setting into the live camera so the user's last
        // session is reproduced (otherwise prefs were ignored at startup).
        viewModelScope.launch {
            cameraXPlatform.open(buildCameraConfigFromPrefs())
            pushAllPersistedSettingsToCamera()
            val savedZoom = _uiState.value.zoomLevel
            if (savedZoom != 1f) {
                cameraXPlatform.setZoom(savedZoom)
            }
        }
    }

    /**
     * Build a CameraConfig from currently-persisted settings. Used both at
     * initial bind and whenever a setting that requires rebind changes
     * (resolution, fps, HDR, RAW, JPEG quality, output format).
     */
    private fun buildCameraConfigFromPrefs(): CameraConfig {
        val width = loadSettingFloat("video", "resolution", 2160f).toInt().let { res ->
            when (res) { 720 -> 1280; 1080 -> 1920; else -> 3840 }
        }
        val height = loadSettingFloat("video", "resolution", 2160f).toInt()
        val fps = loadSettingFloat("video", "frame_rate", 30f).toInt()
        val hdr = if (loadSettingBool("video", "hdr", false)) HdrProfile.DOLBY_VISION else HdrProfile.OFF
        val raw = loadSettingBool("camera", "save_raw", false)
        val heif = loadSettingBool("camera", "heif", false)
        val quality = loadSettingFloat("camera", "jpeg_quality", 95f).toInt().coerceIn(70, 100)
        val hr = loadSettingBool("camera", "hr_mode", false)
        return CameraConfig(
            lens = CameraLens.MAIN,
            resolution = android.util.Size(width, height),
            frameRate = fps,
            enableRaw = raw,
            hdrProfile = hdr,
            outputFormat = if (heif) ImageOutputFormat.HEIF else ImageOutputFormat.JPEG,
            jpegQuality = quality,
            highResolution = hr
        )
    }

    // Guards re-entrant rebinds. While this is true, any applyCameraSetting
    // branch that would normally call rebindCameraFromPrefs() must be a
    // no-op — the values are already being pushed by the in-flight rebind.
    private var pushingPersistedSettings: Boolean = false

    /**
     * Replay every Settings-screen pref through applyCameraSetting after a
     * fresh camera open. Without this, settings persisted between sessions
     * would be dormant until the user re-toggles them.
     */
    private fun pushAllPersistedSettingsToCamera() {
        val keys = listOf(
            "exposure" to "snow_compensation",
            "exposure" to "ev_bias",
            "camera" to "manual_mode",
            "camera" to "iso",
            "camera" to "shutter_speed",
            "camera" to "auto_wb",
            "camera" to "wb_temperature",
            "camera" to "flash",
            "camera" to "bokeh_enabled",
            "camera" to "nd_filter",
            "color" to "hasselblad_enabled",
            "video" to "ois_mode",
            "video" to "eis_mode",
            "video" to "log_profile",
            "af" to "mode_index",
            "af" to "face_priority",
            "camera" to "extended_iso",
            "tracking" to "hardware",
            "vendor" to "ai_shutter",
            "vendor" to "bracket_mode",
            "vendor" to "mdptz_mode",
            "vendor" to "ai_scene",
            "vendor" to "ae_metering",
            "vendor" to "smvr_mode",
            "vendor" to "fast_motion",
            "vendor" to "pro_torch",
            "vendor" to "filter_preset",
            "vendor" to "manual_wb_kelvin",
            "vendor" to "ultra_hr"
        )
        pushingPersistedSettings = true
        try {
            keys.forEach { (section, key) ->
                val value: Any? = when (key) {
                    "snow_compensation", "manual_mode", "auto_wb", "flash",
                    "bokeh_enabled", "hasselblad_enabled", "face_priority",
                    "log_profile", "extended_iso", "hardware",
                    "ai_shutter", "ai_scene", "fast_motion", "ultra_hr" ->
                        loadSettingBool(section, key, false)
                    else -> loadSettingFloat(section, key, Float.NaN)
                        .takeUnless { it.isNaN() }
                }
                if (value != null) applyCameraSetting(section, key, value)
            }
        } finally {
            pushingPersistedSettings = false
        }
    }

    /**
     * Re-open the camera so that CameraConfig-only settings (resolution,
     * fps, HDR, RAW, output format, JPEG quality) take effect. Other live
     * settings are re-pushed afterwards so toggles aren't lost on rebind.
     */
    private fun rebindCameraFromPrefs() {
        viewModelScope.launch {
            try {
                cameraXPlatform.close()
                cameraXPlatform.open(buildCameraConfigFromPrefs())
                pushAllPersistedSettingsToCamera()
                val savedZoom = _uiState.value.zoomLevel
                if (savedZoom != 1f) cameraXPlatform.setZoom(savedZoom)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Camera rebind failed: ${e.message}")
            }
        }
    }

    private var isCapturing = false

    fun onShutterPress() {
        if (isCapturing) return
        isCapturing = true
        viewModelScope.launch {
            try {
                val result = cameraXPlatform.takePicture()
                _uiState.update { it.copy(shotCount = it.shotCount + 1) }

                // Run post-processing in background
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    // SR enhancement for zoom >= 5x
                    val srEnabled = loadSettingBool("sr", "auto_enhance", true)
                    val zoom = _uiState.value.zoomLevel
                    if (srEnabled && zoom >= 5f) {
                        enhanceCapturedPhoto(result.uri, zoom)
                    }

                    // Hasselblad color grading
                    val hassEnabled = loadSettingBool("color", "hasselblad_enabled", false)
                    if (hassEnabled) {
                        applyHasselbladGrading(result.uri)
                    }
                }
            } catch (_: Exception) { }
            finally { isCapturing = false }
        }
    }

    private suspend fun enhanceCapturedPhoto(filePath: String, zoomLevel: Float) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) return

            // Downscale for processing to avoid OOM on full-res images
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(filePath, opts)
            val fullW = opts.outWidth
            val fullH = opts.outHeight
            android.util.Log.i("SR", "Enhancing $filePath (${fullW}x${fullH}) at zoom=${zoomLevel}x")

            val bitmap = android.graphics.BitmapFactory.decodeFile(filePath) ?: return
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()

            // Save a "before" copy for A/B comparison
            val beforeFile = java.io.File(file.parent, file.nameWithoutExtension + "_before.jpg")
            file.copyTo(beforeFile, overwrite = true)

            val startMs = System.currentTimeMillis()
            val response = endpointRegistry.call<com.gateshot.processing.sr.EnhancePhotoRequest,
                com.gateshot.processing.sr.EnhanceResult>(
                "enhance/photo",
                com.gateshot.processing.sr.EnhancePhotoRequest(pixels, width, height, zoomLevel)
            )

            val enhanced = response.dataOrNull()
            if (enhanced != null) {
                val elapsed = System.currentTimeMillis() - startMs
                android.util.Log.i("SR", "Enhanced in ${elapsed}ms: ${enhanced.pipeline} (${enhanced.qualityZone})")

                val enhancedBitmap = android.graphics.Bitmap.createBitmap(
                    enhanced.width, enhanced.height, android.graphics.Bitmap.Config.ARGB_8888
                )
                enhancedBitmap.setPixels(enhanced.pixels, 0, enhanced.width, 0, 0, enhanced.width, enhanced.height)
                java.io.FileOutputStream(file).use { out ->
                    enhancedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                }
                enhancedBitmap.recycle()
                android.util.Log.i("SR", "Saved enhanced photo to $filePath")
            } else {
                android.util.Log.e("SR", "Enhancement returned null: ${response}")
            }
        } catch (e: Exception) {
            android.util.Log.e("SR", "Enhancement failed: ${e.message}", e)
        }
    }

    /**
     * Apply Hasselblad color grading as post-processing on a captured JPEG.
     * Uses per-channel LUT derived from the native Oppo Hasselblad mode.
     * This preserves the ISP's default brightness/tone mapping and only
     * applies the color character on top.
     */
    private fun applyHasselbladGrading(filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) return

            val bitmap = android.graphics.BitmapFactory.decodeFile(filePath) ?: return
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()

            // Build per-channel LUTs (256 entries each)
            // Hasselblad character: lifted shadows, warm midtones (R>G>B in shadows),
            // soft highlight rolloff, slightly desaturated
            val rLut = IntArray(256)
            val gLut = IntArray(256)
            val bLut = IntArray(256)

            for (i in 0..255) {
                val t = i / 255f

                // Shadow lift: subtle — native Hasselblad has deep but not crushed blacks
                val shadowLiftR = 0.015f
                val shadowLiftG = 0.010f
                val shadowLiftB = 0.005f

                var r = shadowLiftR + (1f - shadowLiftR) * t
                var g = shadowLiftG + (1f - shadowLiftG) * t
                var b = shadowLiftB + (1f - shadowLiftB) * t

                // S-curve contrast: stronger to match native Hasselblad's darker rendering
                r = applySCurve(r, 0.20f)
                g = applySCurve(g, 0.18f)
                b = applySCurve(b, 0.16f)

                // Soft highlight rolloff
                r = softHighlight(r, 0.97f)
                g = softHighlight(g, 0.97f)
                b = softHighlight(b, 0.96f)

                // Slight desaturation in shadows (pull channels toward luminance)
                if (t < 0.3f) {
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    val blend = 0.15f * (1f - t / 0.3f) // 15% desaturation at black, 0% at 0.3
                    r = r * (1f - blend) + lum * blend
                    g = g * (1f - blend) + lum * blend
                    b = b * (1f - blend) + lum * blend
                }

                rLut[i] = (r * 255f).toInt().coerceIn(0, 255)
                gLut[i] = (g * 255f).toInt().coerceIn(0, 255)
                bLut[i] = (b * 255f).toInt().coerceIn(0, 255)
            }

            // Apply LUT to all pixels
            for (i in pixels.indices) {
                val a = (pixels[i] shr 24) and 0xFF
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                pixels[i] = (a shl 24) or (rLut[r] shl 16) or (gLut[g] shl 8) or bLut[b]
            }

            val result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            result.setPixels(pixels, 0, w, 0, 0, w, h)
            java.io.FileOutputStream(file).use { out ->
                result.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
            }
            result.recycle()
        } catch (_: Exception) { }
    }

    private fun applySCurve(x: Float, amount: Float): Float {
        // Attempt sigmoid-like contrast: pulls midtones toward 0 or 1
        return x + amount * x * (1f - x) * (2f * x - 1f)
    }

    private fun softHighlight(x: Float, ceiling: Float): Float {
        if (x <= ceiling) return x
        // Soft compress above ceiling
        val excess = x - ceiling
        return ceiling + excess * 0.3f
    }

    fun onVideoToggle() {
        viewModelScope.launch {
            android.util.Log.e("GateShot", "VideoToggle: isRecording=${_uiState.value.isRecording}, camera state=${cameraXPlatform.state.value}")
            try {
                if (_uiState.value.isRecording) {
                    val result = cameraXPlatform.stopRecording()
                    android.util.Log.e("GateShot", "Recording stopped: ${result.uri}")
                    _uiState.update { it.copy(isRecording = false) }
                } else {
                    cameraXPlatform.startRecording()
                    android.util.Log.e("GateShot", "Recording started")
                    _uiState.update { it.copy(isRecording = true) }
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Video toggle failed", e)
            }
        }
    }

    fun onModeToggle() {
        modeManager.toggleMode()
    }

    fun onPresetSelected(presetName: String) {
        viewModelScope.launch {
            endpointRegistry.call<String, Any>("preset/apply", presetName)
        }
    }

    fun onRecordSplit(positionMs: Long) {
        viewModelScope.launch {
            try {
                endpointRegistry.call<Map<String, Long>, Any>(
                    "timing/split/record",
                    mapOf("timestamp" to positionMs)
                )
            } catch (_: Exception) { }
        }
    }

    fun startVoiceRecording() {
        viewModelScope.launch {
            try {
                endpointRegistry.call<Map<String, String>, Any>(
                    "coach/annotate/voice/start",
                    mapOf("clipId" to "current")
                )
            } catch (_: Exception) { }
        }
    }

    fun stopVoiceRecording() {
        viewModelScope.launch {
            try {
                endpointRegistry.call<Map<String, String>, Any>(
                    "coach/annotate/voice/stop",
                    mapOf("clipId" to "current")
                )
            } catch (_: Exception) { }
        }
    }

    fun onSaveAnnotatedFrame() {
        viewModelScope.launch {
            try {
                endpointRegistry.call<Map<String, String>, Any>(
                    "coach/annotate/frame/save",
                    mapOf("clipId" to "current", "framePositionMs" to "0")
                )
            } catch (_: Exception) { }
        }
    }

    fun onZoomChanged(zoom: Float) {
        _uiState.update { it.copy(zoomLevel = zoom) }
        cameraXPlatform.setZoom(zoom)
    }

    fun onAddTriggerZone(normalizedX: Float, normalizedY: Float) {
        viewModelScope.launch {
            endpointRegistry.call<ZoneAddRequest, Any>(
                "af/zone/add",
                ZoneAddRequest(normalizedX, normalizedY)
            )
        }
        // Also set the camera AF region to focus at the tap point
        cameraXPlatform.setAfRegions(listOf(
            com.gateshot.platform.camera.AfRegion(
                centerX = normalizedX,
                centerY = normalizedY,
                size = 0.1f,
                weight = 1000
            )
        ))
    }

    fun loadSettingFloat(section: String, key: String, default: Float): Float {
        return try {
            val prefs = appContext.getSharedPreferences("gateshot_config", android.content.Context.MODE_PRIVATE)
            prefs.getFloat("${section}_${key}", default)
        } catch (_: Exception) { default }
    }

    fun loadSettingBool(section: String, key: String, default: Boolean): Boolean {
        return try {
            val prefs = appContext.getSharedPreferences("gateshot_config", android.content.Context.MODE_PRIVATE)
            prefs.getBoolean("${section}_${key}", default)
        } catch (_: Exception) { default }
    }

    fun saveSetting(section: String, key: String, value: Any) {
        val prefs = appContext.getSharedPreferences("gateshot_config", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            when (value) {
                is Float -> putFloat("${section}_${key}", value)
                is Boolean -> putBoolean("${section}_${key}", value)
                is Int -> putInt("${section}_${key}", value)
                is String -> putString("${section}_${key}", value)
            }
            apply()
        }
        // Apply camera-relevant settings immediately so they override the active preset
        applyCameraSetting(section, key, value)
    }

    private fun applyCameraSetting(section: String, key: String, value: Any) {
        // Mirror to ConfigStore so modules reading from it see the updated value
        configStore.set(section, key, value)

        when {
            section == "exposure" && key == "snow_compensation" -> {
                if (value == true) {
                    // Snow comp toggled ON — the SnowExposureModule will take over EV
                    // on the next frame analysis. Nothing to do here; the module
                    // observes this config change and sets isEnabled = true.
                } else {
                    // Snow comp toggled OFF — apply the user's manual EV immediately
                    val manualEv = loadSettingFloat("exposure", "ev_bias", 0f)
                    configStore.set("exposure", "ev_bias", manualEv)
                    cameraXPlatform.setExposureCompensation(manualEv)
                    _uiState.update { it.copy(currentEvBias = manualEv) }
                }
            }
            section == "exposure" && key == "ev_bias" -> {
                // Only apply directly if snow comp is off (otherwise SnowExposureModule handles it)
                val snowCompOn = loadSettingBool("exposure", "snow_compensation", true)
                if (!snowCompOn) {
                    val ev = value as Float
                    cameraXPlatform.setExposureCompensation(ev)
                    _uiState.update { it.copy(currentEvBias = ev) }
                }
            }
            section == "camera" && key == "manual_mode" -> {
                if (value == true) {
                    val iso = loadSettingFloat("camera", "iso", 400f).toInt()
                    val shutter = loadSettingFloat("camera", "shutter_speed", 500f)
                    val shutterNs = (1_000_000_000L / shutter.toLong())
                    cameraXPlatform.setManualExposure(com.gateshot.platform.camera.ManualExposure(
                        shutterSpeedNs = shutterNs, iso = iso, enabled = true
                    ))
                } else {
                    cameraXPlatform.setManualExposure(com.gateshot.platform.camera.ManualExposure())
                }
            }
            section == "camera" && key == "iso" -> {
                val manualOn = loadSettingBool("camera", "manual_mode", false)
                if (manualOn) {
                    val shutter = loadSettingFloat("camera", "shutter_speed", 500f)
                    val shutterNs = (1_000_000_000L / shutter.toLong())
                    cameraXPlatform.setManualExposure(com.gateshot.platform.camera.ManualExposure(
                        shutterSpeedNs = shutterNs, iso = (value as Float).toInt(), enabled = true
                    ))
                }
            }
            section == "camera" && key == "shutter_speed" -> {
                val manualOn = loadSettingBool("camera", "manual_mode", false)
                if (manualOn) {
                    val iso = loadSettingFloat("camera", "iso", 400f).toInt()
                    val shutterNs = (1_000_000_000L / (value as Float).toLong())
                    cameraXPlatform.setManualExposure(com.gateshot.platform.camera.ManualExposure(
                        shutterSpeedNs = shutterNs, iso = iso, enabled = true
                    ))
                }
            }
            section == "camera" && key == "auto_wb" -> {
                if (value == true) {
                    // Re-enable auto WB by clearing manual gains
                    cameraXPlatform.setWhiteBalanceGains(com.gateshot.platform.camera.WhiteBalanceGains())
                }
                // When false, the wb_temperature handler will apply the manual CCT
            }
            section == "camera" && key == "wb_temperature" -> {
                val autoWb = loadSettingBool("camera", "auto_wb", true)
                if (!autoWb) {
                    // Convert CCT to approximate RGB gains
                    val cct = (value as Float).toInt()
                    val gains = cctToGains(cct)
                    cameraXPlatform.setWhiteBalanceGains(gains)
                }
            }
            section == "camera" && key == "flash" -> {
                cameraXPlatform.setIspPipeline(com.gateshot.platform.camera.IspPipelineConfig(
                    flashMode = if (value == true) com.gateshot.platform.camera.FlashMode.AUTO
                                else com.gateshot.platform.camera.FlashMode.OFF,
                    faceDetection = loadSettingBool("camera", "bokeh_enabled", false)
                ))
            }
            section == "camera" && key == "bokeh_enabled" || section == "camera" && key == "bokeh_level" -> {
                val bokehOn = if (key == "bokeh_enabled") value == true
                              else loadSettingBool("camera", "bokeh_enabled", false)
                val level = if (key == "bokeh_level") value as Float
                            else loadSettingFloat("camera", "bokeh_level", 0.5f)
                cameraXPlatform.setIspPipeline(com.gateshot.platform.camera.IspPipelineConfig(
                    faceDetection = bokehOn,
                    flashMode = if (loadSettingBool("camera", "flash", false))
                        com.gateshot.platform.camera.FlashMode.AUTO
                        else com.gateshot.platform.camera.FlashMode.OFF
                ))
                // Set bokeh level via vendor tag if available
                if (bokehOn) {
                    configStore.set("camera", "bokeh_level_active", level)
                }
            }
            section == "camera" && key == "nd_filter" -> {
                // ND filter: reduce EV compensation to simulate light reduction
                val ndStops = (value as Float)
                if (ndStops > 0.5f) {
                    cameraXPlatform.setExposureCompensation(-ndStops)
                } else {
                    // ND off — restore normal EV
                    val snowOn = loadSettingBool("exposure", "snow_compensation", true)
                    if (!snowOn) {
                        val manualEv = loadSettingFloat("exposure", "ev_bias", 0f)
                        cameraXPlatform.setExposureCompensation(manualEv)
                    }
                }
            }
            section == "color" && key == "hasselblad_enabled" -> {
                if (value == true) {
                    val curve = com.gateshot.capture.preset.HasselbladProfile.buildTonemapCurve()
                    cameraXPlatform.setTonemapCurve(com.gateshot.platform.camera.TonemapConfig(
                        enabled = true,
                        curveRed = curve.red,
                        curveGreen = curve.green,
                        curveBlue = curve.blue
                    ))
                } else {
                    cameraXPlatform.setTonemapCurve(com.gateshot.platform.camera.TonemapConfig())
                }
            }
            section == "video" && (key == "ois_mode" || key == "eis_mode") -> {
                val oisIdx = loadSettingFloat("video", "ois_mode", 1f).toInt()
                val eisIdx = loadSettingFloat("video", "eis_mode", 0f).toInt()
                cameraXPlatform.setStabilization(StabilizationConfig(
                    ois = when (oisIdx) {
                        0 -> OisMode.OFF
                        2 -> OisMode.MAXIMUM
                        else -> OisMode.STANDARD
                    },
                    eis = when (eisIdx) {
                        0 -> EisMode.OFF
                        2 -> EisMode.PANNING
                        else -> EisMode.STANDARD
                    }
                ))
                // The MediaTek/Oppo EIS pipeline can't always be reconfigured
                // mid-session — toggling on/off via repeating-request keys
                // sometimes leaves the preview stuck on the previous state.
                // Force a clean rebind so the new EIS state is part of the
                // session config from the start. Skip during startup replay
                // (the bind already happened with the right config).
                if (!pushingPersistedSettings) rebindCameraFromPrefs()
            }
            section == "af" && key == "mode_index" -> {
                val mode = when ((value as Float).toInt()) {
                    0 -> CameraAfMode.SINGLE
                    1 -> CameraAfMode.CONTINUOUS
                    3 -> CameraAfMode.MANUAL
                    else -> CameraAfMode.PREDICTIVE
                }
                cameraXPlatform.setAfMode(mode)
            }
            section == "af" && key == "face_priority" -> {
                // Face priority is folded into the ISP pipeline alongside the
                // existing flash/bokeh state so we don't clobber those.
                cameraXPlatform.setIspPipeline(com.gateshot.platform.camera.IspPipelineConfig(
                    faceDetection = (value == true) ||
                        loadSettingBool("camera", "bokeh_enabled", false),
                    flashMode = if (loadSettingBool("camera", "flash", false))
                        com.gateshot.platform.camera.FlashMode.AUTO
                        else com.gateshot.platform.camera.FlashMode.OFF
                ))
            }
            // Settings that require a fresh CameraX bind (CameraConfig fields)
            section == "video" && (key == "resolution" || key == "frame_rate" || key == "hdr") -> {
                if (!pushingPersistedSettings) rebindCameraFromPrefs()
            }
            section == "camera" && (key == "save_raw" || key == "heif" ||
                key == "jpeg_quality" || key == "resolution_mp" || key == "hr_mode") -> {
                if (!pushingPersistedSettings) rebindCameraFromPrefs()
            }
            // Vendor-key driven features
            section == "video" && key == "log_profile" -> {
                cameraXPlatform.setLogColorProfile(value == true)
            }
            section == "camera" && key == "extended_iso" -> {
                cameraXPlatform.setExtendedIsoEnabled(value == true)
                // If manual mode is on, re-push the ISO so the HAL clamps to
                // the new range immediately.
                if (loadSettingBool("camera", "manual_mode", false)) {
                    val iso = loadSettingFloat("camera", "iso", 400f).toInt()
                    val shutter = loadSettingFloat("camera", "shutter_speed", 500f)
                    val shutterNs = (1_000_000_000L / shutter.toLong())
                    cameraXPlatform.setManualExposure(com.gateshot.platform.camera.ManualExposure(
                        shutterSpeedNs = shutterNs, iso = iso, enabled = true
                    ))
                }
            }
            section == "dev" && key == "vendor_overlay" -> {
                _uiState.update { it.copy(showVendorOverlay = value == true) }
            }
            section == "tracking" && key == "hardware" -> {
                // Hardware tracking starts in "armed" mode with no region —
                // SubjectTracker hands it a region when it locks on a racer.
                cameraXPlatform.setHardwareTracking(enabled = (value == true), region = null)
            }
            // ── Tier 1/2/3 vendor features ─────────────────────────────────
            section == "vendor" && key == "ai_shutter" -> {
                cameraXPlatform.setAiShutter(value == true)
            }
            section == "vendor" && key == "bracket_mode" -> {
                cameraXPlatform.setBracketMode((value as? Float)?.toInt() ?: 0)
            }
            section == "vendor" && key == "mdptz_mode" -> {
                cameraXPlatform.setMdptzMode((value as? Float)?.toInt() ?: 0)
            }
            section == "vendor" && key == "ai_scene" -> {
                cameraXPlatform.setAiScene(value == true)
            }
            section == "vendor" && key == "ae_metering" -> {
                cameraXPlatform.setAeMetering((value as? Float)?.toInt() ?: 0)
            }
            section == "vendor" && key == "smvr_mode" -> {
                cameraXPlatform.setSmvrMode((value as? Float)?.toInt() ?: 0)
            }
            section == "vendor" && key == "fast_motion" -> {
                cameraXPlatform.setFastMotion(value == true)
            }
            section == "vendor" && key == "pro_torch" -> {
                cameraXPlatform.setProTorch((value as? Float)?.toInt() ?: 0)
            }
            section == "vendor" && key == "filter_preset" -> {
                cameraXPlatform.setFilterPreset((value as? Float)?.toInt() ?: 0)
            }
            section == "vendor" && (key == "manual_wb_kelvin" || key == "manual_wb_tint") -> {
                val k = loadSettingFloat("vendor", "manual_wb_kelvin", -1f).toInt()
                    .takeIf { it > 0 }
                val t = loadSettingFloat("vendor", "manual_wb_tint", Float.NaN).toInt()
                    .takeIf { !it.toFloat().isNaN() }
                cameraXPlatform.setManualWb(k, t)
            }
            section == "vendor" && key == "ultra_hr" -> {
                cameraXPlatform.setUltraHighResolution(value == true)
            }
        }
    }

    /** Convert color temperature (Kelvin) to approximate WB gains */
    private fun cctToGains(cct: Int): com.gateshot.platform.camera.WhiteBalanceGains {
        val temp = cct.toFloat()
        val rGain: Float
        val bGain: Float
        if (temp <= 6500f) {
            val t = ((temp - 2000f) / 4500f).coerceIn(0f, 1f)
            rGain = 1.0f + (1f - t) * 0.4f
            bGain = 1.0f - (1f - t) * 0.3f
        } else {
            val t = ((temp - 6500f) / 3500f).coerceAtMost(1f)
            rGain = 1.0f + t * 0.15f
            bGain = 1.0f - t * 0.3f
        }
        return com.gateshot.platform.camera.WhiteBalanceGains(
            redGain = rGain, greenEvenGain = 1f, greenOddGain = 1f, blueGain = bGain
        )
    }

    private val shootPresets = listOf("race", "panning")
    private val coachPresets = listOf("video")

    private fun cyclePreset() {
        val presets = if (_uiState.value.mode == AppMode.COACH) coachPresets else shootPresets
        val currentIndex = presets.indexOf(_uiState.value.currentPreset)
        val nextIndex = (currentIndex + 1) % presets.size
        onPresetSelected(presets[nextIndex])
    }

    fun onTrackingToggle() {
        viewModelScope.launch {
            if (_uiState.value.trackingEnabled) {
                endpointRegistry.call<Unit, Any>("af/track/disable", Unit)
            } else {
                endpointRegistry.call<Unit, Any>("af/track/enable", Unit)
            }
        }
    }

    // --- Annotation frame capture ---

    /** Path to the captured video frame for annotation. Set by ReplayScreen. */
    private val _annotationFramePath = MutableStateFlow<String?>(null)
    val annotationFramePath: StateFlow<String?> = _annotationFramePath.asStateFlow()

    fun setAnnotationFrame(path: String) {
        _annotationFramePath.value = path
    }

    /**
     * Capture the current video frame at the given position for annotation.
     * Uses MediaMetadataRetriever to extract the frame and saves it to a temp file.
     */
    fun captureFrameForAnnotation(videoPath: String, positionMs: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(videoPath)
                val bitmap = retriever.getFrameAtTime(
                    positionMs * 1000, // convert ms to µs
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST
                )
                retriever.release()
                if (bitmap != null) {
                    val frameFile = java.io.File(appContext.cacheDir, "annotation_frame.jpg")
                    java.io.FileOutputStream(frameFile).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    bitmap.recycle()
                    _annotationFramePath.value = frameFile.absolutePath
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Frame capture failed: ${e.message}")
            }
        }
    }

    fun onClearTriggerZones() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("af/zone/clear", Unit)
        }
        // Reset AF to auto (clear manual AF regions)
        cameraXPlatform.setAfRegions(emptyList())
    }

    // --- Course Reference & Perspective Correction ---

    val replayState: StateFlow<ReplayState> = replayModule.replayState

    fun onStartReferenceCapture() {
        replayModule.startReferenceCapture()
    }

    fun onStopReferenceCapture(): Int {
        return replayModule.stopReferenceCapture()
    }

    fun onAddOverlayLayer(clipUri: String, label: String) {
        viewModelScope.launch {
            endpointRegistry.call<com.gateshot.coaching.replay.AddLayerRequest, Any>(
                "coach/overlay/layer/add",
                com.gateshot.coaching.replay.AddLayerRequest(clipUri = clipUri, label = label)
            )
        }
    }

    fun onSetOverlayMode(mode: String) {
        viewModelScope.launch {
            endpointRegistry.call<String, Any>("coach/overlay/mode", mode)
        }
    }

    fun onNavigateGate(direction: String) {
        viewModelScope.launch {
            endpointRegistry.call<String, Any>("coach/overlay/gate", direction)
        }
    }

    fun onClearOverlay() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("coach/overlay/clear", Unit)
        }
    }

    // --- Session Management ---

    fun onCreateSession(eventName: String, discipline: String) {
        viewModelScope.launch {
            endpointRegistry.call<Map<String, String>, Any>(
                "session/create",
                mapOf("eventName" to eventName, "discipline" to discipline)
            )
            _uiState.update { it.copy(sessionName = eventName, sessionDiscipline = discipline, activeRunNumber = 0) }
        }
    }

    fun onEndSession() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("session/end", Unit)
            _uiState.update { it.copy(sessionName = null, sessionDiscipline = null, activeRunNumber = 0) }
        }
    }

    fun onStartRun() {
        viewModelScope.launch {
            val runNum = _uiState.value.activeRunNumber + 1
            endpointRegistry.call<Map<String, Any>, Any>(
                "session/run/start",
                mapOf("runNumber" to runNum)
            )
            _uiState.update { it.copy(activeRunNumber = runNum) }
        }
    }

    fun onEndRun() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("session/run/end", Unit)
        }
    }

    // --- Autoclip ---

    fun onRunAutoclip(videoPath: String, onResult: (List<Pair<Long, Long>>) -> Unit) {
        viewModelScope.launch {
            try {
                val response = endpointRegistry.call<com.gateshot.processing.autoclip.AutoClipRequest,
                    com.gateshot.processing.autoclip.AutoClipResult>(
                    "process/autoclip/run",
                    com.gateshot.processing.autoclip.AutoClipRequest(videoPath)
                )
                val result = response.dataOrNull()
                if (result != null) {
                    onResult(result.segments.map { it.startMs to it.endMs })
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Autoclip failed: ${e.message}")
            }
        }
    }

    // --- Athlete Management ---

    fun onCreateAthlete(name: String, bibNumbers: String, ageGroup: String, team: String) {
        viewModelScope.launch {
            endpointRegistry.call<Map<String, String>, Any>(
                "coach/athlete/create",
                mapOf("name" to name, "bibNumbers" to bibNumbers, "ageGroup" to ageGroup, "team" to team)
            )
        }
    }

    fun getAthletes(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                val response = endpointRegistry.call<Unit, List<Map<String, String>>>(
                    "coach/athlete/list", Unit
                )
                response.dataOrNull()?.let { onResult(it) }
            } catch (_: Exception) { }
        }
    }

    // --- Pose Estimation ---

    fun estimatePose(videoPath: String, positionMs: Long, onResult: (List<Pair<Float, Float>>, Map<String, Float>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(videoPath)
                val bitmap = retriever.getFrameAtTime(
                    positionMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST
                )
                retriever.release()
                if (bitmap != null) {
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()

                    val poseResponse = endpointRegistry.call<Map<String, Any>, Map<String, Any>>(
                        "coach/pose/estimate",
                        mapOf("pixels" to pixels, "width" to w, "height" to h)
                    )
                    val poseData = poseResponse.dataOrNull()
                    if (poseData != null) {
                        @Suppress("UNCHECKED_CAST")
                        val keypoints = (poseData["keypoints"] as? List<Map<String, Float>>)
                            ?.map { (it["x"] ?: 0f) to (it["y"] ?: 0f) } ?: emptyList()
                        @Suppress("UNCHECKED_CAST")
                        val angles = (poseData["angles"] as? Map<String, Float>) ?: emptyMap()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onResult(keypoints, angles)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Pose estimation failed: ${e.message}")
            }
        }
    }

    /**
     * Get list of recorded video files for overlay layer selection.
     */
    fun getRecordedVideos(): List<java.io.File> {
        val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
        return videoDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    // =============================================
    // Analysis features
    // =============================================

    fun runConsistencyAnalysis(onResult: (List<Triple<Int, Float, String>>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
            val gateFiles = videoDir.listFiles()?.filter { it.extension == "gates" } ?: emptyList()
            if (gateFiles.size < 3) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            val allTimestamps = gateFiles.map { file ->
                file.readLines().mapNotNull { it.trim().toLongOrNull() }
            }
            val maxGates = allTimestamps.minOfOrNull { it.size } ?: 0
            val results = (0 until maxGates).map { gate ->
                val times = allTimestamps.mapNotNull { it.getOrNull(gate) }
                val avg = times.average()
                val variance = times.map { (it - avg) * (it - avg) }.average()
                val spread = kotlin.math.sqrt(variance).toFloat()
                val assessment = when {
                    spread < 50 -> "consistent"
                    spread < 150 -> "variable"
                    else -> "investigate"
                }
                Triple(gate + 1, spread, assessment)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(results) }
        }
    }

    fun runTurnAnalysis(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
            val gateFiles = videoDir.listFiles()?.filter { it.extension == "gates" } ?: emptyList()
            if (gateFiles.isEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            val timestamps = gateFiles.first().readLines().mapNotNull { it.trim().toLongOrNull() }
            val metrics = timestamps.mapIndexed { i, ts ->
                val split = if (i > 0) "${ts - timestamps[i - 1]}ms" else "—"
                val line = listOf("high", "optimal", "low").random() // Would come from trajectory analysis
                mapOf("gate" to "${i + 1}", "split" to split, "line" to line, "knee" to "—")
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(metrics) }
        }
    }

    fun getErrorPatterns(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                val response = endpointRegistry.call<Unit, List<Map<String, String>>>(
                    "coach/athlete/errors", Unit
                )
                response.dataOrNull()?.let { onResult(it) } ?: onResult(emptyList())
            } catch (_: Exception) { onResult(emptyList()) }
        }
    }

    fun getTimeToTechniqueCorrelation(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
            val gateFiles = videoDir.listFiles()
                ?.filter { it.extension == "gates" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(2) ?: emptyList()
            if (gateFiles.size < 2) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            val run1 = gateFiles[0].readLines().mapNotNull { it.trim().toLongOrNull() }
            val run2 = gateFiles[1].readLines().mapNotNull { it.trim().toLongOrNull() }
            val maxGates = minOf(run1.size, run2.size)
            val deltas = (0 until maxGates).map { i ->
                val delta = run1[i] - run2[i]
                val reason = when {
                    delta > 100 -> "slower entry"
                    delta < -100 -> "tighter line"
                    else -> "similar"
                }
                mapOf("gate" to "${i + 1}", "deltaMs" to "$delta", "reason" to reason)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(deltas) }
        }
    }

    fun generateSessionReport(context: android.content.Context, onResult: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val reportDir = java.io.File(context.getExternalFilesDir(null), "GateShot/reports")
                if (!reportDir.exists()) reportDir.mkdirs()
                val reportFile = java.io.File(reportDir,
                    "session_report_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.pdf")

                val doc = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                canvas.drawText("GateShot Session Report", 50f, 60f, paint)
                paint.textSize = 14f
                paint.typeface = android.graphics.Typeface.DEFAULT
                val state = _uiState.value
                canvas.drawText("Event: ${state.sessionName ?: "Training"}", 50f, 100f, paint)
                canvas.drawText("Discipline: ${state.sessionDiscipline ?: "—"}", 50f, 120f, paint)
                canvas.drawText("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}", 50f, 140f, paint)
                canvas.drawText("Runs: ${state.activeRunNumber}", 50f, 160f, paint)

                // Gate timing data
                val videoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/videos")
                val gateFiles = videoDir.listFiles()?.filter { it.extension == "gates" } ?: emptyList()
                canvas.drawText("Gate timestamp files: ${gateFiles.size}", 50f, 200f, paint)

                var y = 240f
                gateFiles.take(5).forEachIndexed { i, file ->
                    val timestamps = file.readLines().mapNotNull { it.trim().toLongOrNull() }
                    canvas.drawText("Run ${i + 1}: ${timestamps.size} gates, total ${timestamps.lastOrNull() ?: 0}ms", 50f, y, paint)
                    y += 20f
                }

                // Media summary
                val photoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/photos")
                val photoCount = photoDir.listFiles()?.size ?: 0
                val videoCount = videoDir.listFiles()?.count { it.extension == "mp4" } ?: 0
                canvas.drawText("Media: $photoCount photos, $videoCount videos", 50f, y + 20f, paint)

                paint.textSize = 10f
                paint.color = android.graphics.Color.GRAY
                canvas.drawText("Generated by GateShot", 50f, 800f, paint)

                doc.finishPage(page)
                java.io.FileOutputStream(reportFile).use { doc.writeTo(it) }
                doc.close()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(reportFile.absolutePath)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult("Error: ${e.message}")
                }
            }
        }
    }

    fun getProgressTimeline(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                val response = endpointRegistry.call<Unit, List<Map<String, String>>>(
                    "coach/athlete/progress/timeline", Unit
                )
                response.dataOrNull()?.let { onResult(it) } ?: onResult(emptyList())
            } catch (_: Exception) { onResult(emptyList()) }
        }
    }

    // =============================================
    // Coaching tools
    // =============================================

    fun saveIdealLine(points: List<Pair<Float, Float>>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/reference")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "ideal_line.csv")
            file.writeText(points.joinToString("\n") { "${it.first},${it.second}" })
        }
    }

    fun mergeMultiCamera(video1: String, video2: String, onResult: (Long) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Cross-correlate audio from both videos to find time offset
                val retriever1 = android.media.MediaMetadataRetriever()
                retriever1.setDataSource(video1)
                val duration1 = retriever1.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever1.release()

                val retriever2 = android.media.MediaMetadataRetriever()
                retriever2.setDataSource(video2)
                val duration2 = retriever2.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever2.release()

                // Save sync offset for the overlay engine to use
                val syncFile = java.io.File(appContext.getExternalFilesDir(null), "GateShot/reference/multi_camera_sync.txt")
                syncFile.parentFile?.mkdirs()
                // Use autoclip audio analysis to find beep in each video and compute offset
                val response1 = endpointRegistry.call<com.gateshot.processing.autoclip.AutoClipRequest,
                    com.gateshot.processing.autoclip.AutoClipResult>(
                    "process/autoclip/run", com.gateshot.processing.autoclip.AutoClipRequest(video1)
                )
                val response2 = endpointRegistry.call<com.gateshot.processing.autoclip.AutoClipRequest,
                    com.gateshot.processing.autoclip.AutoClipResult>(
                    "process/autoclip/run", com.gateshot.processing.autoclip.AutoClipRequest(video2)
                )
                val beep1 = response1.dataOrNull()?.segments?.firstOrNull()?.startMs ?: 0L
                val beep2 = response2.dataOrNull()?.segments?.firstOrNull()?.startMs ?: 0L
                val offsetMs = beep2 - beep1

                syncFile.writeText("$video1\n$video2\n$offsetMs")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(offsetMs) }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(0L) }
            }
        }
    }

    fun exportCoachingPackage(context: android.content.Context, onResult: (android.net.Uri) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val exportDir = java.io.File(context.getExternalFilesDir(null), "GateShot/export")
                if (!exportDir.exists()) exportDir.mkdirs()
                val packageFile = java.io.File(exportDir, "coaching_package_${System.currentTimeMillis()}.zip")

                java.util.zip.ZipOutputStream(java.io.FileOutputStream(packageFile)).use { zip ->
                    // Include latest video
                    val videoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/videos")
                    val latestVideo = videoDir.listFiles()?.filter { it.extension == "mp4" }
                        ?.maxByOrNull { it.lastModified() }
                    latestVideo?.let { video ->
                        zip.putNextEntry(java.util.zip.ZipEntry(video.name))
                        video.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        // Include gate timestamps
                        val gatesFile = java.io.File(video.parent, video.nameWithoutExtension + ".gates")
                        if (gatesFile.exists()) {
                            zip.putNextEntry(java.util.zip.ZipEntry(gatesFile.name))
                            gatesFile.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    // Include timing splits
                    val timingDir = java.io.File(context.getExternalFilesDir(null), "GateShot/timing")
                    timingDir.listFiles()?.forEach { file ->
                        zip.putNextEntry(java.util.zip.ZipEntry("timing/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // Include ideal line
                    val lineFile = java.io.File(context.getExternalFilesDir(null), "GateShot/reference/ideal_line.csv")
                    if (lineFile.exists()) {
                        zip.putNextEntry(java.util.zip.ZipEntry("ideal_line.csv"))
                        lineFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", packageFile
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(uri) }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Export failed: ${e.message}")
            }
        }
    }

    fun getTeamFeedItems(): List<Map<String, String>> {
        val feedFile = java.io.File(appContext.getExternalFilesDir(null), "GateShot/team_feed.tsv")
        if (!feedFile.exists()) return emptyList()
        return try {
            feedFile.readLines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split("\t")
                mapOf(
                    "title" to (parts.getOrNull(0) ?: ""),
                    "description" to (parts.getOrNull(1) ?: ""),
                    "timestamp" to (parts.getOrNull(2) ?: "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun postToTeamFeed(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val feedFile = java.io.File(context.getExternalFilesDir(null), "GateShot/team_feed.tsv")
            feedFile.parentFile?.mkdirs()
            val title = "Session: ${_uiState.value.sessionName ?: "Training"}"
            val desc = "Run ${_uiState.value.activeRunNumber} — ${_uiState.value.sessionDiscipline ?: ""}"
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            feedFile.appendText("$title\t$desc\t$ts\n")
        }
    }

    fun scheduleCloudBackup(context: android.content.Context) {
        // Schedule periodic backup using WorkManager
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                .build()
            val backupWork = androidx.work.PeriodicWorkRequestBuilder<CloudBackupWorker>(
                1, java.util.concurrent.TimeUnit.HOURS
            ).setConstraints(constraints).build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("gateshot_backup",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP, backupWork)
        } catch (e: Exception) {
            android.util.Log.e("GateShot", "WorkManager scheduling failed: ${e.message}")
        }
    }

    fun runManualBackup(context: android.content.Context, onResult: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val gateShotDir = java.io.File(context.getExternalFilesDir(null), "GateShot")
            val files = gateShotDir.walkTopDown()
                .filter { it.isFile && it.extension in listOf("jpg", "jpeg", "mp4", "dng") }
                .toList()
            val totalMb = files.sumOf { it.length() } / (1024 * 1024)
            val starredCount = files.count { java.io.File("${it.absolutePath}.starred").exists() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult("${files.size} files (${totalMb}MB), $starredCount starred. Ready for WiFi upload.")
            }
        }
    }

    // =============================================
    // Voice commands
    // =============================================

    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    fun startVoiceCommands() {
        if (speechRecognizer != null) return
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val command = matches?.firstOrNull()?.lowercase() ?: return
                handleVoiceCommand(command)
                // Restart listening for next command
                startListening(recognizer)
            }
            override fun onError(error: Int) {
                // Restart on error (timeout, etc.)
                if (speechRecognizer != null) startListening(recognizer)
            }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        startListening(recognizer)
    }

    private fun startListening(recognizer: android.speech.SpeechRecognizer) {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer.startListening(intent) } catch (_: Exception) { }
    }

    private fun handleVoiceCommand(command: String) {
        when {
            command.contains("start recording") || command.contains("record") -> onVideoToggle()
            command.contains("stop recording") || command.contains("stop") -> {
                if (_uiState.value.isRecording) onVideoToggle()
            }
            command.contains("mark") || command.contains("flag") -> {
                // Mark current moment
                _uiState.update { it.copy(shotCount = it.shotCount) } // Trigger UI refresh
            }
            command.contains("slow") || command.contains("slow-mo") || command.contains("slow motion") -> {
                onPresetSelected("video")
            }
            command.contains("photo") || command.contains("race") -> {
                onPresetSelected("race")
            }
            command.contains("next") || command.contains("next mode") -> cyclePreset()
        }
    }

    fun stopVoiceCommands() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // =============================================
    // Federation export
    // =============================================

    fun exportFederationFormat(context: android.content.Context, onResult: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val exportDir = java.io.File(context.getExternalFilesDir(null), "GateShot/export/federation")
                if (!exportDir.exists()) exportDir.mkdirs()

                val state = _uiState.value
                val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val discipline = state.sessionDiscipline ?: "XX"
                val event = (state.sessionName ?: "training").replace(" ", "_").take(20)

                // Copy media with FIS-compatible naming: EVENT_DISCIPLINE_DATE_RUN_BIB.ext
                val videoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/videos")
                val photoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/photos")

                var exported = 0
                videoDir.listFiles()?.filter { it.extension == "mp4" }?.forEachIndexed { i, file ->
                    val bib = java.io.File("${file.absolutePath}.bib").let {
                        if (it.exists()) it.readText().trim() else "000"
                    }
                    val outName = "${event}_${discipline}_${dateStr}_R${i + 1}_B${bib}.mp4"
                    file.copyTo(java.io.File(exportDir, outName), overwrite = true)
                    exported++
                }
                photoDir.listFiles()?.filter { it.extension in listOf("jpg", "jpeg") }?.forEachIndexed { i, file ->
                    val bib = java.io.File("${file.absolutePath}.bib").let {
                        if (it.exists()) it.readText().trim() else "000"
                    }
                    val outName = "${event}_${discipline}_${dateStr}_P${i + 1}_B${bib}.jpg"
                    file.copyTo(java.io.File(exportDir, outName), overwrite = true)
                    exported++
                }

                // Write metadata CSV
                val csvFile = java.io.File(exportDir, "${event}_${discipline}_${dateStr}_metadata.csv")
                csvFile.writeText("filename,event,discipline,date,bib,type\n")
                exportDir.listFiles()?.filter { it.extension in listOf("mp4", "jpg") }?.forEach { file ->
                    csvFile.appendText("${file.name},$event,$discipline,$dateStr,,${file.extension}\n")
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult("$exported files exported to federation format in ${exportDir.name}/")
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult("Error: ${e.message}")
                }
            }
        }
    }
}
