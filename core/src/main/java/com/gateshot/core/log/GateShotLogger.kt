package com.gateshot.core.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GateShot Structured Logger.
 *
 * Provides:
 * - Consistent "[MODULE] message" format across all modules
 * - Log levels: VERBOSE, DEBUG, INFO, WARN, ERROR
 * - Per-module filtering at runtime (enable/disable specific modules)
 * - In-memory log ring buffer for on-device debugging (Settings > Debug Log)
 * - Timestamped entries for performance analysis
 * - Event correlation IDs for tracing a shutter press through the pipeline
 *
 * Usage:
 *   logger.i("camera", "Opened camera with config: $config")
 *   logger.e("burst", "Buffer flush failed", exception)
 *   logger.perf("snow_exposure", "Frame analysis", startTime)
 */
@Singleton
class GateShotLogger @Inject constructor() {

    enum class Level(val tag: String, val priority: Int) {
        VERBOSE("V", 0),
        DEBUG("D", 1),
        INFO("I", 2),
        WARN("W", 3),
        ERROR("E", 4)
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val module: String,
        val message: String,
        val throwable: Throwable? = null,
        val correlationId: String? = null
    ) {
        val timeFormatted: String
            get() = dateFormat.format(Date(timestamp))

        val formatted: String
            get() {
                val corr = if (correlationId != null) " [$correlationId]" else ""
                val err = if (throwable != null) " | ${throwable.message}" else ""
                return "$timeFormatted ${level.tag} [$module]$corr $message$err"
            }

        companion object {
            private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }

    // In-memory ring buffer for on-device debug log viewer
    private val maxLogEntries = 2000
    private val logBuffer = ArrayDeque<LogEntry>(maxLogEntries)

    private val _logFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logFlow: StateFlow<List<LogEntry>> = _logFlow.asStateFlow()

    // Per-module enable/disable
    private val disabledModules = mutableSetOf<String>()
    var minLevel: Level = Level.DEBUG

    // --- Main logging methods ---

    fun v(module: String, message: String, correlationId: String? = null) =
        log(Level.VERBOSE, module, message, null, correlationId)

    fun d(module: String, message: String, correlationId: String? = null) =
        log(Level.DEBUG, module, message, null, correlationId)

    fun i(module: String, message: String, correlationId: String? = null) =
        log(Level.INFO, module, message, null, correlationId)

    fun w(module: String, message: String, throwable: Throwable? = null, correlationId: String? = null) =
        log(Level.WARN, module, message, throwable, correlationId)

    fun e(module: String, message: String, throwable: Throwable? = null, correlationId: String? = null) =
        log(Level.ERROR, module, message, throwable, correlationId)

    /**
     * Log a performance measurement.
     * Usage: val start = System.nanoTime(); ... ; logger.perf("module", "operation", start)
     */
    fun perf(module: String, operation: String, startNanos: Long, correlationId: String? = null) {
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000f
        d(module, "⏱ $operation: ${"%.1f".format(elapsedMs)}ms", correlationId)
    }

    /**
     * Generate a correlation ID for tracing an event through the pipeline.
     * E.g., a shutter press generates an ID that appears in burst, buffer, culling, session logs.
     */
    fun correlationId(): String = "gs-${System.currentTimeMillis() % 100000}"

    // --- Filtering ---

    fun setModuleEnabled(module: String, enabled: Boolean) {
        if (enabled) disabledModules.remove(module) else disabledModules.add(module)
    }

    fun updateMinLevel(level: Level) {
        minLevel = level
    }

    // --- Buffer access ---

    fun getRecentLogs(count: Int = 100, module: String? = null, minLevel: Level? = null): List<LogEntry> {
        return synchronized(logBuffer) {
            logBuffer.filter { entry ->
                (module == null || entry.module == module) &&
                (minLevel == null || entry.level.priority >= minLevel.priority)
            }.takeLast(count)
        }
    }

    fun getLogsByCorrelation(correlationId: String): List<LogEntry> {
        return synchronized(logBuffer) {
            logBuffer.filter { it.correlationId == correlationId }
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) { logBuffer.clear() }
        _logFlow.value = emptyList()
    }

    fun getModuleStats(): Map<String, ModuleLogStats> {
        return synchronized(logBuffer) {
            logBuffer.groupBy { it.module }.mapValues { (_, entries) ->
                ModuleLogStats(
                    totalEntries = entries.size,
                    errors = entries.count { it.level == Level.ERROR },
                    warnings = entries.count { it.level == Level.WARN },
                    lastEntry = entries.lastOrNull()?.timeFormatted ?: ""
                )
            }
        }
    }

    /**
     * Export logs as a text block for sharing (e.g., bug report).
     */
    fun exportLogs(count: Int = 500): String {
        val entries = getRecentLogs(count)
        val header = "GateShot Debug Log — ${entries.size} entries\n" +
            "Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
        return header + entries.joinToString("\n") { it.formatted }
    }

    // --- Internal ---

    private fun log(level: Level, module: String, message: String, throwable: Throwable?, correlationId: String?) {
        if (level.priority < minLevel.priority) return
        if (module in disabledModules) return

        val entry = LogEntry(System.currentTimeMillis(), level, module, message, throwable, correlationId)

        // Write to Android logcat
        val logTag = "GateShot"
        val logMessage = "[${entry.module}] $message"
        when (level) {
            Level.VERBOSE -> Log.v(logTag, logMessage, throwable)
            Level.DEBUG -> Log.d(logTag, logMessage, throwable)
            Level.INFO -> Log.i(logTag, logMessage, throwable)
            Level.WARN -> Log.w(logTag, logMessage, throwable)
            Level.ERROR -> Log.e(logTag, logMessage, throwable)
        }

        // Write to ring buffer
        synchronized(logBuffer) {
            if (logBuffer.size >= maxLogEntries) logBuffer.removeFirst()
            logBuffer.addLast(entry)
        }

        // Update flow for UI observers
        _logFlow.value = synchronized(logBuffer) { logBuffer.toList().takeLast(100) }
    }
}

data class ModuleLogStats(
    val totalEntries: Int,
    val errors: Int,
    val warnings: Int,
    val lastEntry: String
)
