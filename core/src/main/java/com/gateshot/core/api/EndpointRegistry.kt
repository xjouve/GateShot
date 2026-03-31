package com.gateshot.core.api

import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

interface EndpointRegistryContract {
    fun register(endpoint: ApiEndpoint<*, *>)
    fun unregister(path: String)
    suspend fun <Req, Res> call(path: String, request: Req): ApiResponse<Res>
    fun listEndpoints(mode: AppMode? = null): List<EndpointDescriptor>
    fun isAvailable(path: String): Boolean
}

@Singleton
class EndpointRegistry @Inject constructor(
    private val modeManager: ModeManager
) : EndpointRegistryContract {

    private val endpoints = mutableMapOf<String, ApiEndpoint<*, *>>()
    private val mutex = Mutex()

    override fun register(endpoint: ApiEndpoint<*, *>) {
        endpoints[endpoint.path] = endpoint
    }

    override fun unregister(path: String) {
        endpoints.remove(path)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <Req, Res> call(path: String, request: Req): ApiResponse<Res> {
        val endpoint = endpoints[path]
            ?: return ApiResponse.notFound(path)

        if (!modeManager.isFeatureAvailable(endpoint.requiredMode)) {
            return ApiResponse.modeNotActive()
        }

        return try {
            val typed = endpoint as ApiEndpoint<Req, Res>
            typed.handle(request)
        } catch (e: Exception) {
            ApiResponse.moduleError(endpoint.module, e.message ?: "Unknown error")
        }
    }

    override fun listEndpoints(mode: AppMode?): List<EndpointDescriptor> {
        return endpoints.values
            .filter { mode == null || modeManager.isFeatureAvailable(it.requiredMode) }
            .map { EndpointDescriptor(it.path, it.module, it.requiredMode, it.version) }
    }

    override fun isAvailable(path: String): Boolean {
        val endpoint = endpoints[path] ?: return false
        return modeManager.isFeatureAvailable(endpoint.requiredMode)
    }
}
