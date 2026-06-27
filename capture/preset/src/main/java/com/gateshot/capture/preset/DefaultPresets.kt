package com.gateshot.capture.preset

/**
 * Three focused presets, each for a fundamentally different shooting mode.
 *
 * RACE  — Stills of racers at gates. Fast shutter freezes action, predictive AF
 *         tracks through occlusion, burst captures the key moment.
 * VIDEO — Filming runs for coaching replay & comparison. High frame rate for
 *         slow-motion analysis, continuous AF, stabilization for handheld use.
 * PAN   — Panning shots with deliberate motion blur. Slow shutter, EIS panning
 *         mode unlocks the horizontal axis for smooth follow-through.
 *
 * Settings that used to differ between removed presets (Finish, Speed, Atmosphere)
 * are now independently configurable in the Settings screen: AF mode, face priority,
 * stabilization, HDR, shutter speed, etc.
 */
object DefaultPresets {

    val RACE = Preset(
        id = "race",
        displayName = "Race",
        category = PresetCategory.TECHNICAL,
        camera = CameraPreset(
            // 1920x1080 @ 30fps — CameraX's PREVIEW_STABILIZATION surface
            // combination list caps the preview/video surface at s1440p
            // (1920×1440), so anything above FHD silently disables the
            // high-quality EIS path. Native camera apps bypass this via
            // private Oppo vendor paths; GateShot uses the public CameraX
            // API and so is bound by this constraint. If you want 4K/60
            // (e.g. for forensic slo-mo replay) toggle it in Settings and
            // accept the tradeoff: at 4K/60 the HAL reports stabilization
            // as active but no actual warping happens.
            resolutionWidth = 1920,
            resolutionHeight = 1080,
            frameRate = 30,
            shutterSpeedMin = "1/1000",
            shutterSpeedMax = "1/4000",
            preferRaw = true
        ),
        burst = BurstPreset(
            mode = BurstMode.CONTINUOUS,
            frameCount = 15,
            preBufferSeconds = 1.5f
        ),
        exposure = ExposurePreset(
            evBias = 1.5f,
            snowCompensation = true,
            flatLightAuto = true,
            hdrMode = HdrMode.AUTO
        ),
        autofocus = AutofocusPreset(
            mode = AfMode.CONTINUOUS_PREDICTIVE,
            reacquisitionSpeed = AfSpeed.FAST,
            occlusionHold = true,
            facePriority = false
        ),
        stabilization = StabilizationPreset(
            ois = OisMode.MAXIMUM,
            // STANDARD EIS — the RACE preset is used for both stills and
            // handheld video capture on the hill, and the native-app-
            // equivalent stabilization matters far more in practice than
            // the small preview crop that EIS introduces. Stills capture
            // bypasses CONTROL_VIDEO_STABILIZATION_MODE entirely, so the
            // crop only affects the preview/video surface.
            eis = EisMode.STANDARD
        )
    )

    val VIDEO = Preset(
        id = "video",
        displayName = "Video",
        category = PresetCategory.COACHING,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 120,
            shutterSpeedMin = "1/500",
            shutterSpeedMax = "1/1000",
            preferRaw = false
        ),
        burst = BurstPreset(
            mode = BurstMode.SINGLE,
            frameCount = 1,
            preBufferSeconds = 0f
        ),
        exposure = ExposurePreset(
            evBias = 1.5f,
            snowCompensation = true,
            flatLightAuto = true,
            hdrMode = HdrMode.AUTO
        ),
        autofocus = AutofocusPreset(
            mode = AfMode.CONTINUOUS,
            reacquisitionSpeed = AfSpeed.NORMAL,
            occlusionHold = true,
            facePriority = false
        ),
        stabilization = StabilizationPreset(
            ois = OisMode.STANDARD,
            eis = EisMode.STANDARD
        )
    )

    val PANNING = Preset(
        id = "panning",
        displayName = "Panning",
        category = PresetCategory.CREATIVE,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 30,
            shutterSpeedMin = "1/125",
            shutterSpeedMax = "1/250",
            preferRaw = true
        ),
        burst = BurstPreset(
            mode = BurstMode.CONTINUOUS,
            frameCount = 20,
            preBufferSeconds = 1.0f
        ),
        exposure = ExposurePreset(
            evBias = 1.0f,
            snowCompensation = true,
            flatLightAuto = false,
            hdrMode = HdrMode.OFF
        ),
        autofocus = AutofocusPreset(
            mode = AfMode.CONTINUOUS,
            reacquisitionSpeed = AfSpeed.NORMAL,
            occlusionHold = false,
            facePriority = false
        ),
        stabilization = StabilizationPreset(
            ois = OisMode.STANDARD,
            eis = EisMode.PANNING
        )
    )

    val ALL = listOf(RACE, VIDEO, PANNING)
    val BY_ID = ALL.associateBy { it.id }
}
