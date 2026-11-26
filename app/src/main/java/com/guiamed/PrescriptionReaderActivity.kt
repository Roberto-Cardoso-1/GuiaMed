package com.guiamed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.ReceitaResponse
import com.guiamed.ocr.MlKitOcrEngine
import com.guiamed.ocr.readPrescription
import com.guiamed.speech.TtsManager
import com.guiamed.ui.ReadingPanel
import com.guiamed.ui.toReadingPresentation
import com.guiamed.utils.VibrationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrescriptionReaderActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var readingPanel: ReadingPanel
    private lateinit var ttsManager: TtsManager
    private lateinit var vibrationManager: VibrationManager
    private lateinit var ocrEngine: MlKitOcrEngine
    private lateinit var repository: GuiaMedRepository
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sourceUri: Uri? = null
    private var displayedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prescription_reader)

        imageView = findViewById(R.id.prescriptionImage)
        readingPanel = ReadingPanel(findViewById(R.id.readingPanel))
        ttsManager = TtsManager(this)
        vibrationManager = VibrationManager(this)
        ocrEngine = MlKitOcrEngine()
        repository = GuiaMedRepository()

        sourceUri = intent.getStringExtra("pdf_uri")?.let(Uri::parse)
            ?: intent.getStringExtra("image_uri")?.let(Uri::parse)
        if (sourceUri == null) {
            showError("Não foi possível acessar a receita compartilhada.")
            return
        }

        readingPanel.show("Lendo receita compartilhada")
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
                    readingPanel.show("Processando receita")
                }
                check(ocrEngine.initialize()) { "Falha ao inicializar OCR" }
                val result = ocrEngine.readPrescription(this@PrescriptionReaderActivity, repository, bitmap)
                withContext(Dispatchers.Main) {
                    if (result == null) showError(getString(R.string.no_text_found))
                    else showPrescription(result)
                }
            } catch (error: CancellationException) {
                throw error
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
        val result = response.toReadingPresentation()
        readingPanel.show(result)
        if (result.alert != null) vibrationManager.vibrateAlert()
        else vibrationManager.vibrateDoubleShort()
        ttsManager.speak(result.speech, TtsManager.UTTERANCE_ID_READING)
    }

    private fun showError(message: String) {
        readingPanel.show("Não foi possível ler a receita", message, getString(R.string.visual_alert_label))
        vibrationManager.vibrateTripleLong()
        ttsManager.speak(message, TtsManager.UTTERANCE_ID_ALERT)
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        ocrEngine.close()
        displayedBitmap?.recycle()
        scope.cancel()
    }
}
