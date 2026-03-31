package com.gateshot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
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
    val moduleStatuses: Map<String, String> = emptyMap()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val modeManager: ModeManager,
    private val eventBus: EventBus,
    private val endpointRegistry: EndpointRegistry
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
            _uiState.update { it.copy(currentPreset = event.presetName) }
        }
        eventBus.collect<AppEvent.BurstCompleted>(viewModelScope) { event ->
            _uiState.update { it.copy(shotCount = it.shotCount + event.frameCount) }
        }
    }

    fun onShutterPress() {
        viewModelScope.launch {
            eventBus.publish(AppEvent.ShutterPressed())
            // The camera module listens for this event and handles capture
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
        viewModelScope.launch {
            endpointRegistry.call<Float, Any>("lens/zoom/set", zoom)
        }
    }
}
