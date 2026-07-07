package com.gateshot.di

import android.util.Log
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInitializer @Inject constructor(
    private val moduleLoader: ModuleLoader,
    private val featureModules: Set<@JvmSuppressWildcards FeatureModule>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initialize() {
        scope.launch {
            Log.i(TAG, "Initializing GateShot with ${featureModules.size} modules...")
            featureModules.forEach { module ->
                moduleLoader.registerModule(module)
            }
            moduleLoader.loadAll()
            Log.i(TAG, "All modules loaded: ${moduleLoader.getLoadedModules()}")
        }
    }

    companion object {
        private const val TAG = "AppInitializer"
    }
}
