package com.gateshot.coaching.annotation

import android.content.Context
import android.media.MediaRecorder
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationFeatureModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "annotation"
    override val version = "0.1.0"
    override val requiredMode = AppMode.COACH

    // Voice-over storage: clipId -> list of voice annotations
    private val voiceAnnotations = mutableMapOf<String, MutableList<VoiceAnnotation>>()
    // Drawing storage: clipId -> list of drawing layers
    private val drawingAnnotations = mutableMapOf<String, MutableList<DrawingAnnotation>>()

    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingVoice = false
    private var currentVoiceClipId: String? = null
    private var voiceStartPositionMs: Long = 0
    private var voiceFile: File? = null

    override suspend fun initialize() {}
    override suspend fun shutdown() {
        stopVoiceRecording()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        StartVoiceOver(),
        StopVoiceOver(),
        ListVoiceOvers(),
        DeleteVoiceOver(),
        SaveDrawing(),
        ListDrawings(),
        DeleteDrawing(),
        SaveAnnotatedFrame()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    private fun stopVoiceRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
        isRecordingVoice = false
    }

    // --- coach/annotate/voiceover/start ---
    inner class StartVoiceOver : ApiEndpoint<VoiceOverStartRequest, Boolean> {
        override val path = "coach/annotate/voiceover/start"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: VoiceOverStartRequest): ApiResponse<Boolean> {
            if (isRecordingVoice) return ApiResponse.error(409, "Already recording")

            val file = File(context.cacheDir, "voiceover_${System.currentTimeMillis()}.m4a")
            voiceFile = file
            currentVoiceClipId = request.clipId
            voiceStartPositionMs = request.videoPositionMs

            try {
                mediaRecorder = MediaRecorder(context).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                isRecordingVoice = true
                return ApiResponse.success(true)
            } catch (e: Exception) {
                return ApiResponse.moduleError(module, e.message ?: "Failed to start recording")
            }
        }
    }

    // --- coach/annotate/voiceover/stop ---
    inner class StopVoiceOver : ApiEndpoint<Unit, VoiceAnnotation> {
        override val path = "coach/annotate/voiceover/stop"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<VoiceAnnotation> {
            if (!isRecordingVoice) return ApiResponse.error(409, "Not recording")

            stopVoiceRecording()

            val annotation = VoiceAnnotation(
                id = System.currentTimeMillis().toString(),
                clipId = currentVoiceClipId ?: "",
                audioFileUri = voiceFile?.absolutePath ?: "",
                startPositionMs = voiceStartPositionMs,
                durationMs = 0,  // Would be calculated from file
                timestamp = System.currentTimeMillis()
            )
            voiceAnnotations.getOrPut(annotation.clipId) { mutableListOf() }.add(annotation)
            eventBus.publish(AppEvent.AnnotationAdded(annotation.clipId, "voiceover"))

            return ApiResponse.success(annotation)
        }
    }

    // --- coach/annotate/voiceover/list ---
    inner class ListVoiceOvers : ApiEndpoint<String, List<VoiceAnnotation>> {
        override val path = "coach/annotate/voiceover/list"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<List<VoiceAnnotation>> {
            return ApiResponse.success(voiceAnnotations[request] ?: emptyList())
        }
    }

    // --- coach/annotate/voiceover/delete ---
    inner class DeleteVoiceOver : ApiEndpoint<DeleteAnnotationRequest, Boolean> {
        override val path = "coach/annotate/voiceover/delete"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: DeleteAnnotationRequest): ApiResponse<Boolean> {
            voiceAnnotations[request.clipId]?.removeAll { it.id == request.annotationId }
            return ApiResponse.success(true)
        }
    }

    // --- coach/annotate/draw/save ---
    inner class SaveDrawing : ApiEndpoint<DrawingAnnotation, Boolean> {
        override val path = "coach/annotate/draw/save"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: DrawingAnnotation): ApiResponse<Boolean> {
            drawingAnnotations.getOrPut(request.clipId) { mutableListOf() }.add(request)
            eventBus.publish(AppEvent.AnnotationAdded(request.clipId, "drawing"))
            return ApiResponse.success(true)
        }
    }

    // --- coach/annotate/draw/list ---
    inner class ListDrawings : ApiEndpoint<String, List<DrawingAnnotation>> {
        override val path = "coach/annotate/draw/list"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: String): ApiResponse<List<DrawingAnnotation>> {
            return ApiResponse.success(drawingAnnotations[request] ?: emptyList())
        }
    }

    // --- coach/annotate/draw/delete ---
    inner class DeleteDrawing : ApiEndpoint<DeleteAnnotationRequest, Boolean> {
        override val path = "coach/annotate/draw/delete"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: DeleteAnnotationRequest): ApiResponse<Boolean> {
            drawingAnnotations[request.clipId]?.removeAll { it.id == request.annotationId }
            return ApiResponse.success(true)
        }
    }

    // --- coach/annotate/frame/save ---
    inner class SaveAnnotatedFrame : ApiEndpoint<SaveFrameRequest, String> {
        override val path = "coach/annotate/frame/save"
        override val module = "annotation"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: SaveFrameRequest): ApiResponse<String> {
            // Save the annotated frame as a PNG — actual bitmap composition done by UI
            val outputFile = File(context.cacheDir, "annotated_${System.currentTimeMillis()}.png")
            // In real implementation, the UI would pass bitmap data
            return ApiResponse.success(outputFile.absolutePath)
        }
    }
}

@Serializable
data class VoiceAnnotation(
    val id: String,
    val clipId: String,
    val audioFileUri: String,
    val startPositionMs: Long,
    val durationMs: Long,
    val timestamp: Long
)

@Serializable
data class DrawingAnnotation(
    val id: String = System.currentTimeMillis().toString(),
    val clipId: String,
    val framePositionMs: Long,
    val elements: List<DrawingElement>,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class DrawingElement(
    val type: DrawingType,
    val points: List<PointF>,       // Normalized 0.0-1.0
    val color: String = "#FF0000",  // Hex color
    val strokeWidth: Float = 4f,
    val text: String? = null        // For labels
)

@Serializable
enum class DrawingType {
    FREEHAND, LINE, ARROW, CIRCLE, ANGLE_ARC, TEXT
}

@Serializable
data class PointF(val x: Float, val y: Float)

data class VoiceOverStartRequest(val clipId: String, val videoPositionMs: Long)
data class DeleteAnnotationRequest(val clipId: String, val annotationId: String)
data class SaveFrameRequest(val clipId: String, val framePositionMs: Long)
