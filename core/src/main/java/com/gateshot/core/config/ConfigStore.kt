package com.gateshot.core.config

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigStore @Inject constructor() {

    private val store = mutableMapOf<String, MutableMap<String, Any>>()
    private val changeFlows = mutableMapOf<String, MutableStateFlow<Long>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(module: String, key: String, default: T): T {
        return store[module]?.get(key) as? T ?: default
    }

    fun <T : Any> set(module: String, key: String, value: T) {
        store.getOrPut(module) { mutableMapOf() }[key] = value
        notifyChange(module, key)
    }

    fun getModuleConfig(module: String): Map<String, Any> {
        return store[module]?.toMap() ?: emptyMap()
    }

    fun setModuleConfig(module: String, config: Map<String, Any>) {
        store[module] = config.toMutableMap()
        notifyChange(module, "*")
    }

    fun observeChanges(module: String, key: String): Flow<Long> {
        val flowKey = "$module:$key"
        return changeFlows.getOrPut(flowKey) {
            MutableStateFlow(System.currentTimeMillis())
        }
    }

    fun export(): Map<String, Map<String, Any>> = store.mapValues { it.value.toMap() }

    fun import(config: Map<String, Map<String, Any>>) {
        store.clear()
        config.forEach { (module, values) ->
            store[module] = values.toMutableMap()
        }
    }

    fun clear(module: String) {
        store.remove(module)
    }

    private fun notifyChange(module: String, key: String) {
        val flowKey = "$module:$key"
        changeFlows[flowKey]?.value = System.currentTimeMillis()
        // Also notify wildcard observers
        val wildcardKey = "$module:*"
        changeFlows[wildcardKey]?.value = System.currentTimeMillis()
    }
}
