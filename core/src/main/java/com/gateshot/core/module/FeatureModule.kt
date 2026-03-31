package com.gateshot.core.module

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.mode.AppMode

interface FeatureModule {
    val name: String
    val version: String
    val requiredMode: AppMode?
    val dependencies: List<String> get() = emptyList()
    val requiredCapabilities: List<String> get() = emptyList()

    suspend fun initialize()
    suspend fun shutdown()
    fun endpoints(): List<ApiEndpoint<*, *>>
    fun healthCheck(): ModuleHealth
}
