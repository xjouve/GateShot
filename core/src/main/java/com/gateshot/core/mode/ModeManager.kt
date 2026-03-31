package com.gateshot.core.mode

import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModeManager @Inject constructor(
    private val eventBus: EventBus
) {
    private val _currentMode = MutableStateFlow(AppMode.SHOOT)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    suspend fun setMode(mode: AppMode) {
        if (_currentMode.value != mode) {
            _currentMode.value = mode
            eventBus.publish(AppEvent.ModeChanged(mode))
        }
    }

    fun toggleMode() {
        val newMode = when (_currentMode.value) {
            AppMode.SHOOT -> AppMode.COACH
            AppMode.COACH -> AppMode.SHOOT
        }
        _currentMode.value = newMode
        eventBus.tryPublish(AppEvent.ModeChanged(newMode))
    }

    fun isFeatureAvailable(requiredMode: AppMode?): Boolean {
        if (requiredMode == null) return true
        return when (requiredMode) {
            AppMode.SHOOT -> true
            AppMode.COACH -> _currentMode.value == AppMode.COACH
        }
    }
}
