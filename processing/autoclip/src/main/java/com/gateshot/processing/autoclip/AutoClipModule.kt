package com.gateshot.processing.autoclip

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AutoClipModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "autoclip"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Processing state
    private var isProcessing = false
    private var progress = 0f
    private var lastResult: AutoClipResult? = null

    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        RunAutoClip(),
        GetAutoClipStatus()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    private fun analyzeAudioTrack(videoPath: String): List<ClipSegment> {
        val segments = mutableListOf<ClipSegment>()
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(videoPath)

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) return segments

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            // Read audio samples and detect high-amplitude peaks (start beeps)
            val bufferSize = 8192
            val buffer = ByteBuffer.allocate(bufferSize)
            val peakTimestamps = mutableListOf<Long>()

            // Threshold for start beep detection
            val amplitudeThreshold = 20000
            var lastPeakTime = -5000000L  // 5 second cooldown between detections

            while (true) {
                val readSize = extractor.readSampleData(buffer, 0)
                if (readSize < 0) break

                val sampleTime = extractor.sampleTime  // microseconds

                // Analyze amplitude
                buffer.rewind()
                var maxAmplitude = 0
                val shortCount = readSize / 2
                for (i in 0 until shortCount) {
                    val sample = abs(buffer.getShort(i * 2).toInt())
                    if (sample > maxAmplitude) maxAmplitude = sample
                }

                if (maxAmplitude > amplitudeThreshold &&
                    sampleTime - lastPeakTime > 5_000_000) {  // 5s cooldown
                    peakTimestamps.add(sampleTime / 1000)  // Convert to ms
                    lastPeakTime = sampleTime
                }

                extractor.advance()
            }

            // Get total duration
            val totalDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            val totalDurationMs = totalDurationUs / 1000

            // Create segments from peaks
            for (i in peakTimestamps.indices) {
                val startMs = peakTimestamps[i]
                val endMs = if (i + 1 < peakTimestamps.size)
                    peakTimestamps[i + 1] - 1000  // 1s before next beep
                else
                    totalDurationMs

                segments.add(
                    ClipSegment(
                        runNumber = i + 1,
                        startMs = startMs,
                        endMs = endMs,
                        durationMs = endMs - startMs
                    )
                )
            }

            // If no beeps detected, return the whole video as one segment
            if (segments.isEmpty()) {
                segments.add(
                    ClipSegment(
                        runNumber = 1,
                        startMs = 0,
                        endMs = totalDurationMs,
                        durationMs = totalDurationMs
                    )
                )
            }

        } catch (_: Exception) {
        } finally {
            extractor.release()
        }

        return segments
    }

    // --- process/autoclip/run ---
    inner class RunAutoClip : ApiEndpoint<AutoClipRequest, AutoClipResult> {
        override val path = "process/autoclip/run"
        override val module = "autoclip"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: AutoClipRequest): ApiResponse<AutoClipResult> {
            if (isProcessing) return ApiResponse.error(409, "Already processing")
            isProcessing = true
            progress = 0f

            try {
                val segments = analyzeAudioTrack(request.videoPath)
                progress = 1f

                val result = AutoClipResult(
                    sourceVideo = request.videoPath,
                    segments = segments,
                    totalRuns = segments.size,
                    method = "audio_peak"
                )
                lastResult = result
                isProcessing = false

                // Publish run detected events
                segments.forEach { seg ->
                    eventBus.publish(AppEvent.RunDetected(seg.runNumber, null))
                }

                return ApiResponse.success(result)
            } catch (e: Exception) {
                isProcessing = false
                return ApiResponse.moduleError(module, e.message ?: "Auto-clip failed")
            }
        }
    }

    // --- process/autoclip/status ---
    inner class GetAutoClipStatus : ApiEndpoint<Unit, AutoClipStatus> {
        override val path = "process/autoclip/status"
        override val module = "autoclip"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<AutoClipStatus> {
            return ApiResponse.success(
                AutoClipStatus(
                    isProcessing = isProcessing,
                    progress = progress,
                    lastResult = lastResult
                )
            )
        }
    }
}

data class AutoClipRequest(val videoPath: String)

@Serializable
data class ClipSegment(
    val runNumber: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val bibNumber: Int? = null
)

data class AutoClipResult(
    val sourceVideo: String,
    val segments: List<ClipSegment>,
    val totalRuns: Int,
    val method: String
)

data class AutoClipStatus(
    val isProcessing: Boolean,
    val progress: Float,
    val lastResult: AutoClipResult?
)
