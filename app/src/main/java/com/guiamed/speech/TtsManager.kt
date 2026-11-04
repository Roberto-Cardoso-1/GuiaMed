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
    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    @Volatile private var initializationFinished = false
    @Volatile private var speaking = false
    private val initializationListeners = mutableListOf<(Boolean) -> Unit>()
    private val speakListeners = mutableMapOf<String, (Boolean) -> Unit>()

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
            isInitialized = available
            initializationFinished = true
            if (available) {
                configureTts()
            } else {
                Log.e(TAG, "Falha ao inicializar TTS em pt-BR")
            }
            val listeners = initializationListeners.toList()
            initializationListeners.clear()
            listeners.forEach { listener -> mainHandler.post { listener(available) } }
        }
    }

    private fun configureTts() {
        tts?.apply {
            setSpeechRate(0.9f)
            setPitch(1.0f)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    speaking = true
                }

                override fun onDone(utteranceId: String) {
                    speaking = false
                    completeRequest(utteranceId, true)
                }

                @Deprecated("Deprecated by Android, still required by the interface")
                override fun onError(utteranceId: String) {
                    speaking = false
                    completeRequest(utteranceId, false)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    speaking = false
                    completeRequest(utteranceId, false)
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    speaking = false
                    completeRequest(utteranceId, false)
                }
            })
        }
    }

    private fun completeRequest(requestId: String, success: Boolean) {
        val listener = synchronized(speakListeners) { speakListeners.remove(requestId) }
        listener?.let { mainHandler.post { it(success) } }
    }

    fun speak(
        text: String,
        utteranceId: String = UTTERANCE_ID_DEFAULT,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!isInitialized || tts == null) {
            if (!initializationFinished) {
                addInitializationListener { ready ->
                    if (ready) speak(text, utteranceId, onComplete) else onComplete?.invoke(false)
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
        onComplete?.let { synchronized(speakListeners) { speakListeners[requestId] = it } }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, requestId)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, requestId)
        if (result != TextToSpeech.SUCCESS) {
            completeRequest(requestId, false)
            return false
        }
        return true
    }

    fun speakStartupMessage() = speak(appContext.getString(R.string.app_opened), UTTERANCE_ID_STARTUP)

    fun speakReadingPackage() = speak(appContext.getString(R.string.reading_package), UTTERANCE_ID_READING)

    fun speakDangerAlert(message: String) = speak(message, UTTERANCE_ID_ALERT)

    fun isSpeakingNow(): Boolean = speaking || tts?.isSpeaking == true

    fun stop() {
        tts?.stop()
        speaking = false
        val callbacks = synchronized(speakListeners) {
            speakListeners.values.toList().also { speakListeners.clear() }
        }
        callbacks.forEach { callback -> mainHandler.post { callback(false) } }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        initializationFinished = true
    }

    fun addInitializationListener(listener: (Boolean) -> Unit) {
        when {
            isInitialized -> mainHandler.post { listener(true) }
            initializationFinished -> mainHandler.post { listener(false) }
            else -> initializationListeners.add(listener)
        }
    }
}
