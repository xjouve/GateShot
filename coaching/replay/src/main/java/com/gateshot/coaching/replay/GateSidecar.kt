package com.gateshot.coaching.replay

import android.util.Log

/**
 * Pure parse/format helpers for the `.gates` sidecar file format: one
 * video-position timestamp (ms) per line. Extracted from ReplayFeatureModule
 * so the format logic can be unit tested without Android/Room/File deps.
 */

/**
 * Parse `.gates` sidecar lines into timestamps (ms).
 * Blank lines are skipped silently. Non-numeric or negative lines are
 * skipped with a warning, and otherwise don't stop parsing of the rest.
 */
fun parseGateTimestamps(lines: List<String>): List<Long> {
    val result = mutableListOf<Long>()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val value = trimmed.toLongOrNull()
        if (value == null) {
            Log.w("GateSidecar", "skipping malformed line: $line")
            continue
        }
        if (value < 0) {
            Log.w("GateSidecar", "skipping malformed line: $line")
            continue
        }
        result.add(value)
    }
    return result
}

/**
 * Format timestamps (ms) into the on-disk `.gates` sidecar format: one
 * video-position ms value per line.
 */
fun formatGateTimestamps(timestamps: List<Long>): String {
    return timestamps.joinToString("\n")
}
