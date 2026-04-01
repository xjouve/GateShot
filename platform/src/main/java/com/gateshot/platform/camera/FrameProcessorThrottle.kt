package com.gateshot.platform.camera

import android.os.SystemClock
import android.util.Log

/**
 * Frame Processor Throttle — Prevents frame listeners from overloading the pipeline.
 *
 * PROBLEM:
 * GateShot has 5+ frame listeners running simultaneously:
 * - Pre-capture buffer (burst module)
 * - Snow exposure analyzer
 * - Racer tracker
 * - Bib detection
 * - Gate-zone trigger motion detector
 *
 * If all process every frame, the pipeline stalls and fps drops.
 *
 * SOLUTION:
 * Adaptive throttling based on actual processing time:
 * 1. Measure how long each listener takes per frame
 * 2. If total processing exceeds the frame budget (33ms at 30fps), skip
 *    non-critical listeners on alternate frames
 * 3. Priority system: buffer > tracker > snow > trigger > bib detection
 *
 * The Dimensity 9500 is powerful, but 200MP frames are large and we're
 * running 5 listeners concurrently. This throttle ensures smooth fps.
 */
class FrameProcessorThrottle {

    data class ListenerStats(
        val name: String,
        val priority: Int,          // 0 = highest (never skip), 10 = lowest (skip first)
        var avgProcessingMs: Float = 0f,
        var framesSinceLastRun: Int = 0,
        var lastRunTimeMs: Long = 0,
        var totalRunCount: Long = 0,
        var totalSkipCount: Long = 0
    )

    private val stats = mutableMapOf<String, ListenerStats>()
    private var frameBudgetMs = 33f  // 30fps default
    private var lastFrameTimestamp = 0L
    private var actualFps = 30f

    // Priority levels
    companion object {
        const val PRIORITY_CRITICAL = 0   // Pre-capture buffer — never skip
        const val PRIORITY_HIGH = 2       // Racer tracker — essential for AF
        const val PRIORITY_MEDIUM = 5     // Snow exposure — runs every 5th frame anyway
        const val PRIORITY_LOW = 7        // Gate-zone trigger — can skip occasional frames
        const val PRIORITY_BACKGROUND = 9 // Bib detection — runs infrequently
    }

    fun registerListener(name: String, priority: Int) {
        stats[name] = ListenerStats(name, priority)
    }

    fun unregisterListener(name: String) {
        stats.remove(name)
    }

    /**
     * Check if a listener should process this frame.
     * Returns true if the listener should run, false if it should skip.
     */
    fun shouldProcess(name: String): Boolean {
        val listenerStats = stats[name] ?: return true

        // Critical priority — always run
        if (listenerStats.priority == PRIORITY_CRITICAL) return true

        // Calculate remaining budget
        val totalLoad = stats.values.sumOf { it.avgProcessingMs.toDouble() }.toFloat()

        if (totalLoad <= frameBudgetMs) {
            // Under budget — everyone runs
            listenerStats.framesSinceLastRun = 0
            return true
        }

        // Over budget — skip low-priority listeners based on load
        val overBudgetRatio = totalLoad / frameBudgetMs
        val skipThreshold = when {
            overBudgetRatio > 3f -> PRIORITY_HIGH      // Very overloaded — skip everything below HIGH
            overBudgetRatio > 2f -> PRIORITY_MEDIUM     // Moderately overloaded
            overBudgetRatio > 1.5f -> PRIORITY_LOW      // Slightly overloaded
            else -> PRIORITY_BACKGROUND                  // Just barely over
        }

        if (listenerStats.priority >= skipThreshold) {
            listenerStats.framesSinceLastRun++
            listenerStats.totalSkipCount++

            // But don't skip too many consecutive frames — run at least every Nth
            val maxConsecutiveSkips = when (listenerStats.priority) {
                PRIORITY_HIGH -> 2
                PRIORITY_MEDIUM -> 5
                PRIORITY_LOW -> 10
                else -> 15
            }
            if (listenerStats.framesSinceLastRun >= maxConsecutiveSkips) {
                listenerStats.framesSinceLastRun = 0
                return true
            }
            return false
        }

        listenerStats.framesSinceLastRun = 0
        return true
    }

    /**
     * Record how long a listener took to process a frame.
     * Used to update adaptive throttling.
     */
    fun recordProcessingTime(name: String, processingMs: Float) {
        val listenerStats = stats[name] ?: return
        // Exponential moving average
        listenerStats.avgProcessingMs = listenerStats.avgProcessingMs * 0.8f + processingMs * 0.2f
        listenerStats.lastRunTimeMs = SystemClock.elapsedRealtime()
        listenerStats.totalRunCount++
    }

    fun setTargetFps(fps: Int) {
        frameBudgetMs = 1000f / fps
    }

    fun getStats(): Map<String, ListenerStats> = stats.toMap()

    fun getTotalLoadMs(): Float = stats.values.sumOf { it.avgProcessingMs.toDouble() }.toFloat()

    fun getLoadPercentage(): Float = (getTotalLoadMs() / frameBudgetMs * 100).coerceIn(0f, 999f)

    fun logStats() {
        val load = getLoadPercentage()
        Log.d("FrameThrottle", "Load: ${load.toInt()}% (${getTotalLoadMs().toInt()}ms / ${frameBudgetMs.toInt()}ms budget)")
        stats.values.sortedBy { it.priority }.forEach { s ->
            Log.d("FrameThrottle", "  ${s.name}: ${s.avgProcessingMs.toInt()}ms avg, " +
                "priority=${s.priority}, runs=${s.totalRunCount}, skips=${s.totalSkipCount}")
        }
    }
}
