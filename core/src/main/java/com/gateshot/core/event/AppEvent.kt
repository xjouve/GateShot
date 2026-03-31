package com.gateshot.core.event

import com.gateshot.core.mode.AppMode

sealed interface AppEvent {

    // Capture events
    data class ShutterPressed(val timestamp: Long = System.currentTimeMillis()) : AppEvent
    data class BurstStarted(val sessionId: String) : AppEvent
    data class BurstCompleted(val frameCount: Int, val sessionId: String) : AppEvent
    data class VideoRecordingStarted(val sessionId: String) : AppEvent
    data class VideoRecordingStopped(val sessionId: String, val clipUri: String) : AppEvent
    data class RunDetected(val runNumber: Int, val bibNumber: Int?) : AppEvent

    // Processing events
    data class BibDetected(val bibNumber: Int, val confidence: Float) : AppEvent
    data class ExposureAdjusted(val evBias: Float, val reason: String) : AppEvent

    // Coaching events
    data class SplitRecorded(val gateNumber: Int, val timestamp: Long) : AppEvent
    data class AnnotationAdded(val clipId: String, val type: String) : AppEvent

    // System events
    data class ModeChanged(val newMode: AppMode) : AppEvent
    data class LensDetected(val lensType: String) : AppEvent
    data class LensRemoved(val lensType: String) : AppEvent
    data class BatteryWarning(val tempCelsius: Float, val level: Int) : AppEvent
    data class ModuleLoaded(val moduleName: String) : AppEvent
    data class ModuleError(val moduleName: String, val error: String) : AppEvent

    // Camera lifecycle
    data object CameraOpened : AppEvent
    data object CameraClosed : AppEvent
    data class PresetApplied(val presetName: String) : AppEvent
}
