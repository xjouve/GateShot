package com.gateshot.processing.bib

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.platform.camera.CameraXPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BibDetectionModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "bib_detection"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null
    override val dependencies = listOf("camera")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Simple digit detection via high-contrast region analysis
    // In production, this would use ML Kit or TFLite text recognition
    private var lastDetectedBib: Int? = null
    private var lastDetectionConfidence: Float = 0f
    private var frameCounter = 0

    // Analyze every 15th frame (~2/sec at 30fps) — bib detection is expensive
    private val analyzeEveryNFrames = 15

    private val frameListener: (ImageProxy) -> Unit = { imageProxy ->
        frameCounter++
        if (frameCounter % analyzeEveryNFrames == 0) {
            analyzeForBib(imageProxy)
        }
    }

    override suspend fun initialize() {
        cameraPlatform.addFrameListener(frameListener)

        // Also detect bibs in completed burst frames
        eventBus.collect<AppEvent.BurstCompleted>(scope) { event ->
            // Batch detection on burst frames would run here
        }
    }

    override suspend fun shutdown() {
        cameraPlatform.removeFrameListener(frameListener)
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        DetectBib(),
        BatchDetect(),
        GetLastDetection()
    )

    override fun healthCheck(): ModuleHealth {
        val msg = if (lastDetectedBib != null)
            "Last: bib #$lastDetectedBib (${(lastDetectionConfidence * 100).toInt()}%)"
        else "Scanning..."
        return ModuleHealth(name, ModuleHealth.Status.OK, msg)
    }

    private fun analyzeForBib(imageProxy: ImageProxy) {
        try {
            // Look for high-contrast rectangular regions in the chest area of the frame
            // (top-center region where bibs typically appear)
            val yPlane = imageProxy.planes[0]
            val buffer = yPlane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = yPlane.rowStride

            // Search the center-upper region (where a bib would be on a standing/skiing person)
            val searchTop = height / 4
            val searchBottom = height * 3 / 4
            val searchLeft = width / 4
            val searchRight = width * 3 / 4

            // Find high-contrast blocks that could be bib numbers
            var maxContrast = 0f
            var contrastRegionCount = 0

            val blockSize = 32
            for (y in searchTop until searchBottom step blockSize) {
                for (x in searchLeft until searchRight step blockSize) {
                    var min = 255
                    var max = 0
                    for (dy in 0 until blockSize step 4) {
                        for (dx in 0 until blockSize step 4) {
                            val px = x + dx
                            val py = y + dy
                            if (px < width && py < height) {
                                val idx = py * rowStride + px
                                if (idx < buffer.limit()) {
                                    val lum = buffer.get(idx).toInt() and 0xFF
                                    if (lum < min) min = lum
                                    if (lum > max) max = lum
                                }
                            }
                        }
                    }
                    val contrast = (max - min).toFloat()
                    if (contrast > 150) {  // Very high contrast = likely text on bib
                        contrastRegionCount++
                        if (contrast > maxContrast) maxContrast = contrast
                    }
                }
            }

            // If we found high-contrast text-like regions, this is a potential bib
            // In production, ML Kit TextRecognition would extract the actual number
            if (contrastRegionCount >= 3 && maxContrast > 180) {
                // Placeholder: would use ML Kit to get actual number
                // For now, signal that a bib region was detected
                lastDetectionConfidence = (maxContrast / 255f).coerceIn(0f, 1f)
            }
        } catch (_: Exception) { }
    }

    // --- process/bib/detect ---
    inner class DetectBib : ApiEndpoint<String, BibDetectionResult> {
        override val path = "process/bib/detect"
        override val module = "bib_detection"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: String): ApiResponse<BibDetectionResult> {
            // Analyze a specific image file for bib numbers
            val bitmap = BitmapFactory.decodeFile(request)
                ?: return ApiResponse.error(404, "Image not found: $request")

            // Placeholder result — ML Kit would provide actual OCR
            val result = BibDetectionResult(
                bibNumber = null,
                confidence = 0f,
                boundingBox = null,
                sourceFile = request
            )
            bitmap.recycle()
            return ApiResponse.success(result)
        }
    }

    // --- process/bib/detect/batch ---
    inner class BatchDetect : ApiEndpoint<List<String>, List<BibDetectionResult>> {
        override val path = "process/bib/detect/batch"
        override val module = "bib_detection"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: List<String>): ApiResponse<List<BibDetectionResult>> {
            val results = request.map { path ->
                BibDetectionResult(
                    bibNumber = null,
                    confidence = 0f,
                    boundingBox = null,
                    sourceFile = path
                )
            }
            return ApiResponse.success(results)
        }
    }

    // --- process/bib/last ---
    inner class GetLastDetection : ApiEndpoint<Unit, BibDetectionResult?> {
        override val path = "process/bib/last"
        override val module = "bib_detection"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<BibDetectionResult?> {
            return ApiResponse.success(
                lastDetectedBib?.let {
                    BibDetectionResult(it, lastDetectionConfidence, null, "live")
                }
            )
        }
    }
}

data class BibDetectionResult(
    val bibNumber: Int?,
    val confidence: Float,
    val boundingBox: BoundingBox?,
    val sourceFile: String
)

data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
