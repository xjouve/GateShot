package com.gateshot.capture.preset

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val id: String,
    val displayName: String,
    val category: PresetCategory,
    val camera: CameraPreset,
    val burst: BurstPreset,
    val exposure: ExposurePreset,
    val autofocus: AutofocusPreset,
    val stabilization: StabilizationPreset
)

@Serializable
enum class PresetCategory { TECHNICAL, SPEED, CREATIVE, COACHING }

@Serializable
data class CameraPreset(
    val resolutionWidth: Int = 3840,
    val resolutionHeight: Int = 2160,
    val frameRate: Int = 30,
    val shutterSpeedMin: String = "1/1000",    // as fraction string for display
    val shutterSpeedMax: String = "1/2000",
    val preferRaw: Boolean = true
)

@Serializable
data class BurstPreset(
    val mode: BurstMode = BurstMode.SHORT,
    val frameCount: Int = 8,
    val preBufferSeconds: Float = 1.5f
)

@Serializable
enum class BurstMode { SINGLE, SHORT, CONTINUOUS }

@Serializable
data class ExposurePreset(
    val evBias: Float = 1.5f,
    val snowCompensation: Boolean = true,
    val flatLightAuto: Boolean = true,
    val hdrMode: HdrMode = HdrMode.AUTO
)

@Serializable
enum class HdrMode { OFF, AUTO, AGGRESSIVE }

@Serializable
data class AutofocusPreset(
    val mode: AfMode = AfMode.CONTINUOUS_PREDICTIVE,
    val reacquisitionSpeed: AfSpeed = AfSpeed.FAST,
    val occlusionHold: Boolean = true,
    val facePriority: Boolean = false
)

@Serializable
enum class AfMode { SINGLE, CONTINUOUS, CONTINUOUS_PREDICTIVE, MANUAL }

@Serializable
enum class AfSpeed { NORMAL, FAST }

@Serializable
data class StabilizationPreset(
    val ois: OisMode = OisMode.STANDARD,
    val eis: EisMode = EisMode.OFF
)

@Serializable
enum class OisMode { OFF, STANDARD, MAXIMUM }

@Serializable
enum class EisMode { OFF, STANDARD, PANNING }
