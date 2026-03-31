package com.gateshot.capture.preset

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.config.ConfigStore
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetFeatureModule @Inject constructor(
    private val configStore: ConfigStore,
    private val eventBus: EventBus
) : FeatureModule {

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
}

data class PresetSummary(
    val id: String,
    val displayName: String,
    val category: String
)
