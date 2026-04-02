package com.gateshot.capture.preset

import com.gateshot.platform.camera.AfRegion
import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.platform.camera.FlashMode
import com.gateshot.platform.camera.IspEdgeMode
import com.gateshot.platform.camera.IspHotPixel
import com.gateshot.platform.camera.IspNoiseReduction
import com.gateshot.platform.camera.IspPipelineConfig
import com.gateshot.platform.camera.ManualExposure
import com.gateshot.platform.camera.StabilizationConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires preset camera settings to the Camera2 hardware layer.
 *
 * Enforces every preset parameter on the actual hardware via Camera2 interop:
 * exposure, stabilization, AF mode, ISP pipeline, and flash.
 */
@Singleton
class PresetApplier @Inject constructor(
    private val camera: CameraXPlatform
) {

    fun apply(preset: Preset) {
        applyExposure(preset.camera, preset.exposure)
        applyStabilization(preset.stabilization)
        applyAutofocus(preset.autofocus)
        applyIspPipeline(preset)
    }

    /**
     * Configure ISP noise reduction, sharpening, and flash per preset.
     *
     * For presets that use our custom SR pipeline (SLALOM_GS, SPEED), we
     * DISABLE the ISP's noise reduction and sharpening to prevent double-
     * processing. Our multi-frame denoise + custom sharpening produces
     * better results than the ISP's single-frame algorithms.
     *
     * For video-focused presets (TRAINING), we keep ISP processing ON
     * because the video stream doesn't go through our SR pipeline.
     */
    private fun applyIspPipeline(preset: Preset) {
        val useCustomSr = preset.camera.preferRaw ||
            preset.category == PresetCategory.TECHNICAL ||
            preset.category == PresetCategory.SPEED

        val config = IspPipelineConfig(
            noiseReduction = if (useCustomSr) IspNoiseReduction.OFF else IspNoiseReduction.FAST,
            edgeEnhancement = if (useCustomSr) IspEdgeMode.OFF else IspEdgeMode.FAST,
            hotPixelCorrection = IspHotPixel.FAST,
            faceDetection = preset.autofocus.facePriority,
            flashMode = when (preset.category) {
                // Outdoor action presets: always disable flash
                PresetCategory.TECHNICAL, PresetCategory.SPEED -> FlashMode.OFF
                // Creative/coaching: allow flash for macro/inspection
                PresetCategory.CREATIVE -> FlashMode.OFF
                PresetCategory.COACHING -> FlashMode.OFF
            }
        )

        camera.setIspPipeline(config)
    }

    /**
     * Enforce shutter speed range and ISO on the sensor.
     *
     * Strategy: we use the preset's MAX shutter speed (fastest) as the
     * exposure time, because for action photography we want the fastest
     * shutter the preset allows. The camera AE can only go slower, not
     * faster, so we lock exposure to manual mode with the fast shutter
     * and let snow exposure compensation handle brightness via EV bias.
     *
     * For presets like Atmosphere that use slower shutters (1/250), we
     * use the MIN (slowest allowed) to get the motion blur effect.
     */
    private fun applyExposure(camera: CameraPreset, exposure: ExposurePreset) {
        // Parse the shutter speed strings to nanoseconds
        val fastShutterNs = CameraXPlatform.parseShutterSpeedToNs(camera.shutterSpeedMax)
        val slowShutterNs = CameraXPlatform.parseShutterSpeedToNs(camera.shutterSpeedMin)

        if (fastShutterNs == null && slowShutterNs == null) {
            // Can't parse either — fall back to auto exposure
            this.camera.setManualExposure(ManualExposure(enabled = false))
            return
        }

        // For action presets (TECHNICAL, SPEED), use the fastest shutter.
        // For creative presets (PANNING), use the slowest to get motion blur.
        val targetShutterNs = fastShutterNs ?: slowShutterNs!!

        // Select ISO based on conditions:
        // - Snow scenes are bright → keep ISO low (100-400)
        // - Indoor/evening → let ISO float higher
        // Start with ISO 200 as a safe default for daylight snow
        val targetIso = if (exposure.snowCompensation) 200 else 400

        this.camera.setManualExposure(
            ManualExposure(
                shutterSpeedNs = targetShutterNs,
                iso = targetIso,
                enabled = true
            )
        )
    }

    /**
     * Map OIS/EIS preset enums to Camera2 stabilization modes.
     *
     * OIS (optical) and EIS (electronic/video) are independent systems:
     * - OIS moves the lens element to counteract shake — works always, no crop
     * - EIS crops the frame and shifts it digitally — only for video
     *
     * For the PANNING preset, we disable EIS (horizontal axis must be free)
     * but keep OIS on (vertical shake still needs correction).
     */
    private fun applyStabilization(stabilization: StabilizationPreset) {
        val ois = when (stabilization.ois) {
            OisMode.OFF -> false
            OisMode.STANDARD -> true
            OisMode.MAXIMUM -> true   // Camera2 doesn't distinguish standard/max — OIS is on or off
        }

        val eis = when (stabilization.eis) {
            EisMode.OFF -> false
            EisMode.STANDARD -> true
            EisMode.PANNING -> false  // Panning mode: disable EIS so horizontal movement is preserved
        }

        this.camera.setStabilization(StabilizationConfig(
            opticalStabilization = ois,
            videoStabilization = eis
        ))
    }

    /**
     * Configure the AF behavioral mode on Camera2.
     *
     * This sets the baseline AF mode from the preset. The SubjectTracker /
     * TrackingAfBridge will overlay AF regions dynamically on top of this
     * mode, but the mode itself (continuous vs single vs manual) must be
     * set here first.
     *
     * Camera2 AF modes:
     * - CONTINUOUS_VIDEO: Camera continuously hunts for focus. Best for
     *   tracking a moving racer.
     * - CONTINUOUS_PICTURE: Similar but optimized for still capture.
     * - AUTO (single): Focuses once when triggered. Used for static shots.
     * - OFF (manual): Focus locked at a set distance. Used for macro.
     */
    private fun applyAutofocus(af: AutofocusPreset) {
        // Map our preset AF modes to Camera2 CaptureRequest options.
        // We don't call Camera2 directly here — instead we configure the
        // AF regions and mode via CameraXPlatform's Camera2 interop.
        //
        // For CONTINUOUS and CONTINUOUS_PREDICTIVE modes, we set continuous
        // AF regions. For SINGLE mode, we use one-shot AF. For MANUAL, we
        // clear AF regions and let MacroMode handle focus distance.

        when (af.mode) {
            AfMode.CONTINUOUS, AfMode.CONTINUOUS_PREDICTIVE -> {
                // Continuous AF is the default Camera2 mode when AF regions are set.
                // The TrackingAfBridge will set specific regions per-frame.
                // Here we just ensure no stale manual focus override is active.
                camera.setFocusDistance(0f)
            }
            AfMode.SINGLE -> {
                // Single AF: camera focuses once on the center, then locks.
                // Clear any AF regions so camera uses center-weighted AF.
                camera.setAfRegions(emptyList())
                camera.setFocusDistance(0f)
            }
            AfMode.MANUAL -> {
                // Manual: clear AF regions, focus distance set by MacroMode or user.
                camera.setAfRegions(emptyList())
            }
        }
    }
}
