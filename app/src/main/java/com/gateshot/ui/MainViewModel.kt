package com.gateshot.ui

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
import com.gateshot.capture.trigger.TriggerFeatureModule
import com.gateshot.capture.trigger.TriggerZone
import com.gateshot.capture.trigger.ZoneAddRequest
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
    val moduleStatuses: Map<String, String> = emptyMap()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val modeManager: ModeManager,
    private val eventBus: EventBus,
    private val endpointRegistry: EndpointRegistry,
    val cameraXPlatform: CameraXPlatform,
    private val triggerModule: TriggerFeatureModule,
    private val sensorPlatform: SensorPlatform
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
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
                _uiState.update { it.copy(currentPreset = event.presetName) }
            }
        }
        eventBus.collect<AppEvent.BurstCompleted>(viewModelScope) { event ->
            _uiState.update { it.copy(shotCount = it.shotCount + event.frameCount) }
        }
        eventBus.collect<AppEvent.ExposureAdjusted>(viewModelScope) { event ->
            _uiState.update { it.copy(currentEvBias = event.evBias) }
        }

        // Poll battery status every 30 seconds
        viewModelScope.launch {
            while (true) {
                val battLevel = sensorPlatform.getBatteryLevel()
                val battTemp = sensorPlatform.getBatteryTemperature() ?: 20f
                _uiState.update { it.copy(batteryLevel = battLevel, batteryTemp = battTemp) }
                kotlinx.coroutines.delay(30_000)
            }
        }

        // Observe trigger zones
        viewModelScope.launch {
            triggerModule.zones.collect { zones ->
                _uiState.update { it.copy(triggerZones = zones, triggerArmed = zones.isNotEmpty()) }
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

    fun onShutterPress() {
        viewModelScope.launch {
            eventBus.publish(AppEvent.ShutterPressed())
            // Take a photo directly (burst module also listens to ShutterPressed)
            try {
                cameraXPlatform.takePicture()
                _uiState.update { it.copy(shotCount = it.shotCount + 1) }
            } catch (_: Exception) { }
        }
    }

    fun onVideoToggle() {
        viewModelScope.launch {
            if (_uiState.value.isRecording) {
                endpointRegistry.call<Unit, Any>("capture/video/stop", Unit)
            } else {
                endpointRegistry.call<Unit, Any>("capture/video/start", Unit)
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

    private val presetOrder = listOf("slalom_gs", "speed", "panning", "finish", "atmosphere", "training")

    private fun cyclePreset() {
        val currentIndex = presetOrder.indexOf(_uiState.value.currentPreset)
        val nextIndex = (currentIndex + 1) % presetOrder.size
        onPresetSelected(presetOrder[nextIndex])
    }

    fun onClearTriggerZones() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("af/zone/clear", Unit)
        }
    }
}
