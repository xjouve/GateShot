package com.gateshot.capture.camera

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.platform.camera.CameraPlatform
import com.gateshot.platform.camera.CameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraFeatureModule @Inject constructor(
    private val cameraPlatform: CameraPlatform,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "camera"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null  // Always available

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cameraEndpoints = CameraEndpoints(cameraPlatform, eventBus)

    override suspend fun initialize() {
        // Observe camera state changes and publish events
        scope.launch {
            cameraPlatform.state.collect { state ->
                when (state) {
                    CameraState.OPEN -> eventBus.publish(AppEvent.CameraOpened)
                    CameraState.CLOSED -> eventBus.publish(AppEvent.CameraClosed)
                    else -> {}
                }
            }
        }
    }

    override suspend fun shutdown() {
        cameraPlatform.close()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = cameraEndpoints.all()

    override fun healthCheck(): ModuleHealth {
        val state = cameraPlatform.state.value
        return when (state) {
            CameraState.OPEN -> ModuleHealth(name, ModuleHealth.Status.OK)
            CameraState.CLOSED -> ModuleHealth(name, ModuleHealth.Status.OK, "Camera closed")
            CameraState.OPENING -> ModuleHealth(name, ModuleHealth.Status.OK, "Camera opening")
            CameraState.ERROR -> ModuleHealth(name, ModuleHealth.Status.ERROR, "Camera error")
        }
    }
}
