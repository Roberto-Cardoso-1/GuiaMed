package com.guiamed.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

class SpeechRecognitionManager(context: Context) {

    companion object {
        private const val TAG = "SpeechRecognition"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenGeneration = AtomicLong(0)
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentDeferred: CompletableDeferred<String>? = null
    private var pendingPartialRunnable: Runnable? = null

    @Volatile
    var isListening: Boolean = false
        private set

    var onSessionEnded: (() -> Unit)? = null

    fun startListening(
        noMatchMessage: String = "Não entendi o que foi dito",
        minimumInputLengthMillis: Long = 2_500L,
        completeSilenceLengthMillis: Long = 2_200L,
        partialDispatchDebounceMillis: Long = 280L,
        partialAcceptPredicate: ((String) -> Boolean)? = null,
        onReady: () -> Unit = {},
    ): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        val generation = listenGeneration.incrementAndGet()
        isListening = true
        mainHandler.post {
            if (generation != listenGeneration.get()) {
                isListening = currentDeferred?.isActive == true
                deferred.completeExceptionally(IllegalStateException("Reconhecimento de voz interrompido"))
                return@post
            }
            currentDeferred?.takeIf { it.isActive }?.completeExceptionally(
                IllegalStateException("Uma sessão de voz já estava ativa")
            )
            releaseRecognizer(notifySessionEnd = false)
            isListening = true
            currentDeferred = deferred

            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                finishWithError(deferred, "Reconhecimento de voz indisponível neste aparelho")
                return@post
            }

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                speechRecognizer = recognizer
                var lastPartialCandidate: String? = null
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (currentDeferred === deferred) onReady()
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Erro de áudio"
                            SpeechRecognizer.ERROR_CLIENT -> "Reconhecimento interrompido"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone negada"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erro de rede no reconhecimento de voz"
                            SpeechRecognizer.ERROR_NO_MATCH -> noMatchMessage
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Microfone ocupado. Tente novamente"
                            SpeechRecognizer.ERROR_SERVER -> "Serviço de voz indisponível"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nenhuma fala detectada. Tente novamente"
                            else -> "Erro no reconhecimento de voz"
                        }
                        Log.w(TAG, "$message ($error)")
                        finishWithError(deferred, message)
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (text.isBlank()) {
                            finishWithError(deferred, noMatchMessage)
                        } else {
                            finishWithResult(deferred, text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (currentDeferred !== deferred || partialAcceptPredicate == null) return
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (partial.isBlank() || partial == lastPartialCandidate) return
                        lastPartialCandidate = partial
                        clearPendingPartial()
                        if (!partialAcceptPredicate(partial)) return
                        val dispatch = Runnable {
                            if (currentDeferred === deferred && partialAcceptPredicate(partial)) {
                                finishWithResult(deferred, partial)
                            }
                        }
                        pendingPartialRunnable = dispatch
                        mainHandler.postDelayed(dispatch, partialDispatchDebounceMillis)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialAcceptPredicate != null)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimumInputLengthMillis)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceLengthMillis)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, minOf(completeSilenceLengthMillis, 1_400L))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                recognizer.startListening(intent)
            } catch (error: Exception) {
                Log.e(TAG, "Erro ao iniciar reconhecimento", error)
                finishWithError(deferred, "Não foi possível abrir o microfone")
            }
        }
        return deferred
    }

    private fun clearPendingPartial() {
        pendingPartialRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPartialRunnable = null
    }

    private fun finishWithResult(deferred: CompletableDeferred<String>, text: String) {
        if (currentDeferred !== deferred) return
        releaseRecognizer(notifySessionEnd = true)
        if (deferred.isActive) deferred.complete(text)
    }

    private fun finishWithError(deferred: CompletableDeferred<String>, message: String) {
        if (currentDeferred !== deferred) return
        releaseRecognizer(notifySessionEnd = true)
        if (deferred.isActive) deferred.completeExceptionally(IllegalStateException(message))
    }

    fun stopListening() {
        listenGeneration.incrementAndGet()
        val cleanup = {
            currentDeferred?.takeIf { it.isActive }?.completeExceptionally(
                IllegalStateException("Reconhecimento de voz interrompido")
            )
            releaseRecognizer(notifySessionEnd = true)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            mainHandler.post(cleanup)
        }
    }

    private fun releaseRecognizer(notifySessionEnd: Boolean) {
        clearPendingPartial()
        val recognizer = speechRecognizer
        speechRecognizer = null
        currentDeferred = null
        isListening = false
        recognizer?.cancel()
        recognizer?.destroy()
        if (notifySessionEnd) {
            onSessionEnded?.invoke()
        }
    }

    fun destroy() {
        onSessionEnded = null
        stopListening()
    }
}
