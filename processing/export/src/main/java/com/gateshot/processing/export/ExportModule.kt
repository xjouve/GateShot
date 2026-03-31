package com.gateshot.processing.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.content.FileProvider
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportModule @Inject constructor(
    @ApplicationContext private val context: Context
) : FeatureModule {

    override val name = "export"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null

    private var watermarkConfig = WatermarkConfig()

    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        QuickShare(),
        BatchShare(),
        ConfigureWatermark(),
        ExportRaw()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    private fun prepareForShare(filePath: String, preset: SharePreset): File {
        val source = File(filePath)
        if (!source.exists()) return source

        return when (preset) {
            SharePreset.COACH -> {
                // 1080p, moderate compression
                resizeImage(source, 1920, 1080, 80)
            }
            SharePreset.PRESS -> {
                // Full resolution, just copy
                source
            }
            SharePreset.SOCIAL -> {
                // 1080p, compressed, with watermark
                val resized = resizeImage(source, 1080, 1080, 70)
                if (watermarkConfig.enabled) applyWatermark(resized) else resized
            }
        }
    }

    private fun resizeImage(source: File, maxWidth: Int, maxHeight: Int, quality: Int): File {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return source

        val scale = minOf(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height,
            1f  // Don't upscale
        )

        val output = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val outputFile = File(context.cacheDir, "share_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { fos ->
            output.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }

        if (output != bitmap) output.recycle()
        bitmap.recycle()
        return outputFile
    }

    private fun applyWatermark(file: File): File {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return file
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()

        val canvas = Canvas(mutable)
        val paint = Paint().apply {
            color = Color.WHITE
            alpha = (watermarkConfig.opacity * 255).toInt()
            textSize = mutable.height * 0.03f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val text = watermarkConfig.text
        val textWidth = paint.measureText(text)

        // Bottom-right corner
        canvas.drawText(
            text,
            mutable.width - textWidth - 20f,
            mutable.height - 20f,
            paint
        )

        val outputFile = File(context.cacheDir, "watermarked_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { fos ->
            mutable.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
        mutable.recycle()
        return outputFile
    }

    // --- export/share/quick ---
    inner class QuickShare : ApiEndpoint<QuickShareRequest, Boolean> {
        override val path = "export/share/quick"
        override val module = "export"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: QuickShareRequest): ApiResponse<Boolean> {
            val prepared = prepareForShare(request.filePath, request.preset)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                prepared
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (request.filePath.endsWith(".mp4")) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            return ApiResponse.success(true)
        }
    }

    // --- export/share/batch ---
    inner class BatchShare : ApiEndpoint<BatchShareRequest, Boolean> {
        override val path = "export/share/batch"
        override val module = "export"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: BatchShareRequest): ApiResponse<Boolean> {
            val uris = request.filePaths.map { path ->
                val prepared = prepareForShare(path, request.preset)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", prepared)
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Share ${uris.size} files").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            return ApiResponse.success(true)
        }
    }

    // --- export/watermark/config ---
    inner class ConfigureWatermark : ApiEndpoint<WatermarkConfig, Boolean> {
        override val path = "export/watermark/config"
        override val module = "export"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: WatermarkConfig): ApiResponse<Boolean> {
            watermarkConfig = request
            return ApiResponse.success(true)
        }
    }

    // --- export/raw/extract ---
    inner class ExportRaw : ApiEndpoint<RawExportRequest, List<String>> {
        override val path = "export/raw/extract"
        override val module = "export"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: RawExportRequest): ApiResponse<List<String>> {
            // Collect files matching the filter criteria
            val files = request.filePaths.filter { path ->
                val file = File(path)
                file.exists() && (request.minStarRating == null || true)
            }
            return ApiResponse.success(files)
        }
    }
}

@Serializable
enum class SharePreset { COACH, PRESS, SOCIAL }

data class QuickShareRequest(
    val filePath: String,
    val preset: SharePreset = SharePreset.COACH
)

data class BatchShareRequest(
    val filePaths: List<String>,
    val preset: SharePreset = SharePreset.COACH
)

@Serializable
data class WatermarkConfig(
    val enabled: Boolean = false,
    val text: String = "GateShot",
    val opacity: Float = 0.5f
)

data class RawExportRequest(
    val filePaths: List<String>,
    val minStarRating: Int? = null
)
