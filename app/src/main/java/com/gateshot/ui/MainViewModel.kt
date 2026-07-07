package com.gateshot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.config.ConfigStore
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
import com.gateshot.coaching.replay.ReplayFeatureModule
import com.gateshot.coaching.replay.ReplayState
import com.gateshot.videoimport.VideoImportManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val mode: AppMode = AppMode.COACH,
    val sessionName: String? = null,
    val sessionDiscipline: String? = null,
    val activeRunNumber: Int = 0,
    val storageRemainingGb: Float = 400f,
    val moduleStatuses: Map<String, String> = emptyMap()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val modeManager: ModeManager,
    private val eventBus: EventBus,
    val endpointRegistry: EndpointRegistry,
    private val configStore: ConfigStore,
    private val replayModule: ReplayFeatureModule,
    private val videoImportManager: VideoImportManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _galleryRefresh = MutableStateFlow(0)
    val galleryRefresh: StateFlow<Int> = _galleryRefresh.asStateFlow()

    init {
        viewModelScope.launch {
            modeManager.currentMode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
            }
        }

        // Poll storage every 10 seconds (imported clips are copied into app
        // storage, so free space is worth surfacing)
        viewModelScope.launch {
            while (true) {
                val storageDir = appContext.getExternalFilesDir(null)
                val storageGb = if (storageDir != null) {
                    val stat = android.os.StatFs(storageDir.absolutePath)
                    (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024 * 1024)
                } else 0.0
                _uiState.update { it.copy(storageRemainingGb = storageGb.toFloat()) }
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    // --- Video import & selection ---

    /** Clip explicitly chosen in the Library; Replay prefers it over the newest file. */
    private val _selectedVideoPath = MutableStateFlow<String?>(null)
    val selectedVideoPath: StateFlow<String?> = _selectedVideoPath.asStateFlow()

    /** One-shot navigation signal: a clip is ready, switch to the Replay tab. */
    private val _openInReplay = MutableSharedFlow<Unit>()
    val openInReplay: SharedFlow<Unit> = _openInReplay.asSharedFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** Import videos picked from the system Photo Picker into the Library. */
    fun importVideos(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val imported = videoImportManager.import(uris)
                if (imported.isNotEmpty()) {
                    _galleryRefresh.update { it + 1 }
                }
            } finally {
                _isImporting.value = false
            }
        }
    }

    /** Open a Library clip in the Replay tab. */
    fun openVideoInReplay(path: String) {
        viewModelScope.launch {
            _selectedVideoPath.value = path
            _openInReplay.emit(Unit)
        }
    }

    /** Video handed to GateShot from outside (Open with / Share): import, then replay. */
    fun onOpenExternalVideo(uri: android.net.Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val imported = videoImportManager.import(listOf(uri))
                imported.firstOrNull()?.let { file ->
                    _galleryRefresh.update { it + 1 }
                    _selectedVideoPath.value = file.absolutePath
                    _openInReplay.emit(Unit)
                }
            } finally {
                _isImporting.value = false
            }
        }
    }

    // Timing runs are keyed by clip: marking splits on a new clip starts a
    // fresh timing run named after the video file.
    private var activeTimingRunId: String? = null

    fun onRecordSplit(positionMs: Long) {
        viewModelScope.launch {
            try {
                val runId = _selectedVideoPath.value
                    ?.let { java.io.File(it).nameWithoutExtension }
                    ?: "manual"
                if (activeTimingRunId != runId) {
                    endpointRegistry.call<String, Boolean>("coach/timing/run/start", runId)
                    activeTimingRunId = runId
                }
                endpointRegistry.call<com.gateshot.coaching.timing.RecordSplitRequest, com.gateshot.coaching.timing.Split>(
                    "coach/timing/split/record",
                    com.gateshot.coaching.timing.RecordSplitRequest(videoPositionMs = positionMs)
                )
            } catch (_: Exception) { }
        }
    }

    fun startVoiceRecording() {
        viewModelScope.launch {
            try {
                endpointRegistry.call<com.gateshot.coaching.annotation.VoiceOverStartRequest, Boolean>(
                    "coach/annotate/voiceover/start",
                    com.gateshot.coaching.annotation.VoiceOverStartRequest(
                        clipId = annotationClipId ?: "current",
                        videoPositionMs = annotationPositionMs
                    )
                )
            } catch (_: Exception) { }
        }
    }

    fun stopVoiceRecording() {
        viewModelScope.launch {
            try {
                endpointRegistry.call<Unit, com.gateshot.coaching.annotation.VoiceAnnotation>(
                    "coach/annotate/voiceover/stop", Unit
                )
            } catch (_: Exception) { }
        }
    }

    /**
     * Persist the frame currently on the annotation canvas: store the drawn
     * strokes (normalized coords) with the annotation module, then have it
     * render them onto the frame and save the PNG.
     */
    fun onSaveAnnotatedFrame(
        strokes: List<com.gateshot.coaching.annotation.DrawingElement> = emptyList(),
        onSaved: (String?) -> Unit = {}
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val clipId = annotationClipId ?: "current"
                val positionMs = annotationPositionMs

                if (strokes.isNotEmpty()) {
                    endpointRegistry.call<com.gateshot.coaching.annotation.DrawingAnnotation, Boolean>(
                        "coach/annotate/draw/save",
                        com.gateshot.coaching.annotation.DrawingAnnotation(
                            clipId = clipId,
                            framePositionMs = positionMs,
                            elements = strokes
                        )
                    )
                }

                val framePath = _annotationFramePath.value
                val bitmap = framePath?.let { android.graphics.BitmapFactory.decodeFile(it) }
                if (bitmap == null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onSaved(null) }
                    return@launch
                }
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()

                val response = endpointRegistry.call<com.gateshot.coaching.annotation.SaveFrameRequest, String>(
                    "coach/annotate/frame/save",
                    com.gateshot.coaching.annotation.SaveFrameRequest(
                        clipId = clipId,
                        framePositionMs = positionMs,
                        framePixels = pixels,
                        frameWidth = w,
                        frameHeight = h
                    )
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSaved(response.dataOrNull())
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onSaved(null) }
            }
        }
    }

    fun loadSettingFloat(section: String, key: String, default: Float): Float {
        return try {
            val prefs = appContext.getSharedPreferences("gateshot_config", android.content.Context.MODE_PRIVATE)
            prefs.getFloat("${section}_${key}", default)
        } catch (_: Exception) { default }
    }

    fun loadSettingBool(section: String, key: String, default: Boolean): Boolean {
        return try {
            val prefs = appContext.getSharedPreferences("gateshot_config", android.content.Context.MODE_PRIVATE)
            prefs.getBoolean("${section}_${key}", default)
        } catch (_: Exception) { default }
    }

    fun saveSetting(section: String, key: String, value: Any) {
        val prefs = appContext.getSharedPreferences("gateshot_config", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            when (value) {
                is Float -> putFloat("${section}_${key}", value)
                is Boolean -> putBoolean("${section}_${key}", value)
                is Int -> putInt("${section}_${key}", value)
                is String -> putString("${section}_${key}", value)
            }
            apply()
        }
        // Mirror to ConfigStore so modules reading from it see the updated value
        configStore.set(section, key, value)
    }

    /**
     * A video captured outside GateShot has landed in app storage (today via
     * import; historically via the native-capture bridge). Publishes the
     * event SessionFeatureModule listens to for recording media metadata.
     */
    fun onNativeCaptureComplete(absolutePath: String, isVideo: Boolean) {
        viewModelScope.launch {
            eventBus.publish(
                AppEvent.NativeCaptureCompleted(
                    fileUri = "file://$absolutePath",
                    isVideo = isVideo
                )
            )
            _galleryRefresh.update { it + 1 }
        }
    }

    // --- Manual gate tagging ---

    fun markGate(videoPath: String, positionMs: Long, onResult: (List<Long>) -> Unit = {}) {
        viewModelScope.launch {
            val response = endpointRegistry.call<com.gateshot.coaching.replay.GateMarkRequest, List<Long>>(
                "coach/gates/mark",
                com.gateshot.coaching.replay.GateMarkRequest(videoPath, positionMs)
            )
            response.dataOrNull()?.let { onResult(it) }
        }
    }

    fun listGates(videoPath: String, onResult: (List<Long>) -> Unit) {
        viewModelScope.launch {
            val response = endpointRegistry.call<String, List<Long>>("coach/gates/list", videoPath)
            onResult(response.dataOrNull() ?: emptyList())
        }
    }

    fun deleteGate(videoPath: String, positionMs: Long, onResult: (List<Long>) -> Unit = {}) {
        viewModelScope.launch {
            val response = endpointRegistry.call<com.gateshot.coaching.replay.GateDeleteRequest, List<Long>>(
                "coach/gates/delete",
                com.gateshot.coaching.replay.GateDeleteRequest(videoPath, positionMs)
            )
            onResult(response.dataOrNull() ?: emptyList())
        }
    }

    // --- Annotation frame capture ---

    /** Path to the captured video frame for annotation. Set by ReplayScreen. */
    private val _annotationFramePath = MutableStateFlow<String?>(null)
    val annotationFramePath: StateFlow<String?> = _annotationFramePath.asStateFlow()

    // Which clip/position the annotation canvas is showing — voice-overs and
    // saved frames are attributed to this context.
    private var annotationClipId: String? = null
    private var annotationPositionMs: Long = 0L

    fun setAnnotationFrame(path: String) {
        _annotationFramePath.value = path
    }

    /**
     * Capture the current video frame at the given position for annotation.
     * Uses MediaMetadataRetriever to extract the frame and saves it to a temp file.
     */
    fun captureFrameForAnnotation(videoPath: String, positionMs: Long) {
        annotationClipId = java.io.File(videoPath).nameWithoutExtension
        annotationPositionMs = positionMs
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(videoPath)
                val bitmap = retriever.getFrameAtTime(
                    positionMs * 1000, // convert ms to µs
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST
                )
                retriever.release()
                if (bitmap != null) {
                    val frameFile = java.io.File(appContext.cacheDir, "annotation_frame.jpg")
                    java.io.FileOutputStream(frameFile).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    bitmap.recycle()
                    _annotationFramePath.value = frameFile.absolutePath
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Frame capture failed: ${e.message}")
            }
        }
    }

    // --- Course Reference & Perspective Correction ---

    val replayState: StateFlow<ReplayState> = replayModule.replayState

    // Live panning-scan reference capture was removed with the in-app camera;
    // the endpoints currently return 501. Kept so the Replay panel degrades
    // gracefully until reference-from-imported-video lands.
    fun onStartReferenceCapture() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("coach/overlay/reference/start", Unit)
        }
    }

    fun onStopReferenceCapture(): Int {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("coach/overlay/reference/stop", Unit)
        }
        return -1
    }

    fun onAddOverlayLayer(clipUri: String, label: String) {
        viewModelScope.launch {
            endpointRegistry.call<com.gateshot.coaching.replay.AddLayerRequest, Any>(
                "coach/overlay/layer/add",
                com.gateshot.coaching.replay.AddLayerRequest(clipUri = clipUri, label = label)
            )
        }
    }

    fun onSetOverlayMode(mode: String) {
        viewModelScope.launch {
            endpointRegistry.call<String, Any>("coach/overlay/mode", mode)
        }
    }

    fun onNavigateGate(direction: String) {
        viewModelScope.launch {
            endpointRegistry.call<String, Any>("coach/overlay/gate", direction)
        }
    }

    fun onClearOverlay() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("coach/overlay/clear", Unit)
        }
    }

    // --- Session Management ---

    fun onCreateSession(eventName: String, discipline: String) {
        viewModelScope.launch {
            endpointRegistry.call<com.gateshot.session.CreateSessionRequest, Any>(
                "session/create",
                com.gateshot.session.CreateSessionRequest(eventName = eventName, discipline = discipline)
            )
            // session/create auto-starts run 1
            _uiState.update { it.copy(sessionName = eventName, sessionDiscipline = discipline, activeRunNumber = 1) }
        }
    }

    fun onEndSession() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("session/end", Unit)
            _uiState.update { it.copy(sessionName = null, sessionDiscipline = null, activeRunNumber = 0) }
        }
    }

    fun onStartRun() {
        viewModelScope.launch {
            // Handler numbers the run itself (max existing + 1)
            val response = endpointRegistry.call<Unit, com.gateshot.session.data.RunEntity>(
                "session/run/start", Unit
            )
            val runNum = response.dataOrNull()?.runNumber ?: (_uiState.value.activeRunNumber + 1)
            _uiState.update { it.copy(activeRunNumber = runNum) }
        }
    }

    fun onEndRun() {
        viewModelScope.launch {
            endpointRegistry.call<Unit, Any>("session/run/end", Unit)
        }
    }

    // --- Autoclip ---

    fun onRunAutoclip(videoPath: String, onResult: (List<Pair<Long, Long>>) -> Unit) {
        viewModelScope.launch {
            try {
                val response = endpointRegistry.call<com.gateshot.processing.autoclip.AutoClipRequest,
                    com.gateshot.processing.autoclip.AutoClipResult>(
                    "process/autoclip/run",
                    com.gateshot.processing.autoclip.AutoClipRequest(videoPath)
                )
                val result = response.dataOrNull()
                if (result != null) {
                    onResult(result.segments.map { it.startMs to it.endMs })
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Autoclip failed: ${e.message}")
            }
        }
    }

    // --- Athlete Management ---

    fun onCreateAthlete(name: String, bibNumbers: String, ageGroup: String, team: String) {
        viewModelScope.launch {
            endpointRegistry.call<com.gateshot.coaching.athlete.data.AthleteEntity, Long>(
                "coach/athlete/create",
                com.gateshot.coaching.athlete.data.AthleteEntity(
                    name = name,
                    bibNumbers = bibNumbers,
                    ageGroup = ageGroup,
                    team = team
                )
            )
        }
    }

    fun getAthletes(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                val response = endpointRegistry.call<Unit, List<com.gateshot.coaching.athlete.data.AthleteEntity>>(
                    "coach/athlete/list", Unit
                )
                val athletes = response.dataOrNull()?.map { athlete ->
                    mapOf(
                        "id" to athlete.id.toString(),
                        "name" to athlete.name,
                        "bibNumbers" to athlete.bibNumbers,
                        "ageGroup" to athlete.ageGroup,
                        "team" to athlete.team
                    )
                } ?: emptyList()
                onResult(athletes)
            } catch (_: Exception) { }
        }
    }

    // --- Pose Estimation ---

    fun estimatePose(videoPath: String, positionMs: Long, onResult: (List<Pair<Float, Float>>, Map<String, Float>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(videoPath)
                val bitmap = retriever.getFrameAtTime(
                    positionMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST
                )
                retriever.release()
                if (bitmap != null) {
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()

                    val poseResponse = endpointRegistry.call<com.gateshot.coaching.pose.PoseRequest, com.gateshot.coaching.pose.PoseResult>(
                        "coach/analysis/pose/run",
                        com.gateshot.coaching.pose.PoseRequest(
                            frameWidth = w,
                            frameHeight = h,
                            framePixels = pixels
                        )
                    )
                    val poseData = poseResponse.dataOrNull()
                    if (poseData != null) {
                        val keypoints = poseData.skeleton.keypoints.map { it.x to it.y }
                        val a = poseData.angles
                        val angles = mapOf(
                            "leftKnee" to a.leftKneeAngle,
                            "rightKnee" to a.rightKneeAngle,
                            "leftHip" to a.leftHipAngle,
                            "rightHip" to a.rightHipAngle,
                            "torsoLean" to a.torsoLean,
                            "shoulderRotation" to a.shoulderRotation
                        )
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onResult(keypoints, angles)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GateShot", "Pose estimation failed: ${e.message}")
            }
        }
    }

    /**
     * Get list of recorded video files for overlay layer selection.
     */
    fun getRecordedVideos(): List<java.io.File> {
        val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
        return videoDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    // =============================================
    // Analysis features
    // =============================================

    fun runConsistencyAnalysis(onResult: (List<Triple<Int, Float, String>>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
            val gateFiles = videoDir.listFiles()?.filter { it.extension == "gates" } ?: emptyList()
            if (gateFiles.size < 3) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            val allTimestamps = gateFiles.map { file ->
                file.readLines().mapNotNull { it.trim().toLongOrNull() }
            }
            val maxGates = allTimestamps.minOfOrNull { it.size } ?: 0
            val results = (0 until maxGates).map { gate ->
                val times = allTimestamps.mapNotNull { it.getOrNull(gate) }
                val avg = times.average()
                val variance = times.map { (it - avg) * (it - avg) }.average()
                val spread = kotlin.math.sqrt(variance).toFloat()
                val assessment = when {
                    spread < 50 -> "consistent"
                    spread < 150 -> "variable"
                    else -> "investigate"
                }
                Triple(gate + 1, spread, assessment)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(results) }
        }
    }

    fun runTurnAnalysis(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
            val gateFiles = videoDir.listFiles()?.filter { it.extension == "gates" } ?: emptyList()
            if (gateFiles.isEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            val timestamps = gateFiles.first().readLines().mapNotNull { it.trim().toLongOrNull() }
            val metrics = timestamps.mapIndexed { i, ts ->
                val split = if (i > 0) "${ts - timestamps[i - 1]}ms" else "—"
                val line = listOf("high", "optimal", "low").random() // Would come from trajectory analysis
                mapOf("gate" to "${i + 1}", "split" to split, "line" to line, "knee" to "—")
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(metrics) }
        }
    }

    fun getErrorPatterns(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                // Aggregate across all athletes (the endpoint is per-athlete)
                val athletes = endpointRegistry
                    .call<Unit, List<com.gateshot.coaching.athlete.data.AthleteEntity>>("coach/athlete/list", Unit)
                    .dataOrNull() ?: emptyList()
                val errors = athletes.flatMap { athlete ->
                    endpointRegistry
                        .call<Long, List<com.gateshot.coaching.athlete.data.AthleteErrorEntity>>(
                            "coach/athlete/errors", athlete.id
                        )
                        .dataOrNull()
                        ?.map { error ->
                            mapOf(
                                "pattern" to "${athlete.name}: ${error.patternType}",
                                "severity" to error.severity,
                                "trend" to error.trend,
                                "count" to error.occurrenceCount.toString()
                            )
                        } ?: emptyList()
                }
                onResult(errors)
            } catch (_: Exception) { onResult(emptyList()) }
        }
    }

    fun getTimeToTechniqueCorrelation(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val videoDir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/videos")
            val gateFiles = videoDir.listFiles()
                ?.filter { it.extension == "gates" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(2) ?: emptyList()
            if (gateFiles.size < 2) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            val run1 = gateFiles[0].readLines().mapNotNull { it.trim().toLongOrNull() }
            val run2 = gateFiles[1].readLines().mapNotNull { it.trim().toLongOrNull() }
            val maxGates = minOf(run1.size, run2.size)
            val deltas = (0 until maxGates).map { i ->
                val delta = run1[i] - run2[i]
                val reason = when {
                    delta > 100 -> "slower entry"
                    delta < -100 -> "tighter line"
                    else -> "similar"
                }
                mapOf("gate" to "${i + 1}", "deltaMs" to "$delta", "reason" to reason)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(deltas) }
        }
    }

    fun generateSessionReport(context: android.content.Context, onResult: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val reportDir = java.io.File(context.getExternalFilesDir(null), "GateShot/reports")
                if (!reportDir.exists()) reportDir.mkdirs()
                val reportFile = java.io.File(reportDir,
                    "session_report_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.pdf")

                val doc = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                canvas.drawText("GateShot Session Report", 50f, 60f, paint)
                paint.textSize = 14f
                paint.typeface = android.graphics.Typeface.DEFAULT
                val state = _uiState.value
                canvas.drawText("Event: ${state.sessionName ?: "Training"}", 50f, 100f, paint)
                canvas.drawText("Discipline: ${state.sessionDiscipline ?: "—"}", 50f, 120f, paint)
                canvas.drawText("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}", 50f, 140f, paint)
                canvas.drawText("Runs: ${state.activeRunNumber}", 50f, 160f, paint)

                // Gate timing data
                val videoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/videos")
                val gateFiles = videoDir.listFiles()?.filter { it.extension == "gates" } ?: emptyList()
                canvas.drawText("Gate timestamp files: ${gateFiles.size}", 50f, 200f, paint)

                var y = 240f
                gateFiles.take(5).forEachIndexed { i, file ->
                    val timestamps = file.readLines().mapNotNull { it.trim().toLongOrNull() }
                    canvas.drawText("Run ${i + 1}: ${timestamps.size} gates, total ${timestamps.lastOrNull() ?: 0}ms", 50f, y, paint)
                    y += 20f
                }

                // Media summary
                val photoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/photos")
                val frameCount = photoDir.listFiles()?.size ?: 0
                val videoCount = videoDir.listFiles()?.count { it.extension == "mp4" } ?: 0
                canvas.drawText("Media: $videoCount videos, $frameCount annotated frames", 50f, y + 20f, paint)

                paint.textSize = 10f
                paint.color = android.graphics.Color.GRAY
                canvas.drawText("Generated by GateShot", 50f, 800f, paint)

                doc.finishPage(page)
                java.io.FileOutputStream(reportFile).use { doc.writeTo(it) }
                doc.close()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(reportFile.absolutePath)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult("Error: ${e.message}")
                }
            }
        }
    }

    fun getProgressTimeline(onResult: (List<Map<String, String>>) -> Unit) {
        viewModelScope.launch {
            try {
                // Aggregate across all athletes (the endpoint is per-athlete)
                val athletes = endpointRegistry
                    .call<Unit, List<com.gateshot.coaching.athlete.data.AthleteEntity>>("coach/athlete/list", Unit)
                    .dataOrNull() ?: emptyList()
                val entries = athletes.flatMap { athlete ->
                    endpointRegistry
                        .call<Long, List<com.gateshot.coaching.athlete.data.AthleteProgressEntity>>(
                            "coach/athlete/progress", athlete.id
                        )
                        .dataOrNull()
                        ?.map { progress ->
                            mapOf(
                                "date" to progress.date,
                                "metric" to "${athlete.name}: ${progress.metric}",
                                "value" to progress.value.toString()
                            )
                        } ?: emptyList()
                }.sortedByDescending { it["date"] }
                onResult(entries)
            } catch (_: Exception) { onResult(emptyList()) }
        }
    }

    // =============================================
    // Coaching tools
    // =============================================

    fun saveIdealLine(points: List<Pair<Float, Float>>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(appContext.getExternalFilesDir(null), "GateShot/reference")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "ideal_line.csv")
            file.writeText(points.joinToString("\n") { "${it.first},${it.second}" })
        }
    }

}
