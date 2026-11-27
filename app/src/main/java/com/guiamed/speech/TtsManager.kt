package com.guiamed.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.guiamed.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        const val UTTERANCE_ID_DEFAULT = "default"
        const val UTTERANCE_ID_STARTUP = "startup"
        const val UTTERANCE_ID_READING = "reading"
        const val UTTERANCE_ID_ALERT = "alert"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicLong(0)
    private val speechGeneration = AtomicLong(0)
    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    @Volatile private var initializationFinished = false
    @Volatile private var speaking = false
    private val initializationListeners = mutableListOf<(Boolean) -> Unit>()
    private val speakListeners = mutableMapOf<String, (Boolean) -> Unit>()
    private val speakStartListeners = mutableMapOf<String, () -> Unit>()
    @Volatile private var activeUtteranceId: String? = null
    @Volatile var onSpeakingChanged: ((Boolean) -> Unit)? = null

    init {
        initialize()
    }

    private fun initialize() {
        // Usa o mecanismo configurado pelo usuario; nem todo aparelho possui o
        // pacote do Google instalado.
        tts = TextToSpeech(appContext) { status ->
            val available = if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                false
            }
            val listeners = synchronized(initializationListeners) {
                if (available) {
                    configureTts()
                } else {
                    Log.e(TAG, "Falha ao inicializar TTS em pt-BR")
                }
                isInitialized = available
                initializationFinished = true
                initializationListeners.toList().also { initializationListeners.clear() }
            }
            listeners.forEach { listener -> mainHandler.post { listener(available) } }
        }
    }

    private fun configureTts() {
        tts?.apply {
            setSpeechRate(1.15f)
            setPitch(1.0f)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    if (utteranceId == activeUtteranceId) {
                        speaking = true
                        mainHandler.post { onSpeakingChanged?.invoke(true) }
                    }
                    val generation = speechGeneration.get()
                    val onStart = synchronized(speakStartListeners) { speakStartListeners.remove(utteranceId) }
                    onStart?.let {
                        mainHandler.post {
                            if (generation == speechGeneration.get() && utteranceId == activeUtteranceId) {
                                it()
                            }
                        }
                    }
                }

                override fun onDone(utteranceId: String) {
                    if (utteranceId == activeUtteranceId) {
                        speaking = false
                        activeUtteranceId = null
                        mainHandler.post { onSpeakingChanged?.invoke(false) }
                    }
                    completeRequest(utteranceId, true)
                }

                @Deprecated("Deprecated by Android, still required by the interface")
                override fun onError(utteranceId: String) {
                    if (utteranceId == activeUtteranceId) {
                        speaking = false
                        activeUtteranceId = null
                        mainHandler.post { onSpeakingChanged?.invoke(false) }
                    }
                    completeRequest(utteranceId, false)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    if (utteranceId == activeUtteranceId) {
                        speaking = false
                        activeUtteranceId = null
                        mainHandler.post { onSpeakingChanged?.invoke(false) }
                    }
                    completeRequest(utteranceId, false)
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    if (utteranceId == activeUtteranceId) {
                        speaking = false
                        activeUtteranceId = null
                        mainHandler.post { onSpeakingChanged?.invoke(false) }
                    }
                    completeRequest(utteranceId, false)
                }
            })
        }
    }

    private fun completeRequest(requestId: String, success: Boolean) {
        val generation = speechGeneration.get()
        val listener = synchronized(speakListeners) {
            speakStartListeners.remove(requestId)
            speakListeners.remove(requestId)
        }
        listener?.let { mainHandler.post { it(success && generation == speechGeneration.get()) } }
    }

    fun speak(
        text: String,
        utteranceId: String = UTTERANCE_ID_DEFAULT,
        onStart: (() -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!isInitialized || tts == null) {
            if (!initializationFinished) {
                val generation = speechGeneration.get()
                addInitializationListener { ready ->
                    if (generation != speechGeneration.get()) onComplete?.invoke(false)
                    else if (ready) speak(text, utteranceId, onStart, onComplete) else onComplete?.invoke(false)
                }
                return true
            }
            onComplete?.invoke(false)
            return false
        }
        if (text.isBlank()) {
            onComplete?.invoke(true)
            return true
        }

        val requestId = "$utteranceId-${requestCounter.incrementAndGet()}"
        synchronized(speakListeners) {
            onStart?.let { speakStartListeners[requestId] = it }
            onComplete?.let { speakListeners[requestId] = it }
        }
        activeUtteranceId = requestId
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, requestId)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, requestId)
        if (result != TextToSpeech.SUCCESS) {
            if (activeUtteranceId == requestId) activeUtteranceId = null
            completeRequest(requestId, false)
            return false
        }
        return true
    }

    fun speakStartupMessage(onComplete: ((Boolean) -> Unit)? = null) =
        speak(appContext.getString(R.string.app_opened), UTTERANCE_ID_STARTUP, onComplete = onComplete)

    fun speakReadingPackage() = speak(appContext.getString(R.string.reading_package), UTTERANCE_ID_READING)

    fun speakDangerAlert(message: String) = speak(message, UTTERANCE_ID_ALERT)

    fun isSpeakingNow(): Boolean = speaking || tts?.isSpeaking == true

    fun stop() {
        val wasSpeaking = speaking
        speechGeneration.incrementAndGet()
        activeUtteranceId = null
        tts?.stop()
        speaking = false
        if (wasSpeaking) {
            mainHandler.post { onSpeakingChanged?.invoke(false) }
        }
        val callbacks = synchronized(speakListeners) {
            speakStartListeners.clear()
            speakListeners.values.toList().also { speakListeners.clear() }
        }
        callbacks.forEach { callback -> mainHandler.post { callback(false) } }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        synchronized(initializationListeners) {
            isInitialized = false
            initializationFinished = true
            initializationListeners.clear()
        }
    }

    fun addInitializationListener(listener: (Boolean) -> Unit) {
        val immediateResult: Boolean? = synchronized(initializationListeners) {
            when {
                isInitialized -> true
                initializationFinished -> false
                else -> {
                    initializationListeners.add(listener)
                    null
                }
            }
        }
        if (immediateResult != null) {
            mainHandler.post { listener(immediateResult) }
        }
    }
}
