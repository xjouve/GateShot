package com.gateshot.core.module

import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.error.ErrorHandler
import com.gateshot.core.log.GateShotLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModuleLoader @Inject constructor(
    private val endpointRegistry: EndpointRegistry,
    private val eventBus: EventBus,
    private val errorHandler: ErrorHandler,
    private val logger: GateShotLogger
) {
    private val modules = mutableMapOf<String, FeatureModule>()
    private val loadOrder = mutableListOf<String>()

    suspend fun registerModule(module: FeatureModule) {
        modules[module.name] = module
    }

    suspend fun loadAll() {
        val sorted = topologicalSort(modules.values.toList())
        for (module in sorted) {
            loadModule(module)
        }
    }

    private suspend fun loadModule(module: FeatureModule) {
        try {
            // Check dependencies
            for (dep in module.dependencies) {
                if (dep !in loadOrder) {
                    logger.w(TAG, "Module '${module.name}' depends on '$dep' which is not loaded. Skipping.")
                    errorHandler.reportError(
                        module.name,
                        IllegalStateException("Missing dependency: $dep"),
                        Severity.WARNING
                    )
                    return
                }
            }

            module.initialize()

            // Register all endpoints
            for (endpoint in module.endpoints()) {
                endpointRegistry.register(endpoint)
            }

            loadOrder.add(module.name)
            eventBus.publish(AppEvent.ModuleLoaded(module.name))
            logger.i(TAG, "Module '${module.name}' v${module.version} loaded (${module.endpoints().size} endpoints)")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to load module '${module.name}'", e)
            errorHandler.reportError(module.name, e, Severity.DEGRADED)
            eventBus.publish(AppEvent.ModuleError(module.name, e.message ?: "Unknown error"))
        }
    }

    suspend fun shutdownAll() {
        for (name in loadOrder.reversed()) {
            try {
                modules[name]?.shutdown()
            } catch (e: Exception) {
                logger.e(TAG, "Error shutting down module '$name'", e)
            }
        }
        loadOrder.clear()
    }

    fun getModule(name: String): FeatureModule? = modules[name]
    fun getLoadedModules(): List<String> = loadOrder.toList()
    fun getHealthReport(): Map<String, ModuleHealth> =
        modules.mapValues { (_, module) ->
            if (module.name in loadOrder) module.healthCheck()
            else ModuleHealth(module.name, ModuleHealth.Status.NOT_LOADED)
        }

    private fun topologicalSort(modules: List<FeatureModule>): List<FeatureModule> {
        val byName = modules.associateBy { it.name }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<FeatureModule>()

        fun visit(module: FeatureModule) {
            if (module.name in visited) return
            visited.add(module.name)
            for (dep in module.dependencies) {
                byName[dep]?.let { visit(it) }
            }
            result.add(module)
        }

        modules.forEach { visit(it) }
        return result
    }

    companion object {
        private const val TAG = "ModuleLoader"
    }
}
