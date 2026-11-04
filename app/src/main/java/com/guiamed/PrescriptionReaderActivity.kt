package com.guiamed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.ReceitaResponse
import com.guiamed.ocr.TesseractOcrEngine
import com.guiamed.speech.TtsManager
import com.guiamed.utils.VibrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrescriptionReaderActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var ttsManager: TtsManager
    private lateinit var vibrationManager: VibrationManager
    private lateinit var ocrEngine: TesseractOcrEngine
    private lateinit var repository: GuiaMedRepository
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sourceUri: Uri? = null
    private var displayedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prescription_reader)

        imageView = findViewById(R.id.prescriptionImage)
        statusText = findViewById(R.id.statusText)
        ttsManager = TtsManager(this)
        vibrationManager = VibrationManager(this)
        ocrEngine = TesseractOcrEngine(this)
        repository = GuiaMedRepository()

        sourceUri = intent.getStringExtra("pdf_uri")?.let(Uri::parse)
            ?: intent.getStringExtra("image_uri")?.let(Uri::parse)
        if (sourceUri == null) {
            showError("Não foi possível acessar a receita compartilhada.")
            return
        }

        updateStatus(getString(R.string.reading_package), false)
        vibrationManager.vibrateDoubleShort()
        ttsManager.speak("Lendo receita compartilhada.")
        loadAndProcess()
    }

    private fun loadAndProcess() {
        scope.launch {
            try {
                val uri = requireNotNull(sourceUri)
                val isPdf = intent.hasExtra("pdf_uri") || contentResolver.getType(uri) == "application/pdf"
                val bitmap = if (isPdf) renderFirstPdfPage(uri) else loadImage(uri)
                displayedBitmap = bitmap

                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bitmap)
                    updateStatus("Processando receita", false)
                }
                check(ocrEngine.initialize()) { "Falha ao inicializar OCR" }
                val ocrText = ocrEngine.recognizeTextFromBitmap(bitmap)
                if (ocrText.isBlank()) throw IllegalStateException(getString(R.string.no_text_found))

                val result = repository.lerReceita(this@PrescriptionReaderActivity, ocrText)
                withContext(Dispatchers.Main) {
                    result.onSuccess(::showPrescription)
                        .onFailure { showError(it.message ?: getString(R.string.network_error)) }
                }
            } catch (error: Exception) {
                Log.e("GuiaMed", "Erro ao processar receita compartilhada", error)
                withContext(Dispatchers.Main) {
                    showError(error.message ?: getString(R.string.ocr_error))
                }
            }
        }
    }

    private fun loadImage(uri: Uri): Bitmap = contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
    } ?: throw IllegalArgumentException("Não foi possível ler a imagem")

    private fun renderFirstPdfPage(uri: Uri): Bitmap {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Não foi possível abrir o PDF")
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                if (renderer.pageCount == 0) throw IllegalArgumentException("O PDF não possui páginas")
                renderer.openPage(0).use { page ->
                    val scale = minOf(2f, 2048f / maxOf(page.width, page.height).toFloat())
                    val width = maxOf(1, (page.width * scale).toInt())
                    val height = maxOf(1, (page.height * scale).toInt())
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun showPrescription(response: ReceitaResponse) {
        if (response.medicamentos.isEmpty()) {
            showError(response.mensagem ?: "Nenhum medicamento foi identificado na receita.")
            return
        }
        updateStatus("Receita lida", false)
        vibrationManager.vibrateDoubleShort()
        val speech = buildString {
            append("Receita médica. ")
            response.medicamentos.forEachIndexed { index, medicine ->
                append("${index + 1}. ${medicine.nome}, ${medicine.dosagem}, ${medicine.frequencia}. ")
                medicine.duracao?.let { append("Por $it. ") }
                medicine.instrucoes?.let { append("$it. ") }
            }
            response.horarios.forEach { append("Horário $it. ") }
        }
        ttsManager.speak(speech, TtsManager.UTTERANCE_ID_READING)
    }

    private fun showError(message: String) {
        updateStatus("Erro: $message", true)
        vibrationManager.vibrateTripleLong()
        ttsManager.speak(message, TtsManager.UTTERANCE_ID_ALERT)
    }

    private fun updateStatus(message: String, danger: Boolean) {
        statusText.text = message
        statusText.setBackgroundColor(
            ContextCompat.getColor(this, if (danger) R.color.red_danger else R.color.black)
        )
        statusText.setTextColor(
            ContextCompat.getColor(this, if (danger) R.color.white else R.color.yellow_high_contrast)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        ocrEngine.close()
        displayedBitmap?.recycle()
        scope.cancel()
    }
}
