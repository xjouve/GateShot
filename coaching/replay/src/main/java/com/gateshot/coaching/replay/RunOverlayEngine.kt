package com.gateshot.coaching.replay

import kotlinx.serialization.Serializable

/**
 * Run Overlay Engine — Layer multiple runs on top of each other.
 *
 * This is THE coaching feature for ski racing. A coach places two (or more)
 * runs in a layered stack, each as a semi-transparent video. The racer
 * sees exactly where their line, timing, or body position differs.
 *
 * Overlay modes:
 *
 * 1. GHOST — Reference run as a semi-transparent silhouette on top of the
 *    current run. Classic "racing game ghost car" concept applied to real video.
 *
 * 2. DIFFERENCE — Pixel-level difference between runs. Areas where the racer
 *    is in the same position glow dim; areas of divergence glow bright.
 *    Instantly shows WHERE on the course the lines diverge.
 *
 * 3. TRAIL — Extract the racer's trajectory from each run and draw colored
 *    lines showing the path. Red line = slow run, green line = fast run.
 *    Overlaid on a single freeze-frame of the course.
 *
 * 4. WIPE — Vertical or horizontal wipe between two runs. Drag the wipe
 *    line to compare body position at the same gate passage.
 *
 * Synchronization:
 * - BY_GATE: Align runs at each gate passage (default — most useful)
 * - BY_TIME: Align by absolute elapsed time from start
 * - BY_DISTANCE: Align by estimated distance along the course
 * - MANUAL: User sets alignment points manually
 */
class RunOverlayEngine {

    data class OverlayLayer(
        val id: String,
        val clipUri: String,
        val label: String,               // e.g., "Run 1", "Best Run", "Bib #14"
        val color: String = "#4FC3F7",   // Layer tint color
        val opacity: Float = 0.6f,       // 0.0 = invisible, 1.0 = fully opaque
        val isReference: Boolean = false, // Reference layer = fully opaque, on bottom
        val enabled: Boolean = true,
        val gateTimestamps: List<Long> = emptyList(), // ms timestamps of each gate passage
        val startTimestampMs: Long = 0
    )

    @Serializable
    enum class OverlayMode {
        GHOST,       // Semi-transparent overlay
        DIFFERENCE,  // Pixel difference highlight
        TRAIL,       // Trajectory lines only
        WIPE         // Split wipe comparison
    }

    @Serializable
    enum class SyncMode {
        BY_GATE,     // Sync at each gate passage
        BY_TIME,     // Sync by elapsed time
        BY_DISTANCE, // Sync by estimated course distance
        MANUAL       // User-defined sync points
    }

    data class OverlayConfig(
        val mode: OverlayMode = OverlayMode.GHOST,
        val syncMode: SyncMode = SyncMode.BY_GATE,
        val layers: MutableList<OverlayLayer> = mutableListOf(),
        val currentGate: Int = 0,         // Which gate we're synced to
        val wipePosition: Float = 0.5f,   // For WIPE mode: 0.0 = all left, 1.0 = all right
        val wipeVertical: Boolean = true,  // Vertical or horizontal wipe
        val trailThickness: Float = 4f,    // For TRAIL mode: line width
        val differenceGain: Float = 3f,    // For DIFFERENCE mode: amplify differences
        val hasReference: Boolean = false,  // Is a course reference panorama loaded?
        val perspectiveCorrectionEnabled: Boolean = true // Auto-warp layers to match reference
    )

    private var config = OverlayConfig()

    fun getConfig(): OverlayConfig = config

    fun setMode(mode: OverlayMode) {
        config = config.copy(mode = mode)
    }

    fun setSyncMode(syncMode: SyncMode) {
        config = config.copy(syncMode = syncMode)
    }

    /**
     * Add a run as a layer. First layer added becomes the reference (fully opaque).
     */
    fun addLayer(
        clipUri: String,
        label: String,
        color: String = "#4FC3F7",
        gateTimestamps: List<Long> = emptyList()
    ): OverlayLayer {
        val isFirst = config.layers.isEmpty()
        val layer = OverlayLayer(
            id = "layer_${System.currentTimeMillis()}",
            clipUri = clipUri,
            label = label,
            color = if (isFirst) "#FFFFFF" else color,
            opacity = if (isFirst) 1.0f else 0.6f,
            isReference = isFirst,
            gateTimestamps = gateTimestamps
        )
        config.layers.add(layer)
        return layer
    }

    fun removeLayer(layerId: String) {
        config.layers.removeAll { it.id == layerId }
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        val idx = config.layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) {
            config.layers[idx] = config.layers[idx].copy(opacity = opacity.coerceIn(0f, 1f))
        }
    }

    fun toggleLayer(layerId: String) {
        val idx = config.layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) {
            config.layers[idx] = config.layers[idx].copy(enabled = !config.layers[idx].enabled)
        }
    }

    fun setWipePosition(position: Float) {
        config = config.copy(wipePosition = position.coerceIn(0f, 1f))
    }

    fun navigateToGate(gateNumber: Int) {
        config = config.copy(currentGate = gateNumber)
    }

    fun nextGate() {
        config = config.copy(currentGate = config.currentGate + 1)
    }

    fun previousGate() {
        config = config.copy(currentGate = (config.currentGate - 1).coerceAtLeast(0))
    }

    /**
     * Calculate the playback position for each layer at the current gate.
     *
     * When synced BY_GATE, each layer jumps to the timestamp of the current gate
     * in that specific run. This means if run A is 0.3s faster to gate 5 than
     * run B, both videos will show gate 5 at the same moment — revealing the
     * line/body position difference, not the time difference.
     */
    fun getLayerPositions(): List<LayerPlaybackPosition> {
        return config.layers.filter { it.enabled }.map { layer ->
            val positionMs = when (config.syncMode) {
                SyncMode.BY_GATE -> {
                    if (config.currentGate < layer.gateTimestamps.size) {
                        layer.gateTimestamps[config.currentGate]
                    } else {
                        0L
                    }
                }
                SyncMode.BY_TIME -> {
                    // All layers use the same elapsed time
                    val refLayer = config.layers.firstOrNull { it.isReference }
                    if (refLayer != null && config.currentGate < refLayer.gateTimestamps.size) {
                        refLayer.gateTimestamps[config.currentGate]
                    } else 0L
                }
                SyncMode.BY_DISTANCE, SyncMode.MANUAL -> {
                    // Placeholder — would use course distance estimation
                    layer.gateTimestamps.getOrElse(config.currentGate) { 0L }
                }
            }

            LayerPlaybackPosition(
                layerId = layer.id,
                label = layer.label,
                seekPositionMs = positionMs,
                opacity = layer.opacity,
                color = layer.color
            )
        }
    }

    /**
     * Calculate timing deltas between layers at each gate.
     * Returns the time difference between each layer and the reference.
     */
    fun getTimingDeltas(): List<GateDelta> {
        val refLayer = config.layers.firstOrNull { it.isReference } ?: return emptyList()
        val otherLayers = config.layers.filter { !it.isReference && it.enabled }

        val maxGates = refLayer.gateTimestamps.size
        val deltas = mutableListOf<GateDelta>()

        for (gate in 0 until maxGates) {
            val refTime = refLayer.gateTimestamps[gate] - refLayer.startTimestampMs
            val layerDeltas = otherLayers.map { layer ->
                val layerTime = if (gate < layer.gateTimestamps.size) {
                    layer.gateTimestamps[gate] - layer.startTimestampMs
                } else refTime

                LayerDelta(
                    layerId = layer.id,
                    label = layer.label,
                    deltaMs = layerTime - refTime,  // Positive = slower, negative = faster
                    color = layer.color
                )
            }

            deltas.add(GateDelta(
                gateNumber = gate + 1,
                referenceTimeMs = refTime,
                layerDeltas = layerDeltas
            ))
        }

        return deltas
    }

    // --- Perspective Registration ---

    private var courseReference: CourseReferenceCapture.CourseReference? = null
    private val perspectiveRegistration = PerspectiveRegistration()
    private val layerHomographies = mutableMapOf<String, PerspectiveRegistration.Homography>()

    /**
     * Set the course reference panorama (captured before training).
     * All subsequent layers will be registered to this reference.
     */
    fun setCourseReference(reference: CourseReferenceCapture.CourseReference) {
        courseReference = reference
        config = config.copy(hasReference = true)
    }

    /**
     * Register a layer's video to the course reference.
     *
     * Call this when adding a layer — it detects gates in the layer's first frame,
     * matches them to the reference panorama's gates, and computes the perspective
     * transform needed to warp this layer into the reference's coordinate space.
     *
     * @param layerId The layer to register
     * @param frameGates Gates detected in this layer's video frame
     */
    fun registerLayerPerspective(
        layerId: String,
        frameGates: List<CourseReferenceCapture.GatePosition>
    ): Boolean {
        val ref = courseReference ?: return false

        // Find correspondences between this frame's gates and the reference panorama's gates
        val correspondences = perspectiveRegistration.findCorrespondences(frameGates, ref.gates)
        if (correspondences.size < 4) return false

        // Compute homography
        val homography = perspectiveRegistration.computeHomography(correspondences) ?: return false
        layerHomographies[layerId] = homography
        return true
    }

    /**
     * Warp a frame from a specific layer into the reference panorama's coordinate space.
     * This corrects for the coach's position change between runs.
     */
    fun warpLayerFrame(
        layerId: String,
        framePixels: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): IntArray? {
        if (!config.perspectiveCorrectionEnabled) return null
        val homography = layerHomographies[layerId] ?: return null

        return perspectiveRegistration.warpFrame(
            framePixels, frameWidth, frameHeight,
            homography, outputWidth, outputHeight
        )
    }

    /**
     * Check if a layer has been registered to the reference.
     */
    fun isLayerRegistered(layerId: String): Boolean = layerHomographies.containsKey(layerId)

    fun clear() {
        config = OverlayConfig()
        layerHomographies.clear()
        // Keep courseReference — it's valid for the whole session
    }

    fun clearAll() {
        config = OverlayConfig()
        layerHomographies.clear()
        courseReference = null
    }
}

data class LayerPlaybackPosition(
    val layerId: String,
    val label: String,
    val seekPositionMs: Long,
    val opacity: Float,
    val color: String
)

data class GateDelta(
    val gateNumber: Int,
    val referenceTimeMs: Long,
    val layerDeltas: List<LayerDelta>
)

data class LayerDelta(
    val layerId: String,
    val label: String,
    val deltaMs: Long,    // Positive = slower than reference, negative = faster
    val color: String
)
