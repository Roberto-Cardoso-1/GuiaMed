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
import kotlinx.coroutines.CompletableDeferred

class SpeechRecognitionManager(context: Context) {

    companion object {
        private const val TAG = "SpeechRecognition"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentDeferred: CompletableDeferred<String>? = null

    fun startListening(onReady: () -> Unit = {}): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        mainHandler.post {
            currentDeferred?.takeIf { it.isActive }?.completeExceptionally(
                IllegalStateException("Uma sessão de voz já estava ativa")
            )
            releaseRecognizer()
            currentDeferred = deferred

            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                finishWithError(deferred, "Reconhecimento de voz indisponível neste aparelho")
                return@post
            }

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                speechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = onReady()
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
                            SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi o nome da alergia"
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
                            finishWithError(deferred, "Não entendi o nome da alergia")
                        } else {
                            finishWithResult(deferred, text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
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

    private fun finishWithResult(deferred: CompletableDeferred<String>, text: String) {
        if (currentDeferred !== deferred) return
        if (deferred.isActive) deferred.complete(text)
        releaseRecognizer()
    }

    private fun finishWithError(deferred: CompletableDeferred<String>, message: String) {
        if (currentDeferred !== deferred) return
        if (deferred.isActive) deferred.completeExceptionally(IllegalStateException(message))
        releaseRecognizer()
    }

    fun stopListening() {
        mainHandler.post {
            currentDeferred?.takeIf { it.isActive }?.completeExceptionally(
                IllegalStateException("Reconhecimento de voz interrompido")
            )
            releaseRecognizer()
        }
    }

    private fun releaseRecognizer() {
        val recognizer = speechRecognizer
        speechRecognizer = null
        currentDeferred = null
        recognizer?.cancel()
        recognizer?.destroy()
    }

    fun destroy() = stopListening()
}
