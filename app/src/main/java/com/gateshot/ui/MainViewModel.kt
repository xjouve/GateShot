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
import com.gateshot.capture.tracking.TrackingFeatureModule
import com.gateshot.capture.trigger.TriggerFeatureModule
import com.gateshot.capture.trigger.TriggerZone
import com.gateshot.capture.trigger.ZoneAddRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gateshot.platform.camera.CameraConfig
import com.gateshot.platform.camera.CameraXPlatform
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
    val currentPreset: String = "slalom_gs",
    val presetDisplayName: String = "Slalom / GS",
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
    val moduleStatuses: Map<String, String> = emptyMap()
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
    private val configStore: ConfigStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        android.util.Log.e("GateShot", "MainViewModel INIT — camera state: ${cameraXPlatform.state.value}")
        // Observe mode changes
        viewModelScope.launch {
            modeManager.currentMode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
            }
        }

        // Observe recording state
        viewModelScope.launch {
            cameraXPlatform.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
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

    fun bindCameraPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        cameraXPlatform.bindPreview(previewView, lifecycleOwner)
        // Auto-open camera after binding
        viewModelScope.launch {
            cameraXPlatform.open(CameraConfig())
        }
    }

    private var isCapturing = false

    fun onShutterPress() {
        if (isCapturing) return
        isCapturing = true
        viewModelScope.launch {
            try {
                // Take a single photo. Don't publish ShutterPressed — that triggers
                // the burst module which captures 8+ frames. For single-shot mode,
                // we call takePicture() directly.
                val result = cameraXPlatform.takePicture()
                _uiState.update { it.copy(shotCount = it.shotCount + 1) }
            } catch (_: Exception) { }
            finally { isCapturing = false }
        }
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
        }
    }

    private val presetOrder = listOf("slalom_gs", "speed", "panning", "finish", "atmosphere", "training")

    private fun cyclePreset() {
        val currentIndex = presetOrder.indexOf(_uiState.value.currentPreset)
        val nextIndex = (currentIndex + 1) % presetOrder.size
        onPresetSelected(presetOrder[nextIndex])
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

    fun onClearTriggerZones() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("af/zone/clear", Unit)
        }
    }
}
