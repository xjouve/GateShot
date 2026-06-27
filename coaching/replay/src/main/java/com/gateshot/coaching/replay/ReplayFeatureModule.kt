package com.gateshot.coaching.replay

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.platform.sensor.SensorPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplayFeatureModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus,
    private val cameraXPlatform: CameraXPlatform,
    private val sensorPlatform: SensorPlatform
) : FeatureModule {

    override val name = "replay"
    override val version = "0.1.0"
    override val requiredMode = AppMode.COACH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var lastRecordedClipUri: String? = null

    private val _replayState = MutableStateFlow(ReplayState())
    val replayState: StateFlow<ReplayState> = _replayState.asStateFlow()

    // Reference capture state
    private var isCapturingReference = false
    private var captureRotationAccum = 0f
    private var lastGyroTimestamp = 0L
    private var capturedFrameCount = 0

    // Frame listener for reference capture — grabs the preview bitmap (RGB) for gate color detection
    private val referenceFrameListener: (androidx.camera.core.ImageProxy) -> Unit = { _ ->
        if (isCapturingReference) {
            // The ImageProxy from BitmapImageProxy only has Y-plane (grayscale).
            // Gate detection needs RGB to distinguish red vs blue poles.
            // Grab the actual color bitmap from the PreviewView instead.
            val bitmap = cameraXPlatform.getPreviewBitmap()
            if (bitmap != null) {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                courseCapture.addFrame(pixels, w, h, captureRotationAccum)
                capturedFrameCount++
                _replayState.value = _replayState.value.copy(
                    referenceFramesCaptured = capturedFrameCount
                )
            }
        }
    }

    // Gate crossing timestamps accumulated during current recording
    private val pendingGateTimestamps = mutableListOf<Long>()
    private var recordingStartMs = 0L

    override suspend fun initialize() {
        // Track recording lifecycle for gate timestamp capture
        eventBus.collect<AppEvent.VideoRecordingStarted>(scope) {
            pendingGateTimestamps.clear()
            recordingStartMs = System.currentTimeMillis()
        }

        // Track latest recorded clip + save gate timestamps as sidecar
        eventBus.collect<AppEvent.VideoRecordingStopped>(scope) { event ->
            lastRecordedClipUri = event.clipUri
            if (pendingGateTimestamps.isNotEmpty()) {
                saveGateTimestamps(event.clipUri, pendingGateTimestamps.toList())
            }
        }

        // Collect gate crossing events from TrackingFeatureModule
        eventBus.collect<AppEvent.GateCrossing>(scope) { event ->
            val ms = System.currentTimeMillis() - recordingStartMs
            pendingGateTimestamps.add(ms)
        }

        // Try to load persisted course reference for current session
        loadPersistedReference()
    }

    override suspend fun shutdown() {
        stopReferenceCapture()
        player?.release()
        player = null
    }

    val overlayEngine = RunOverlayEngine()

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        LoadClip(),
        PlayControl(),
        GetReplayStatus(),
        SetPlaybackSpeed(),
        SeekTo(),
        SetupSplitScreen(),
        // Course reference capture
        StartReferenceCapture(),
        StopReferenceCapture(),
        // Overlay endpoints
        AddOverlayLayer(),
        RemoveOverlayLayer(),
        SetOverlayMode(),
        SetOverlaySync(),
        SetLayerOpacity(),
        ToggleLayer(),
        NavigateGate(),
        GetOverlayConfig(),
        GetTimingDeltas(),
        SetWipePosition(),
        ClearOverlay()
    )

    override fun healthCheck(): ModuleHealth {
        val layers = overlayEngine.getConfig().layers.size
        val mode = overlayEngine.getConfig().mode
        return ModuleHealth(name, ModuleHealth.Status.OK,
            if (layers > 0) "Overlay: $layers layers, mode=$mode" else "Ready")
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(context).build().also {
            player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    updateState()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState()
                }
            })
        }
    }

    private fun updateState() {
        val p = player ?: return
        _replayState.value = ReplayState(
            isLoaded = p.mediaItemCount > 0,
            isPlaying = p.isPlaying,
            currentPositionMs = p.currentPosition,
            durationMs = p.duration.coerceAtLeast(0),
            playbackSpeed = p.playbackParameters.speed,
            clipUri = lastRecordedClipUri
        )
    }

    // --- coach/replay/load ---
    inner class LoadClip : ApiEndpoint<LoadClipRequest, Boolean> {
        override val path = "coach/replay/load"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: LoadClipRequest): ApiResponse<Boolean> {
            val uri = request.clipUri ?: lastRecordedClipUri
                ?: return ApiResponse.error(404, "No clip available")
            val p = getOrCreatePlayer()
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            lastRecordedClipUri = uri
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/replay/play ---
    inner class PlayControl : ApiEndpoint<PlayControlRequest, Boolean> {
        override val path = "coach/replay/play"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: PlayControlRequest): ApiResponse<Boolean> {
            val p = player ?: return ApiResponse.error(409, "No clip loaded")
            when (request.action) {
                "play" -> p.play()
                "pause" -> p.pause()
                "toggle" -> if (p.isPlaying) p.pause() else p.play()
            }
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/replay/status ---
    inner class GetReplayStatus : ApiEndpoint<Unit, ReplayState> {
        override val path = "coach/replay/status"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<ReplayState> {
            updateState()
            return ApiResponse.success(_replayState.value)
        }
    }

    // --- coach/replay/speed ---
    inner class SetPlaybackSpeed : ApiEndpoint<Float, Boolean> {
        override val path = "coach/replay/speed"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Float): ApiResponse<Boolean> {
            val speed = request.coerceIn(0.1f, 4.0f)
            player?.setPlaybackSpeed(speed)
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/replay/seek ---
    inner class SeekTo : ApiEndpoint<Long, Boolean> {
        override val path = "coach/replay/seek"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Long): ApiResponse<Boolean> {
            player?.seekTo(request.coerceAtLeast(0))
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/compare/split ---
    inner class SetupSplitScreen : ApiEndpoint<SplitScreenRequest, Boolean> {
        override val path = "coach/compare/split"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: SplitScreenRequest): ApiResponse<Boolean> {
            // Store split-screen config — UI layer will read this to render two players
            _replayState.value = _replayState.value.copy(
                splitScreen = SplitScreenConfig(
                    leftClipUri = request.leftClipUri,
                    rightClipUri = request.rightClipUri,
                    syncMode = request.syncMode
                )
            )
            return ApiResponse.success(true)
        }
    }

    // =============================================
    // OVERLAY ENDPOINTS
    // =============================================

    private val courseCapture = CourseReferenceCapture()

    /**
     * Start capturing the course reference panorama.
     * Registers a frame listener on CameraXPlatform and accumulates gyro rotation.
     */
    fun startReferenceCapture() {
        if (isCapturingReference) return
        isCapturingReference = true
        capturedFrameCount = 0
        captureRotationAccum = 0f
        lastGyroTimestamp = 0L
        courseCapture.startCapture()

        // Register frame listener to feed camera frames
        cameraXPlatform.addFrameListener(referenceFrameListener)

        // Accumulate gyro yaw rotation for frame spacing
        scope.launch {
            sensorPlatform.getGyroscopeReadings().collect { gyro ->
                if (!isCapturingReference) return@collect
                val now = System.nanoTime()
                if (lastGyroTimestamp > 0) {
                    val dtSec = (now - lastGyroTimestamp) / 1_000_000_000f
                    // Y-axis gyro = yaw (panning left-right)
                    captureRotationAccum += Math.toDegrees(gyro.y.toDouble()).toFloat() * dtSec
                }
                lastGyroTimestamp = now
            }
        }

        _replayState.value = _replayState.value.copy(
            isCapturingReference = true,
            referenceFramesCaptured = 0
        )
    }

    /**
     * Stop capturing and finalize the course reference.
     * Stitches frames, detects gates, persists to disk.
     * Returns the number of gates detected, or -1 on failure.
     */
    fun stopReferenceCapture(): Int {
        if (!isCapturingReference) return -1
        isCapturingReference = false
        cameraXPlatform.removeFrameListener(referenceFrameListener)

        val reference = courseCapture.stopCapture()
        if (reference == null) {
            _replayState.value = _replayState.value.copy(
                isCapturingReference = false,
                referenceFramesCaptured = 0
            )
            return -1
        }

        overlayEngine.setCourseReference(reference)
        persistReference(reference)

        _replayState.value = _replayState.value.copy(
            isCapturingReference = false,
            hasReference = true,
            referenceGateCount = reference.gates.size
        )
        return reference.gates.size
    }

    /**
     * Register a video layer's perspective against the course reference.
     * Extracts the first frame from the video, detects gates, and computes
     * the homography to warp it into the reference coordinate space.
     */
    fun registerLayerFromVideo(layerId: String, videoUri: String): Boolean {
        val ref = overlayEngine.getConfig().hasReference
        if (!ref) return false

        return scope.launch(Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(videoUri)
                val firstFrame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (firstFrame == null) return@launch

                val w = firstFrame.width
                val h = firstFrame.height
                val pixels = IntArray(w * h)
                firstFrame.getPixels(pixels, 0, w, 0, 0, w, h)
                firstFrame.recycle()

                // Detect gates in this video frame using the same algorithm as CourseReferenceCapture
                val frameGates = detectGatesInFrame(pixels, w, h)
                if (frameGates.size >= 4) {
                    overlayEngine.registerLayerPerspective(layerId, frameGates)
                }
            } catch (e: Exception) {
                android.util.Log.e("Replay", "Layer registration failed: ${e.message}")
            }
        }.let { true }
    }

    /**
     * Detect gates in a single video frame (same algorithm as CourseReferenceCapture).
     */
    private fun detectGatesInFrame(pixels: IntArray, width: Int, height: Int): List<CourseReferenceCapture.GatePosition> {
        val gates = mutableListOf<CourseReferenceCapture.GatePosition>()
        val columnSize = 8
        var gateId = 1

        for (col in 0 until width step columnSize) {
            var redCount = 0
            var blueCount = 0
            var totalInColumn = 0
            var avgY = 0f

            for (y in 0 until height step 4) {
                for (dx in 0 until columnSize) {
                    val x = col + dx
                    if (x >= width) continue
                    val idx = y * width + x
                    if (idx >= pixels.size) continue

                    val pixel = pixels[idx]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    if (r > 150 && r > g * 2 && r > b * 2) {
                        redCount++
                        avgY += y
                        totalInColumn++
                    }
                    if (b > 150 && b > r * 1.5 && b > g * 1.5) {
                        blueCount++
                        avgY += y
                        totalInColumn++
                    }
                }
            }

            val minPixelsForGate = (height / 4) / 4
            if (redCount > minPixelsForGate || blueCount > minPixelsForGate) {
                val color = if (redCount > blueCount) CourseReferenceCapture.GateColor.RED
                            else CourseReferenceCapture.GateColor.BLUE
                val count = kotlin.math.max(redCount, blueCount)
                val confidence = (count.toFloat() / (height / 4)).coerceIn(0f, 1f)
                val normalizedX = (col + columnSize / 2f) / width

                val nearbyGate = gates.lastOrNull()
                if (nearbyGate != null && kotlin.math.abs(nearbyGate.x - normalizedX) < 20f / width) {
                    gates[gates.size - 1] = nearbyGate.copy(
                        x = (nearbyGate.x + normalizedX) / 2f,
                        confidence = kotlin.math.max(nearbyGate.confidence, confidence)
                    )
                } else {
                    gates.add(CourseReferenceCapture.GatePosition(
                        id = gateId++,
                        x = normalizedX,
                        y = if (totalInColumn > 0) (avgY / totalInColumn) / height else 0.5f,
                        color = color,
                        confidence = confidence
                    ))
                }
            }
        }

        return gates
    }

    // --- Gate Timestamp Sidecar Files ---

    /**
     * Save gate crossing timestamps alongside the video file.
     * Format: <video_name>.gates (one timestamp per line in ms).
     */
    private fun saveGateTimestamps(videoUri: String, timestamps: List<Long>) {
        scope.launch(Dispatchers.IO) {
            try {
                val videoFile = File(android.net.Uri.parse(videoUri).path ?: return@launch)
                val gatesFile = File(videoFile.parent, videoFile.nameWithoutExtension + ".gates")
                gatesFile.writeText(timestamps.joinToString("\n"))
                android.util.Log.i("Replay", "Saved ${timestamps.size} gate timestamps to ${gatesFile.name}")
            } catch (e: Exception) {
                android.util.Log.e("Replay", "Failed to save gate timestamps: ${e.message}")
            }
        }
    }

    /**
     * Load gate crossing timestamps for a video file.
     * Returns the timestamps in ms relative to recording start.
     */
    fun loadGateTimestamps(videoUri: String): List<Long> {
        return try {
            val videoFile = File(android.net.Uri.parse(videoUri).path ?: return emptyList())
            val gatesFile = File(videoFile.parent, videoFile.nameWithoutExtension + ".gates")
            if (!gatesFile.exists()) return emptyList()
            gatesFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { it.trim().toLongOrNull() }
        } catch (_: Exception) { emptyList() }
    }

    // --- Persistence ---

    private fun getReferenceDir(): File {
        val dir = File(context.getExternalFilesDir(null), "GateShot/reference")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Persist course reference panorama + gate positions to disk.
     * Format: binary file with header + gate list + pixel data.
     */
    private fun persistReference(ref: CourseReferenceCapture.CourseReference) {
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(getReferenceDir(), "course_reference.dat")
                java.io.DataOutputStream(java.io.BufferedOutputStream(file.outputStream())).use { out ->
                    // Header
                    out.writeInt(ref.panoramaWidth)
                    out.writeInt(ref.panoramaHeight)
                    out.writeLong(ref.captureTimestamp)
                    out.writeUTF(ref.cameraPositionDescription)

                    // Gates
                    out.writeInt(ref.gates.size)
                    for (gate in ref.gates) {
                        out.writeInt(gate.id)
                        out.writeFloat(gate.x)
                        out.writeFloat(gate.y)
                        out.writeUTF(gate.color.name)
                        out.writeFloat(gate.confidence)
                    }

                    // Panorama pixels
                    out.writeInt(ref.panoramaPixels.size)
                    for (px in ref.panoramaPixels) {
                        out.writeInt(px)
                    }
                }
                android.util.Log.i("Replay", "Course reference saved: ${ref.gates.size} gates, ${ref.panoramaWidth}x${ref.panoramaHeight}")
            } catch (e: Exception) {
                android.util.Log.e("Replay", "Failed to save reference: ${e.message}")
            }
        }
    }

    /**
     * Load a previously persisted course reference from disk.
     */
    private fun loadPersistedReference() {
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(getReferenceDir(), "course_reference.dat")
                if (!file.exists()) return@launch

                java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { inp ->
                    val w = inp.readInt()
                    val h = inp.readInt()
                    val timestamp = inp.readLong()
                    val posDesc = inp.readUTF()

                    val gateCount = inp.readInt()
                    val gates = mutableListOf<CourseReferenceCapture.GatePosition>()
                    for (i in 0 until gateCount) {
                        gates.add(CourseReferenceCapture.GatePosition(
                            id = inp.readInt(),
                            x = inp.readFloat(),
                            y = inp.readFloat(),
                            color = CourseReferenceCapture.GateColor.valueOf(inp.readUTF()),
                            confidence = inp.readFloat()
                        ))
                    }

                    val pixelCount = inp.readInt()
                    val pixels = IntArray(pixelCount)
                    for (i in 0 until pixelCount) {
                        pixels[i] = inp.readInt()
                    }

                    val ref = CourseReferenceCapture.CourseReference(
                        panoramaWidth = w,
                        panoramaHeight = h,
                        panoramaPixels = pixels,
                        gates = gates,
                        captureTimestamp = timestamp,
                        cameraPositionDescription = posDesc
                    )
                    overlayEngine.setCourseReference(ref)
                    _replayState.value = _replayState.value.copy(
                        hasReference = true,
                        referenceGateCount = gates.size
                    )
                    android.util.Log.i("Replay", "Loaded course reference: ${gates.size} gates")
                }
            } catch (e: Exception) {
                android.util.Log.e("Replay", "Failed to load reference: ${e.message}")
            }
        }
    }

    // --- coach/overlay/reference/start ---
    inner class StartReferenceCapture : ApiEndpoint<Unit, Boolean> {
        override val path = "coach/overlay/reference/start"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            startReferenceCapture()
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/reference/stop ---
    inner class StopReferenceCapture : ApiEndpoint<Unit, Boolean> {
        override val path = "coach/overlay/reference/stop"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            val gateCount = stopReferenceCapture()
            if (gateCount < 0) {
                return ApiResponse.error(400, "Not enough frames captured. Pan slowly across the course.")
            }
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/layer/add ---
    inner class AddOverlayLayer : ApiEndpoint<AddLayerRequest, RunOverlayEngine.OverlayLayer> {
        override val path = "coach/overlay/layer/add"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: AddLayerRequest): ApiResponse<RunOverlayEngine.OverlayLayer> {
            // Auto-load gate timestamps from sidecar file if not provided
            val gateTimestamps = request.gateTimestamps.ifEmpty {
                loadGateTimestamps(request.clipUri)
            }

            val layer = overlayEngine.addLayer(
                clipUri = request.clipUri,
                label = request.label,
                color = request.color ?: "#4FC3F7",
                gateTimestamps = gateTimestamps
            )

            // Auto-register perspective against course reference if available
            if (overlayEngine.getConfig().hasReference) {
                registerLayerFromVideo(layer.id, request.clipUri)
            }

            _replayState.value = _replayState.value.copy(
                overlayLayerCount = overlayEngine.getConfig().layers.size,
                perspectiveCorrectionActive = overlayEngine.isLayerRegistered(layer.id)
            )
            return ApiResponse.success(layer)
        }
    }

    // --- coach/overlay/layer/remove ---
    inner class RemoveOverlayLayer : ApiEndpoint<String, Boolean> {
        override val path = "coach/overlay/layer/remove"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            overlayEngine.removeLayer(request)
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/mode ---
    inner class SetOverlayMode : ApiEndpoint<String, Boolean> {
        override val path = "coach/overlay/mode"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            val mode = try { RunOverlayEngine.OverlayMode.valueOf(request.uppercase()) }
                catch (_: Exception) { return ApiResponse.error(400, "Invalid mode: $request") }
            overlayEngine.setMode(mode)
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/sync ---
    inner class SetOverlaySync : ApiEndpoint<String, Boolean> {
        override val path = "coach/overlay/sync"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            val sync = try { RunOverlayEngine.SyncMode.valueOf(request.uppercase()) }
                catch (_: Exception) { return ApiResponse.error(400, "Invalid sync mode: $request") }
            overlayEngine.setSyncMode(sync)
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/layer/opacity ---
    inner class SetLayerOpacity : ApiEndpoint<LayerOpacityRequest, Boolean> {
        override val path = "coach/overlay/layer/opacity"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: LayerOpacityRequest): ApiResponse<Boolean> {
            overlayEngine.setLayerOpacity(request.layerId, request.opacity)
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/layer/toggle ---
    inner class ToggleLayer : ApiEndpoint<String, Boolean> {
        override val path = "coach/overlay/layer/toggle"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            overlayEngine.toggleLayer(request)
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/gate ---
    inner class NavigateGate : ApiEndpoint<String, Boolean> {
        override val path = "coach/overlay/gate"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<Boolean> {
            when (request) {
                "next" -> overlayEngine.nextGate()
                "prev" -> overlayEngine.previousGate()
                else -> {
                    val gate = request.toIntOrNull() ?: return ApiResponse.error(400, "Invalid gate: $request")
                    overlayEngine.navigateToGate(gate)
                }
            }
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/config ---
    inner class GetOverlayConfig : ApiEndpoint<Unit, RunOverlayEngine.OverlayConfig> {
        override val path = "coach/overlay/config"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<RunOverlayEngine.OverlayConfig> {
            return ApiResponse.success(overlayEngine.getConfig())
        }
    }

    // --- coach/overlay/deltas ---
    inner class GetTimingDeltas : ApiEndpoint<Unit, List<GateDelta>> {
        override val path = "coach/overlay/deltas"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<List<GateDelta>> {
            return ApiResponse.success(overlayEngine.getTimingDeltas())
        }
    }

    // --- coach/overlay/wipe ---
    inner class SetWipePosition : ApiEndpoint<Float, Boolean> {
        override val path = "coach/overlay/wipe"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Float): ApiResponse<Boolean> {
            overlayEngine.setWipePosition(request)
            return ApiResponse.success(true)
        }
    }

    // --- coach/overlay/clear ---
    inner class ClearOverlay : ApiEndpoint<Unit, Boolean> {
        override val path = "coach/overlay/clear"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            overlayEngine.clear()
            return ApiResponse.success(true)
        }
    }
}

data class AddLayerRequest(
    val clipUri: String,
    val label: String,
    val color: String? = null,
    val gateTimestamps: List<Long> = emptyList()
)

data class LayerOpacityRequest(
    val layerId: String,
    val opacity: Float
)

data class ReplayState(
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val clipUri: String? = null,
    val splitScreen: SplitScreenConfig? = null,
    // Course reference capture state
    val isCapturingReference: Boolean = false,
    val referenceFramesCaptured: Int = 0,
    val hasReference: Boolean = false,
    val referenceGateCount: Int = 0,
    // Overlay state
    val overlayLayerCount: Int = 0,
    val overlayMode: String = "GHOST",
    val currentGate: Int = 0,
    val perspectiveCorrectionActive: Boolean = false
)

data class LoadClipRequest(val clipUri: String? = null)
data class PlayControlRequest(val action: String = "toggle")  // play, pause, toggle
data class SplitScreenRequest(
    val leftClipUri: String,
    val rightClipUri: String,
    val syncMode: String = "gate"  // gate, timestamp, manual
)
data class SplitScreenConfig(
    val leftClipUri: String,
    val rightClipUri: String,
    val syncMode: String
)
