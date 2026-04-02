package com.gateshot.processing.sr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 200MP Telephoto Crop Optimizer.
 *
 * The X9 Pro's 200MP telephoto (1/1.56" sensor, 70mm) has the HIGHEST resolution
 * in the system. At 200MP, we can crop aggressively and still retain excellent
 * detail — OPPO claims lossless quality to 13.2x.
 *
 * However, 200MP means tiny 0.5µm pixels, which means more noise,
 * especially in the shadows and in low light on the slopes.
 *
 * This class provides:
 * 1. Intelligent crop from 200MP to target resolution with noise-aware processing
 * 2. Multi-frame noise reduction by stacking 200MP crops
 * 3. Detail preservation during downscale (Lanczos-like filtering)
 */
class TelephotoOptimizer {

    data class CropConfig(
        val zoomLevel: Float,           // Current zoom (e.g., 7.0 = 7x)
        val targetWidth: Int = 4000,    // Output resolution width
        val targetHeight: Int = 3000,   // Output resolution height
        val denoiseStrength: Float = 0.5f, // 0 = none, 1 = maximum
        val sharpenStrength: Float = 0.3f  // 0 = none, 1 = maximum
    )

    /**
     * Quality zones based on the 200MP telephoto's actual capabilities.
     */
    enum class QualityZone(val label: String) {
        OPTICAL_NATIVE("Native optical — full 200MP quality"),      // 1-5x (telephoto native)
        OPTICAL_TELE("Teleconverter optical — full quality"),       // 5-10x (with Hasselblad kit)
        LOSSLESS_CROP("200MP crop — lossless quality"),             // 10-13.2x
        ENHANCED_CROP("200MP crop + SR enhancement"),               // 13.2-20x
        DIGITAL_ZOOM("Digital zoom — SR + AI upscale required")     // 20x+
    }

    fun getQualityZone(zoomLevel: Float, hasTelevonverter: Boolean): QualityZone {
        return when {
            zoomLevel <= 5f -> QualityZone.OPTICAL_NATIVE
            zoomLevel <= 10f && hasTelevonverter -> QualityZone.OPTICAL_TELE
            zoomLevel <= 10f -> QualityZone.LOSSLESS_CROP  // Without teleconverter, 5-10x is crop
            zoomLevel <= 13.2f -> QualityZone.LOSSLESS_CROP
            zoomLevel <= 20f -> QualityZone.ENHANCED_CROP
            else -> QualityZone.DIGITAL_ZOOM
        }
    }

    /**
     * Smart downscale from 200MP crop with noise reduction.
     *
     * When shooting at 200MP, each pixel is only 0.5µm — very noisy.
     * By downscaling from 200MP to 50MP (a 4:1 pixel ratio), we effectively
     * average 4 pixels into 1, naturally reducing noise by ~50%.
     * This is "pixel binning in software."
     *
     * We go further by applying weighted downscaling that preserves edges.
     */
    fun smartDownscale(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        denoiseStrength: Float = 0.5f
    ): IntArray {
        val output = IntArray(targetWidth * targetHeight)

        val scaleX = sourceWidth.toFloat() / targetWidth
        val scaleY = sourceHeight.toFloat() / targetHeight

        for (ty in 0 until targetHeight) {
            for (tx in 0 until targetWidth) {
                // Source region covered by this output pixel
                val srcX0 = (tx * scaleX).toInt()
                val srcY0 = (ty * scaleY).toInt()
                val srcX1 = ((tx + 1) * scaleX).toInt().coerceAtMost(sourceWidth)
                val srcY1 = ((ty + 1) * scaleY).toInt().coerceAtMost(sourceHeight)

                // Collect all source pixels in this region
                var sumR = 0L; var sumG = 0L; var sumB = 0L
                var count = 0

                // Also track for edge detection
                var minLum = 255; var maxLum = 0

                for (sy in srcY0 until srcY1) {
                    for (sx in srcX0 until srcX1) {
                        val idx = sy * sourceWidth + sx
                        if (idx >= source.size) continue
                        val pixel = source[idx]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        sumR += r; sumG += g; sumB += b
                        count++

                        val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        if (lum < minLum) minLum = lum
                        if (lum > maxLum) maxLum = lum
                    }
                }

                if (count == 0) continue

                val avgR = (sumR / count).toInt()
                val avgG = (sumG / count).toInt()
                val avgB = (sumB / count).toInt()

                // Edge-aware: at edges (high local contrast), reduce averaging to preserve sharpness
                val localContrast = maxLum - minLum
                val isEdge = localContrast > 40

                if (isEdge && denoiseStrength < 0.8f) {
                    // At edges, use the center pixel instead of average (preserve sharpness)
                    val centerX = (srcX0 + srcX1) / 2
                    val centerY = (srcY0 + srcY1) / 2
                    val centerIdx = centerY * sourceWidth + centerX
                    if (centerIdx < source.size) {
                        output[ty * targetWidth + tx] = source[centerIdx]
                    } else {
                        output[ty * targetWidth + tx] = Color.argb(255, avgR, avgG, avgB)
                    }
                } else {
                    // Flat region: average (denoise)
                    output[ty * targetWidth + tx] = Color.argb(255, avgR, avgG, avgB)
                }
            }
        }

        return output
    }

    /**
     * Multi-frame noise reduction for 200MP telephoto shots.
     *
     * Stack multiple 200MP frames and average them. Because the 0.5µm pixels
     * are noisy, averaging 8-10 frames reduces noise by sqrt(N) — about 3x
     * noise reduction for 10 frames.
     *
     * Uses temporal outlier rejection to handle the moving racer:
     * - Static pixels (snow, gates): averaged across all frames
     * - Moving pixels (racer): taken from the reference frame only
     */
    fun multiFrameDenoise(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        motionThreshold: Int = 25
    ): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        if (frames.size == 1) return frames[0].copyOf()

        val output = IntArray(width * height)
        val reference = frames[0]
        val frameCount = frames.size

        for (idx in 0 until width * height) {
            if (idx >= reference.size) continue

            val refR = (reference[idx] shr 16) and 0xFF
            val refG = (reference[idx] shr 8) and 0xFF
            val refB = reference[idx] and 0xFF

            var sumR = 0L; var sumG = 0L; var sumB = 0L
            var validCount = 0

            for (frame in frames) {
                if (idx >= frame.size) continue
                val r = (frame[idx] shr 16) and 0xFF
                val g = (frame[idx] shr 8) and 0xFF
                val b = frame[idx] and 0xFF

                // Outlier rejection: if pixel differs too much, skip it (it's the racer)
                val diff = (kotlin.math.abs(r - refR) + kotlin.math.abs(g - refG) + kotlin.math.abs(b - refB)) / 3
                if (diff <= motionThreshold) {
                    sumR += r; sumG += g; sumB += b
                    validCount++
                }
            }

            if (validCount > 0) {
                output[idx] = Color.argb(
                    255,
                    (sumR / validCount).toInt().coerceIn(0, 255),
                    (sumG / validCount).toInt().coerceIn(0, 255),
                    (sumB / validCount).toInt().coerceIn(0, 255)
                )
            } else {
                // All frames had motion here — use reference
                output[idx] = reference[idx]
            }
        }

        return output
    }

    /**
     * Unsharp mask sharpening tuned for the 200MP telephoto.
     * Restores micro-contrast lost during noise reduction or compression.
     */
    fun sharpen(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float = 0.5f,  // 0-1
        radius: Int = 2
    ): IntArray {
        val output = pixels.copyOf()
        val amount = strength * 2f  // Scale to useful range

        for (y in radius until height - radius) {
            for (x in radius until width - radius) {
                val idx = y * width + x

                // Compute local average (blur)
                var avgR = 0; var avgG = 0; var avgB = 0; var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nIdx = (y + dy) * width + (x + dx)
                        if (nIdx < pixels.size) {
                            avgR += (pixels[nIdx] shr 16) and 0xFF
                            avgG += (pixels[nIdx] shr 8) and 0xFF
                            avgB += pixels[nIdx] and 0xFF
                            count++
                        }
                    }
                }
                avgR /= count; avgG /= count; avgB /= count

                // Unsharp mask: original + amount * (original - blur)
                val origR = (pixels[idx] shr 16) and 0xFF
                val origG = (pixels[idx] shr 8) and 0xFF
                val origB = pixels[idx] and 0xFF

                val sharpR = (origR + amount * (origR - avgR)).roundToInt().coerceIn(0, 255)
                val sharpG = (origG + amount * (origG - avgG)).roundToInt().coerceIn(0, 255)
                val sharpB = (origB + amount * (origB - avgB)).roundToInt().coerceIn(0, 255)

                output[idx] = Color.argb(255, sharpR, sharpG, sharpB)
            }
        }

        return output
    }
}
