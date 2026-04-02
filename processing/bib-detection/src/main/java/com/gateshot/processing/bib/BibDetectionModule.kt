package com.gateshot.processing.bib

import android.content.Context
import android.graphics.Bitmap
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bib Number Detection Module — Extracts racer bib numbers from frames using ML Kit OCR.
 *
 * Ski racing bibs have large, high-contrast numbers (typically black or dark blue
 * on white/yellow background, 10-20cm tall). This makes them ideal for OCR even
 * at telephoto distances.
 *
 * DETECTION STRATEGY:
 * 1. Locate high-contrast rectangular regions in the chest area of the frame
 * 2. Crop the candidate regions
 * 3. Run ML Kit Text Recognition on each candidate
 * 4. Filter results for 1-3 digit numbers (bib numbers range from 1-999)
 * 5. Select the highest-confidence numeric detection
 *
 * The module runs on every 15th frame (~2/sec at 30fps) to minimize CPU usage.
 * When a bib is detected, it publishes a BibDetected event that the Athlete
 * module uses to auto-tag the racer.
 */
@Singleton
class BibDetectionModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "bib_detection"
    override val version = "0.2.0"
    override val requiredMode: AppMode? = null
    override val dependencies = listOf("camera")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastDetectedBib: Int? = null
    private var lastDetectionConfidence: Float = 0f
    private var lastBoundingBox: BoundingBox? = null
    private var frameCounter = 0
    private var totalDetections = 0

    // Analyze every 15th frame (~2/sec at 30fps) — bib detection is expensive
    private val analyzeEveryNFrames = 15

    // Bib number validation: race bibs are 1-3 digit numbers
    private val bibNumberPattern = Regex("^\\d{1,3}$")

    // Confidence threshold for accepting a detection
    private val minOcrConfidence = 0.6f

    // Temporal smoothing: require same number detected N times before accepting
    private var candidateBib: Int? = null
    private var candidateCount = 0
    private val requiredConsecutiveDetections = 2

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
            // Burst bib detection handled via batch endpoint
        }
    }

    override suspend fun shutdown() {
        cameraPlatform.removeFrameListener(frameListener)
        textRecognizer.close()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        DetectBib(),
        BatchDetect(),
        GetLastDetection()
    )

    override fun healthCheck(): ModuleHealth {
        val msg = if (lastDetectedBib != null)
            "Last: bib #$lastDetectedBib (${(lastDetectionConfidence * 100).toInt()}%), total=$totalDetections"
        else "Scanning..."
        return ModuleHealth(name, ModuleHealth.Status.OK, msg)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live Frame Analysis
    // ─────────────────────────────────────────────────────────────────────────

    private fun analyzeForBib(imageProxy: ImageProxy) {
        try {
            val yPlane = imageProxy.planes[0]
            val buffer = yPlane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = yPlane.rowStride

            // Step 1: Find high-contrast candidate regions in the chest area
            val candidates = findBibCandidateRegions(buffer, width, height, rowStride)

            if (candidates.isEmpty()) return

            // Step 2: For each candidate, create a bitmap crop and run OCR
            // For live analysis, we use the Y-plane as a grayscale image
            val grayBitmap = createGrayscaleBitmap(buffer, width, height, rowStride)

            for (candidate in candidates) {
                val cropped = Bitmap.createBitmap(
                    grayBitmap,
                    candidate.left.coerceAtLeast(0),
                    candidate.top.coerceAtLeast(0),
                    (candidate.right - candidate.left).coerceAtMost(grayBitmap.width - candidate.left),
                    (candidate.bottom - candidate.top).coerceAtMost(grayBitmap.height - candidate.top)
                )

                runOcrOnBitmap(cropped, candidate)
                cropped.recycle()
            }

            grayBitmap.recycle()
        } catch (_: Exception) { }
    }

    /**
     * Find rectangular regions with high contrast that could contain bib numbers.
     * Scans the center-upper region of the frame (where bibs appear on a skier).
     */
    private fun findBibCandidateRegions(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): List<Rect> {
        val searchTop = height / 4
        val searchBottom = height * 3 / 4
        val searchLeft = width / 4
        val searchRight = width * 3 / 4

        val candidates = mutableListOf<Rect>()
        val blockSize = 32

        // Find clusters of high-contrast blocks
        val highContrastBlocks = mutableListOf<Pair<Int, Int>>()

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
                if ((max - min) > 150) {
                    highContrastBlocks.add(Pair(x, y))
                }
            }
        }

        // Cluster adjacent high-contrast blocks into candidate regions
        if (highContrastBlocks.size >= 3) {
            val minX = highContrastBlocks.minOf { it.first }
            val maxX = highContrastBlocks.maxOf { it.first } + blockSize
            val minY = highContrastBlocks.minOf { it.second }
            val maxY = highContrastBlocks.maxOf { it.second } + blockSize

            // Add some padding around the detected region
            val padding = blockSize
            candidates.add(Rect(
                (minX - padding).coerceAtLeast(0),
                (minY - padding).coerceAtLeast(0),
                (maxX + padding).coerceAtMost(width),
                (maxY + padding).coerceAtMost(height)
            ))
        }

        return candidates
    }

    private fun createGrayscaleBitmap(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * rowStride + x
                val lum = if (idx < buffer.limit()) buffer.get(idx).toInt() and 0xFF else 0
                pixels[y * width + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun runOcrOnBitmap(bitmap: Bitmap, sourceRegion: Rect) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        // Filter for bib numbers: 1-3 digits only
                        if (bibNumberPattern.matches(text)) {
                            val bibNumber = text.toIntOrNull() ?: continue
                            if (bibNumber < 1 || bibNumber > 999) continue

                            val lineBox = line.boundingBox
                            val confidence = (line.confidence ?: 0f)

                            if (confidence >= minOcrConfidence) {
                                onBibDetected(bibNumber, confidence, sourceRegion, lineBox)
                            }
                        }
                    }
                }
            }
    }

    /**
     * Handle a potential bib detection. Uses temporal smoothing to prevent
     * false positives — the same number must be detected at least N times
     * consecutively before we accept it.
     */
    private fun onBibDetected(
        bibNumber: Int,
        confidence: Float,
        sourceRegion: Rect,
        textBox: android.graphics.Rect?
    ) {
        if (bibNumber == candidateBib) {
            candidateCount++
        } else {
            candidateBib = bibNumber
            candidateCount = 1
        }

        if (candidateCount >= requiredConsecutiveDetections) {
            lastDetectedBib = bibNumber
            lastDetectionConfidence = confidence
            lastBoundingBox = BoundingBox(
                sourceRegion.left, sourceRegion.top,
                sourceRegion.right, sourceRegion.bottom
            )
            totalDetections++

            // Publish event for athlete auto-tagging
            eventBus.tryPublish(AppEvent.BibDetected(bibNumber, confidence))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static Image Analysis
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detect bib number from a static image file using ML Kit OCR.
     */
    private suspend fun detectBibFromFile(filePath: String): BibDetectionResult {
        val bitmap = BitmapFactory.decodeFile(filePath)
            ?: return BibDetectionResult(null, 0f, null, filePath)

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return try {
            val visionText = textRecognizer.process(inputImage).await()

            var bestBib: Int? = null
            var bestConfidence = 0f
            var bestBox: BoundingBox? = null

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val text = line.text.trim()
                    if (bibNumberPattern.matches(text)) {
                        val bibNumber = text.toIntOrNull() ?: continue
                        if (bibNumber < 1 || bibNumber > 999) continue

                        val confidence = line.confidence ?: 0f
                        if (confidence > bestConfidence) {
                            bestBib = bibNumber
                            bestConfidence = confidence
                            val box = line.boundingBox
                            bestBox = if (box != null) {
                                BoundingBox(box.left, box.top, box.right, box.bottom)
                            } else null
                        }
                    }
                }
            }

            bitmap.recycle()
            BibDetectionResult(bestBib, bestConfidence, bestBox, filePath)
        } catch (e: Exception) {
            bitmap.recycle()
            BibDetectionResult(null, 0f, null, filePath)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Endpoints
    // ─────────────────────────────────────────────────────────────────────────

    // --- process/bib/detect ---
    inner class DetectBib : ApiEndpoint<String, BibDetectionResult> {
        override val path = "process/bib/detect"
        override val module = "bib_detection"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: String): ApiResponse<BibDetectionResult> {
            val result = detectBibFromFile(request)
            return ApiResponse.success(result)
        }
    }

    // --- process/bib/detect/batch ---
    inner class BatchDetect : ApiEndpoint<List<String>, List<BibDetectionResult>> {
        override val path = "process/bib/detect/batch"
        override val module = "bib_detection"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: List<String>): ApiResponse<List<BibDetectionResult>> {
            val results = request.map { path -> detectBibFromFile(path) }
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
                    BibDetectionResult(it, lastDetectionConfidence, lastBoundingBox, "live")
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
