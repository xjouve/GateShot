package com.gateshot.capture.preset

object DefaultPresets {

    val SLALOM_GS = Preset(
        id = "slalom_gs",
        displayName = "Slalom / GS",
        category = PresetCategory.TECHNICAL,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 60,
            shutterSpeedMin = "1/1000",
            shutterSpeedMax = "1/2000",
            preferRaw = true
        ),
        burst = BurstPreset(
            mode = BurstMode.SHORT,
            frameCount = 8,
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
            ois = OisMode.STANDARD,
            eis = EisMode.OFF
        )
    )

    val SPEED = Preset(
        id = "speed",
        displayName = "Speed Events",
        category = PresetCategory.SPEED,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 60,
            shutterSpeedMin = "1/2000",
            shutterSpeedMax = "1/4000",
            preferRaw = true
        ),
        burst = BurstPreset(
            mode = BurstMode.CONTINUOUS,
            frameCount = 30,
            preBufferSeconds = 2.0f
        ),
        exposure = ExposurePreset(
            evBias = 2.0f,
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
            eis = EisMode.PANNING      // Horizontal axis unlocked
        )
    )

    val FINISH = Preset(
        id = "finish",
        displayName = "Finish Area",
        category = PresetCategory.CREATIVE,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 30,
            shutterSpeedMin = "1/500",
            shutterSpeedMax = "1/1000",
            preferRaw = true
        ),
        burst = BurstPreset(
            mode = BurstMode.SHORT,
            frameCount = 5,
            preBufferSeconds = 1.5f
        ),
        exposure = ExposurePreset(
            evBias = 0.5f,
            snowCompensation = false,
            flatLightAuto = false,
            hdrMode = HdrMode.AUTO
        ),
        autofocus = AutofocusPreset(
            mode = AfMode.CONTINUOUS,
            reacquisitionSpeed = AfSpeed.NORMAL,
            occlusionHold = false,
            facePriority = true     // Faces matter here
        ),
        stabilization = StabilizationPreset(
            ois = OisMode.STANDARD,
            eis = EisMode.OFF
        )
    )

    val ATMOSPHERE = Preset(
        id = "atmosphere",
        displayName = "Atmosphere",
        category = PresetCategory.CREATIVE,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 30,
            shutterSpeedMin = "1/250",
            shutterSpeedMax = "1/1000",
            preferRaw = true
        ),
        burst = BurstPreset(
            mode = BurstMode.SINGLE,
            frameCount = 1,
            preBufferSeconds = 0f
        ),
        exposure = ExposurePreset(
            evBias = 1.0f,
            snowCompensation = true,
            flatLightAuto = false,
            hdrMode = HdrMode.AGGRESSIVE    // Full dynamic range for landscapes
        ),
        autofocus = AutofocusPreset(
            mode = AfMode.SINGLE,
            reacquisitionSpeed = AfSpeed.NORMAL,
            occlusionHold = false,
            facePriority = false
        ),
        stabilization = StabilizationPreset(
            ois = OisMode.STANDARD,
            eis = EisMode.OFF
        )
    )

    val TRAINING = Preset(
        id = "training",
        displayName = "Training Analysis",
        category = PresetCategory.COACHING,
        camera = CameraPreset(
            resolutionWidth = 3840,
            resolutionHeight = 2160,
            frameRate = 60,
            shutterSpeedMin = "1/500",
            shutterSpeedMax = "1/1000",
            preferRaw = false       // Video-first, JPEG is fine
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

    val ALL = listOf(SLALOM_GS, SPEED, PANNING, FINISH, ATMOSPHERE, TRAINING)
    val BY_ID = ALL.associateBy { it.id }
}
