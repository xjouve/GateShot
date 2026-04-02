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
            val outputFile = File(context.cacheDir, "annotated_${System.currentTimeMillis()}.png")

            // Compose the annotated frame: base frame + drawing overlays
            // The UI provides the base frame as pixel data and the drawing
            // elements are stored in our annotation registry.
            val basePixels = request.framePixels
            val width = request.frameWidth
            val height = request.frameHeight

            if (basePixels == null || width <= 0 || height <= 0) {
                return ApiResponse.error(400, "Frame pixel data required")
            }

            // Create bitmap from the frame pixels
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.setPixels(basePixels, 0, width, 0, 0, width, height)
            val canvas = android.graphics.Canvas(bitmap)

            // Render drawing annotations for this clip/frame on top
            val drawings = drawingAnnotations[request.clipId]
                ?.filter { it.framePositionMs == request.framePositionMs }
                ?: emptyList()

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            for (drawing in drawings) {
                for (element in drawing.elements) {
                    paint.color = android.graphics.Color.parseColor(element.color)
                    paint.strokeWidth = element.strokeWidth

                    when (element.type) {
                        DrawingType.FREEHAND, DrawingType.LINE -> {
                            if (element.points.size >= 2) {
                                val path = android.graphics.Path()
                                val first = element.points[0]
                                path.moveTo(first.x * width, first.y * height)
                                for (i in 1 until element.points.size) {
                                    val pt = element.points[i]
                                    path.lineTo(pt.x * width, pt.y * height)
                                }
                                canvas.drawPath(path, paint)
                            }
                        }
                        DrawingType.CIRCLE -> {
                            if (element.points.size >= 2) {
                                val center = element.points[0]
                                val edge = element.points[1]
                                val dx = (edge.x - center.x) * width
                                val dy = (edge.y - center.y) * height
                                val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                                canvas.drawCircle(center.x * width, center.y * height, radius, paint)
                            }
                        }
                        DrawingType.ARROW -> {
                            if (element.points.size >= 2) {
                                val start = element.points[0]
                                val end = element.points[1]
                                val sx = start.x * width; val sy = start.y * height
                                val ex = end.x * width; val ey = end.y * height
                                canvas.drawLine(sx, sy, ex, ey, paint)
                                // Arrowhead
                                val angle = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                                val headLen = 20f
                                canvas.drawLine(ex, ey,
                                    ex - headLen * kotlin.math.cos(angle - 0.4).toFloat(),
                                    ey - headLen * kotlin.math.sin(angle - 0.4).toFloat(), paint)
                                canvas.drawLine(ex, ey,
                                    ex - headLen * kotlin.math.cos(angle + 0.4).toFloat(),
                                    ey - headLen * kotlin.math.sin(angle + 0.4).toFloat(), paint)
                            }
                        }
                        DrawingType.TEXT -> {
                            if (element.points.isNotEmpty() && element.text != null) {
                                paint.style = android.graphics.Paint.Style.FILL
                                paint.textSize = element.strokeWidth * 6
                                val pt = element.points[0]
                                canvas.drawText(element.text, pt.x * width, pt.y * height, paint)
                                paint.style = android.graphics.Paint.Style.STROKE
                            }
                        }
                        DrawingType.ANGLE_ARC -> {
                            if (element.points.size >= 3) {
                                val center = element.points[1]
                                val start = element.points[0]
                                val end = element.points[2]
                                val radius = 40f
                                val startAngle = Math.toDegrees(
                                    kotlin.math.atan2(
                                        (start.y - center.y).toDouble() * height,
                                        (start.x - center.x).toDouble() * width
                                    )
                                ).toFloat()
                                val endAngle = Math.toDegrees(
                                    kotlin.math.atan2(
                                        (end.y - center.y).toDouble() * height,
                                        (end.x - center.x).toDouble() * width
                                    )
                                ).toFloat()
                                val sweepAngle = ((endAngle - startAngle + 360) % 360)
                                val cx = center.x * width
                                val cy = center.y * height
                                val oval = android.graphics.RectF(
                                    cx - radius, cy - radius, cx + radius, cy + radius
                                )
                                canvas.drawArc(oval, startAngle, sweepAngle, false, paint)

                                // Draw the angle value as text
                                if (element.text != null) {
                                    paint.style = android.graphics.Paint.Style.FILL
                                    paint.textSize = 28f
                                    canvas.drawText(element.text, cx + radius + 5, cy, paint)
                                    paint.style = android.graphics.Paint.Style.STROKE
                                }
                            }
                        }
                    }
                }
            }

            // Save as PNG
            java.io.FileOutputStream(outputFile).use { fos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
            bitmap.recycle()

            // Publish event
            eventBus.tryPublish(AppEvent.AnnotationAdded(request.clipId, "annotated_frame"))

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
data class SaveFrameRequest(
    val clipId: String,
    val framePositionMs: Long,
    val framePixels: IntArray? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = framePixels?.contentHashCode() ?: 0
}
