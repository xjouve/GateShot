package com.gateshot.core.error

import android.util.Log
import com.gateshot.core.module.ModuleHealth
import com.gateshot.core.module.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ErrorHandler @Inject constructor() {

    private val moduleHealthMap = mutableMapOf<String, ModuleHealth>()
    private val _errors = MutableStateFlow<List<ErrorRecord>>(emptyList())
    val errors: StateFlow<List<ErrorRecord>> = _errors.asStateFlow()

    fun reportError(module: String, error: Throwable, severity: Severity) {
        val record = ErrorRecord(
            module = module,
            message = error.message ?: "Unknown error",
            severity = severity,
            timestamp = System.currentTimeMillis()
        )

        _errors.value = _errors.value + record

        val healthStatus = when (severity) {
            Severity.INFO -> ModuleHealth.Status.OK
            Severity.WARNING -> ModuleHealth.Status.OK
            Severity.DEGRADED -> ModuleHealth.Status.DEGRADED
            Severity.CRITICAL -> ModuleHealth.Status.ERROR
        }
        moduleHealthMap[module] = ModuleHealth(module, healthStatus, error.message)

        when (severity) {
            Severity.INFO -> Log.i(TAG, "[$module] ${error.message}")
            Severity.WARNING -> Log.w(TAG, "[$module] ${error.message}")
            Severity.DEGRADED -> Log.e(TAG, "[$module] DEGRADED: ${error.message}", error)
            Severity.CRITICAL -> Log.e(TAG, "[$module] CRITICAL: ${error.message}", error)
        }
    }

    fun getModuleHealth(): Map<String, ModuleHealth> = moduleHealthMap.toMap()

    fun clearErrors(module: String) {
        _errors.value = _errors.value.filter { it.module != module }
        moduleHealthMap.remove(module)
    }

    data class ErrorRecord(
        val module: String,
        val message: String,
        val severity: Severity,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "ErrorHandler"
    }
}
