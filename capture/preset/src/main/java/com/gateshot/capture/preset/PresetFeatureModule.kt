package com.gateshot.capture.preset

import android.content.Context
import android.content.SharedPreferences
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.config.ConfigStore
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.platform.camera.ManualExposure
import com.gateshot.platform.camera.StabilizationConfig
import com.gateshot.platform.camera.IspPipelineConfig
import com.gateshot.platform.camera.TonemapConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetFeatureModule @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val configStore: ConfigStore,
    private val eventBus: EventBus,
    private val cameraPlatform: CameraXPlatform
) : FeatureModule {

    private val userPrefs: SharedPreferences
        get() = appContext.getSharedPreferences("gateshot_config", Context.MODE_PRIVATE)

    override val name = "preset"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null

    private var activePreset: Preset = DefaultPresets.RACE
    private val customPresets = mutableMapOf<String, Preset>()

    override suspend fun initialize() {
        // Load default preset
        applyPreset(activePreset)
    }

    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        ListPresets(),
        ApplyPreset(),
        GetCurrentPreset(),
        ResetPreset()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    private suspend fun applyPreset(preset: Preset) {
        activePreset = preset

        // Push preset values into ConfigStore for other modules to read
        configStore.set("camera", "resolution_width", preset.camera.resolutionWidth)
        configStore.set("camera", "resolution_height", preset.camera.resolutionHeight)
        configStore.set("camera", "frame_rate", preset.camera.frameRate)
        configStore.set("camera", "shutter_min", preset.camera.shutterSpeedMin)
        configStore.set("camera", "shutter_max", preset.camera.shutterSpeedMax)
        configStore.set("camera", "prefer_raw", preset.camera.preferRaw)

        configStore.set("burst", "mode", preset.burst.mode.name)
        configStore.set("burst", "frame_count", preset.burst.frameCount)
        configStore.set("burst", "pre_buffer_seconds", preset.burst.preBufferSeconds)

        configStore.set("exposure", "hdr_mode", preset.exposure.hdrMode.name)

        configStore.set("af", "mode", preset.autofocus.mode.name)
        configStore.set("af", "reacquisition_speed", preset.autofocus.reacquisitionSpeed.name)
        configStore.set("af", "occlusion_hold", preset.autofocus.occlusionHold)

        configStore.set("stabilization", "ois", preset.stabilization.ois.name)
        configStore.set("stabilization", "eis", preset.stabilization.eis.name)

        // =============================================
        // Write to SharedPreferences so the Settings screen reflects the
        // active preset's values. Keys match what SettingsScreen reads.
        // =============================================
        userPrefs.edit().apply {
            // Video recording
            putFloat("video_resolution", preset.camera.resolutionHeight.toFloat())
            putFloat("video_frame_rate", preset.camera.frameRate.toFloat())
            putBoolean("video_hdr", preset.exposure.hdrMode != HdrMode.OFF)
            putFloat("video_ois_mode", when (preset.stabilization.ois) {
                OisMode.OFF -> 0f; OisMode.STANDARD -> 1f; OisMode.MAXIMUM -> 2f
            })
            putFloat("video_eis_mode", when (preset.stabilization.eis) {
                EisMode.OFF -> 0f; EisMode.STANDARD -> 1f; EisMode.PANNING -> 2f
            })

            // Autofocus
            putFloat("af_mode_index", when (preset.autofocus.mode) {
                AfMode.SINGLE -> 0f; AfMode.CONTINUOUS -> 1f
                AfMode.CONTINUOUS_PREDICTIVE -> 2f; AfMode.MANUAL -> 3f
            })
            putBoolean("af_face_priority", preset.autofocus.facePriority)

            // Exposure
            putBoolean("exposure_snow_compensation", preset.exposure.snowCompensation)
            putFloat("exposure_ev_bias", preset.exposure.evBias)
            putBoolean("exposure_flat_light_auto", preset.exposure.flatLightAuto)

            // Burst
            putFloat("burst_buffer_duration", preset.burst.preBufferSeconds)

            // SR: enable for stills presets (preferRaw), disable for video
            putBoolean("sr_auto_enhance", preset.camera.preferRaw)

            apply()
        }

        // =============================================
        // Apply to camera hardware
        // =============================================

        // Manual exposure: if user has manual mode on, use their ISO/shutter
        // Otherwise use the preset's shutter speed hint
        val manualMode = userPrefs.getBoolean("camera_manual_mode", false)
        if (manualMode) {
            val userIso = userPrefs.getFloat("camera_iso", 400f).toInt()
            val userShutter = userPrefs.getFloat("camera_shutter_speed", 500f)
            val userShutterNs = (1_000_000_000L / userShutter.toLong())
            cameraPlatform.setManualExposure(ManualExposure(
                shutterSpeedNs = userShutterNs, iso = userIso, enabled = true
            ))
        } else {
            val shutterNs = parseShutterSpeed(preset.camera.shutterSpeedMax)
            cameraPlatform.setManualExposure(ManualExposure(
                shutterSpeedNs = shutterNs, iso = null, enabled = shutterNs != null
            ))
        }

        // Manual WB: if user has auto WB off, apply their CCT
        val autoWb = userPrefs.getBoolean("camera_auto_wb", true)
        if (!autoWb) {
            val cct = userPrefs.getFloat("camera_wb_temperature", 5500f).toInt()
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
            cameraPlatform.setWhiteBalanceGains(com.gateshot.platform.camera.WhiteBalanceGains(
                redGain = rGain, greenEvenGain = 1f, greenOddGain = 1f, blueGain = bGain
            ))
        }

        // Exposure compensation
        val snowCompEnabled = preset.exposure.snowCompensation
        val evBias = if (snowCompEnabled) {
            preset.exposure.evBias
        } else {
            userPrefs.getFloat("exposure_ev_bias", 0f)
        }
        configStore.set("exposure", "snow_compensation", snowCompEnabled)
        configStore.set("exposure", "ev_bias", evBias)
        configStore.set("exposure", "flat_light_auto", preset.exposure.flatLightAuto)
        cameraPlatform.setExposureCompensation(evBias)

        // Stabilization
        cameraPlatform.setStabilization(StabilizationConfig(
            ois = when (preset.stabilization.ois) {
                OisMode.OFF -> com.gateshot.platform.camera.OisMode.OFF
                OisMode.STANDARD -> com.gateshot.platform.camera.OisMode.STANDARD
                OisMode.MAXIMUM -> com.gateshot.platform.camera.OisMode.MAXIMUM
            },
            eis = when (preset.stabilization.eis) {
                EisMode.OFF -> com.gateshot.platform.camera.EisMode.OFF
                EisMode.STANDARD -> com.gateshot.platform.camera.EisMode.STANDARD
                EisMode.PANNING -> com.gateshot.platform.camera.EisMode.PANNING
            }
        ))

        // ISP: face detection, flash, and bokeh from user settings + preset
        val flashOn = userPrefs.getBoolean("camera_flash", false)
        val bokehOn = userPrefs.getBoolean("camera_bokeh_enabled", false)
        cameraPlatform.setIspPipeline(IspPipelineConfig(
            faceDetection = preset.autofocus.facePriority || bokehOn,
            flashMode = if (flashOn) com.gateshot.platform.camera.FlashMode.AUTO
                        else com.gateshot.platform.camera.FlashMode.OFF
        ))

        // Color profile: Hasselblad tone curves (if enabled)
        val hasselbladEnabled = userPrefs.getBoolean("color_hasselblad_enabled", false)
        if (hasselbladEnabled) {
            val curve = HasselbladProfile.buildTonemapCurve()
            cameraPlatform.setTonemapCurve(TonemapConfig(
                enabled = true,
                curveRed = curve.red,
                curveGreen = curve.green,
                curveBlue = curve.blue
            ))
        } else {
            cameraPlatform.setTonemapCurve(TonemapConfig())
        }

        eventBus.publish(AppEvent.PresetApplied(preset.id))
    }

    // --- preset/list ---
    inner class ListPresets : ApiEndpoint<Unit, List<PresetSummary>> {
        override val path = "preset/list"
        override val module = "preset"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<List<PresetSummary>> {
            val all = DefaultPresets.ALL.map { PresetSummary(it.id, it.displayName, it.category.name) } +
                customPresets.values.map { PresetSummary(it.id, it.displayName, it.category.name) }
            return ApiResponse.success(all)
        }
    }

    // --- preset/apply ---
    inner class ApplyPreset : ApiEndpoint<String, Boolean> {
        override val path = "preset/apply"
        override val module = "preset"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            val preset = DefaultPresets.BY_ID[request]
                ?: customPresets[request]
                ?: return ApiResponse.error(404, "Preset not found: $request")

            applyPreset(preset)
            return ApiResponse.success(true)
        }
    }

    // --- preset/current ---
    inner class GetCurrentPreset : ApiEndpoint<Unit, Preset> {
        override val path = "preset/current"
        override val module = "preset"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Preset> {
            return ApiResponse.success(activePreset)
        }
    }

    // --- preset/reset ---
    inner class ResetPreset : ApiEndpoint<Unit, Boolean> {
        override val path = "preset/reset"
        override val module = "preset"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            applyPreset(DefaultPresets.RACE)
            return ApiResponse.success(true)
        }
    }

    /** Parse "1/2000" → nanoseconds (500_000ns). Returns null if unparseable. */
    private fun parseShutterSpeed(fraction: String): Long? {
        val parts = fraction.split("/")
        if (parts.size != 2) return null
        val numerator = parts[0].toLongOrNull() ?: return null
        val denominator = parts[1].toLongOrNull() ?: return null
        if (denominator == 0L) return null
        return numerator * 1_000_000_000L / denominator
    }
}

data class PresetSummary(
    val id: String,
    val displayName: String,
    val category: String
)
