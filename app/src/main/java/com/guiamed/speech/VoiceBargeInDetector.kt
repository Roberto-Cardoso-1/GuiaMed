package com.guiamed.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

internal class VoiceBargeInDetector {

    enum class Mode {
        TTS_BARGE_IN,
        AMBIENT_STANDBY,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionToken = AtomicLong(0)
    @Volatile private var active = false
    @Volatile private var currentMode: Mode? = null
    @Volatile private var onSpeechCallback: (() -> Unit)? = null
    private var workerThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(
        mode: Mode = Mode.TTS_BARGE_IN,
        onUserSpoke: () -> Unit,
    ) {
        onSpeechCallback = onUserSpoke
        if (active && currentMode == mode && workerThread?.isAlive == true) {
            return
        }

        stop(clearCallback = false)
        onSpeechCallback = onUserSpoke
        val mySession = sessionToken.incrementAndGet()
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return

        val preferVoiceCommSource = mode == Mode.TTS_BARGE_IN && AcousticEchoCanceler.isAvailable()
        val recorder = createRecorder(
            audioSource = if (preferVoiceCommSource) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            sampleRate = sampleRate,
            bufferBytes = minBuffer * 2,
        ) ?: if (preferVoiceCommSource) {
            createRecorder(MediaRecorder.AudioSource.MIC, sampleRate, minBuffer * 2)
        } else {
            null
        } ?: return

        val echoCanceler = if (mode == Mode.TTS_BARGE_IN && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        } else {
            null
        }

        currentMode = mode
        active = true
        workerThread = Thread({
            val buffer = ShortArray(640) // ~40 ms por janela
            var detected = false
            val calibrationWindowMs = if (mode == Mode.TTS_BARGE_IN) 350L else 180L
            val requiredFrames = if (mode == Mode.TTS_BARGE_IN) 3 else 2
            val hasAec = echoCanceler != null
            try {
                recorder.startRecording()
                val startedAt = System.currentTimeMillis()
                var baselineRms = if (mode == Mode.TTS_BARGE_IN) 1_600.0 else 550.0
                var ttsReferenceRms = 0.0
                var consecutiveSpeechFrames = 0

                while (active && sessionToken.get() == mySession) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i].toDouble()
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / read)
                    val elapsed = System.currentTimeMillis() - startedAt

                    if (elapsed < calibrationWindowMs) {
                        if (mode == Mode.TTS_BARGE_IN) {
                            ttsReferenceRms = maxOf(ttsReferenceRms, minOf(rms, 10_000.0))
                            baselineRms = maxOf(baselineRms * 0.72 + rms * 0.28, ttsReferenceRms * 0.72)
                        } else {
                            baselineRms = maxOf(450.0, baselineRms * 0.75 + rms * 0.25)
                        }
                        continue
                    }

                    val threshold = if (mode == Mode.TTS_BARGE_IN) {
                        val ttsFloor = if (hasAec) 3_400.0 else 5_600.0
                        maxOf(ttsFloor, baselineRms * 2.15, ttsReferenceRms * 1.35)
                    } else {
                        maxOf(1_500.0, baselineRms * 2.35)
                    }
                    if (rms >= threshold) {
                        consecutiveSpeechFrames++
                        if (consecutiveSpeechFrames >= requiredFrames) {
                            detected = true
                            active = false
                            currentMode = null
                            break
                        }
                    } else {
                        consecutiveSpeechFrames = 0
                        val baselineFloor = if (mode == Mode.TTS_BARGE_IN) {
                            maxOf(1_600.0, ttsReferenceRms * 0.65)
                        } else {
                            450.0
                        }
                        baselineRms = maxOf(baselineFloor, baselineRms * 0.85 + rms * 0.15)
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (sessionToken.get() == mySession) {
                    active = false
                    currentMode = null
                }
                runCatching { echoCanceler?.release() }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }

            if (detected && sessionToken.get() == mySession) {
                mainHandler.post {
                    if (sessionToken.get() == mySession) {
                        onSpeechCallback?.invoke()
                    }
                }
            }
        }, "GuiaMed-VoiceMonitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop(clearCallback: Boolean = true) {
        sessionToken.incrementAndGet()
        active = false
        currentMode = null
        if (clearCallback) onSpeechCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        val thread = workerThread
        workerThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(150) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(
        audioSource: Int,
        sampleRate: Int,
        bufferBytes: Int,
    ): AudioRecord? {
        val recorder = runCatching {
            AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        }.getOrNull() ?: return null
        if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
        runCatching { recorder.release() }
        return null
    }
}
