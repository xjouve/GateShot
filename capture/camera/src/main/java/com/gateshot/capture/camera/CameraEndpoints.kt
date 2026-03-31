package com.gateshot.capture.camera

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.platform.camera.CameraConfig
import com.gateshot.platform.camera.CameraPlatform
import com.gateshot.platform.camera.CameraState
import com.gateshot.platform.camera.CaptureResult
import kotlinx.serialization.Serializable

class CameraEndpoints(
    private val cameraPlatform: CameraPlatform,
    private val eventBus: EventBus
) {
    fun all(): List<ApiEndpoint<*, *>> = listOf(
        OpenCamera(),
        CloseCamera(),
        GetCameraStatus(),
        TakePicture()
    )

    // --- capture/camera/open ---
    inner class OpenCamera : ApiEndpoint<CameraConfig, Boolean> {
        override val path = "capture/camera/open"
        override val module = "camera"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: CameraConfig): ApiResponse<Boolean> {
            return try {
                cameraPlatform.open(request)
                ApiResponse.success(true)
            } catch (e: Exception) {
                ApiResponse.moduleError(module, e.message ?: "Failed to open camera")
            }
        }
    }

    // --- capture/camera/close ---
    inner class CloseCamera : ApiEndpoint<Unit, Boolean> {
        override val path = "capture/camera/close"
        override val module = "camera"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            cameraPlatform.close()
            return ApiResponse.success(true)
        }
    }

    // --- capture/camera/status ---
    inner class GetCameraStatus : ApiEndpoint<Unit, CameraStatusResponse> {
        override val path = "capture/camera/status"
        override val module = "camera"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<CameraStatusResponse> {
            val state = cameraPlatform.state.value
            val caps = cameraPlatform.capabilities
            return ApiResponse.success(
                CameraStatusResponse(
                    state = state.name,
                    hasOpticalStabilization = caps?.hasOpticalStabilization ?: false,
                    maxZoomRatio = caps?.maxZoomRatio ?: 1f,
                    hasFlash = caps?.hasFlash ?: false
                )
            )
        }
    }

    // --- capture/photo/single ---
    inner class TakePicture : ApiEndpoint<Unit, CaptureResult> {
        override val path = "capture/photo/single"
        override val module = "camera"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<CaptureResult> {
            if (cameraPlatform.state.value != CameraState.OPEN) {
                return ApiResponse.error(409, "Camera is not open")
            }
            return try {
                val result = cameraPlatform.takePicture()
                eventBus.publish(AppEvent.BurstCompleted(frameCount = 1, sessionId = "single"))
                ApiResponse.success(result)
            } catch (e: Exception) {
                ApiResponse.moduleError(module, e.message ?: "Capture failed")
            }
        }
    }
}

data class CameraStatusResponse(
    val state: String,
    val hasOpticalStabilization: Boolean,
    val maxZoomRatio: Float,
    val hasFlash: Boolean
)
