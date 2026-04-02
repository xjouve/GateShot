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

    private var activePreset: Preset = DefaultPresets.SLALOM_GS
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

        configStore.set("exposure", "ev_bias", preset.exposure.evBias)
        configStore.set("exposure", "snow_compensation", preset.exposure.snowCompensation)
        configStore.set("exposure", "flat_light_auto", preset.exposure.flatLightAuto)
        configStore.set("exposure", "hdr_mode", preset.exposure.hdrMode.name)

        configStore.set("af", "mode", preset.autofocus.mode.name)
        configStore.set("af", "reacquisition_speed", preset.autofocus.reacquisitionSpeed.name)
        configStore.set("af", "occlusion_hold", preset.autofocus.occlusionHold)
        configStore.set("af", "face_priority", preset.autofocus.facePriority)

        configStore.set("stabilization", "ois", preset.stabilization.ois.name)
        configStore.set("stabilization", "eis", preset.stabilization.eis.name)

        // Apply camera settings directly — ConfigStore alone is not enough
        // because most modules don't poll it for changes.
        // User settings from SharedPreferences override preset defaults.

        // Shutter speed: use the max (fastest) shutter as the target
        val shutterNs = parseShutterSpeed(preset.camera.shutterSpeedMax)
        cameraPlatform.setManualExposure(ManualExposure(
            shutterSpeedNs = shutterNs,
            iso = null,  // Let AE pick ISO within the shutter constraint
            enabled = shutterNs != null
        ))

        // Exposure: user override from settings takes priority over preset
        val snowCompEnabled = userPrefs.getBoolean("exposure_snow_compensation", preset.exposure.snowCompensation)
        val evBias = if (snowCompEnabled) {
            // When snow comp is on, the SnowExposureModule handles EV dynamically
            preset.exposure.evBias
        } else {
            // When snow comp is off, use the user's manual EV setting (default 0 = no bias)
            userPrefs.getFloat("exposure_ev_bias", 0f)
        }
        configStore.set("exposure", "snow_compensation", snowCompEnabled)
        configStore.set("exposure", "ev_bias", evBias)
        configStore.set("exposure", "flat_light_auto",
            userPrefs.getBoolean("exposure_flat_light_auto", preset.exposure.flatLightAuto))
        cameraPlatform.setExposureCompensation(evBias)

        // Stabilization
        cameraPlatform.setStabilization(StabilizationConfig(
            opticalStabilization = preset.stabilization.ois != OisMode.OFF,
            videoStabilization = preset.stabilization.eis != EisMode.OFF
        ))

        // ISP: face detection from AF preset
        cameraPlatform.setIspPipeline(IspPipelineConfig(
            faceDetection = preset.autofocus.facePriority
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
            applyPreset(DefaultPresets.SLALOM_GS)
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
