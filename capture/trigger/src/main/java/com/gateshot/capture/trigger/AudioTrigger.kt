package com.gateshot.capture.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioTrigger(
    private val context: Context,
    private val onTriggered: () -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var analysisJob: Job? = null
    private var isListening = false

    // Audio config
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Detection parameters
    // Start gate beep: typically a short (100-300ms) high-pitched tone (1000-3000 Hz)
    private var amplitudeThreshold = 8000   // Raw amplitude threshold
    private var frequencyMin = 800f          // Hz
    private var frequencyMax = 3500f         // Hz
    private var minDurationMs = 50L          // Min beep duration
    private var cooldownMs = 2000L           // Cooldown between triggers

    private var lastTriggerTime = 0L
    private var beepStartTime = 0L
    private var isInBeep = false

    var sensitivity: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            // Map sensitivity to amplitude threshold: higher sensitivity = lower threshold
            amplitudeThreshold = (12000 - (value * 10000)).toInt().coerceAtLeast(1000)
        }

    fun start(scope: CoroutineScope) {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            audioRecord?.startRecording()
            isListening = true

            analysisJob = scope.launch(Dispatchers.IO) {
                analyzeAudioLoop()
            }
            Log.i(TAG, "Audio trigger started (sensitivity=$sensitivity, threshold=$amplitudeThreshold)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio trigger", e)
        }
    }

    fun stop() {
        isListening = false
        analysisJob?.cancel()
        analysisJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
    }

    private suspend fun analyzeAudioLoop() {
        val buffer = ShortArray(bufferSize / 2)

        while (isListening) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            analyzeBuffer(buffer, read)
        }
    }

    private fun analyzeBuffer(buffer: ShortArray, length: Int) {
        // Find peak amplitude in this buffer
        var maxAmplitude = 0
        var sumSquares = 0L

        for (i in 0 until length) {
            val sample = Math.abs(buffer[i].toInt())
            if (sample > maxAmplitude) maxAmplitude = sample
            sumSquares += sample.toLong() * sample
        }

        val rms = Math.sqrt(sumSquares.toDouble() / length).toFloat()

        // Estimate dominant frequency using zero-crossing rate
        var zeroCrossings = 0
        for (i in 1 until length) {
            if ((buffer[i] > 0 && buffer[i - 1] <= 0) || (buffer[i] <= 0 && buffer[i - 1] > 0)) {
                zeroCrossings++
            }
        }
        val durationSec = length.toFloat() / sampleRate
        val estimatedFreq = zeroCrossings / (2 * durationSec)

        val now = System.currentTimeMillis()

        // Check if this looks like a start beep
        val isLoudEnough = maxAmplitude > amplitudeThreshold
        val isRightFrequency = estimatedFreq in frequencyMin..frequencyMax

        if (isLoudEnough && isRightFrequency) {
            if (!isInBeep) {
                beepStartTime = now
                isInBeep = true
            }

            val beepDuration = now - beepStartTime
            if (beepDuration >= minDurationMs && now - lastTriggerTime >= cooldownMs) {
                // TRIGGER!
                lastTriggerTime = now
                Log.i(TAG, "Audio trigger fired! Freq=${estimatedFreq.toInt()}Hz, Amp=$maxAmplitude")
                onTriggered()
            }
        } else {
            isInBeep = false
        }
    }

    companion object {
        private const val TAG = "AudioTrigger"
    }
}
