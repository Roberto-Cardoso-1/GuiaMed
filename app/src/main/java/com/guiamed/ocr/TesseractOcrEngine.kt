package com.guiamed.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * OCR usando ML Kit (built-in do Google Play Services — sem dependência externa)
 * Compatível com português brasileiro automaticamente.
 */
class TesseractOcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "OcrEngine"
    }

    private var isInitialized = false

    suspend fun initialize(): Boolean {
        return try {
            // ML Kit inicializa automaticamente no primeiro uso
            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar OCR", e)
            isInitialized = false
            false
        }
    }

    suspend fun recognizeText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    val result = visionText.text.trim()
                    Log.d(TAG, "OCR concluído com ${result.length} caracteres")
                    continuation.resume(result)
                }
                .addOnFailureListener { e: Exception ->
                    Log.e(TAG, "Erro no OCR", e)
                    continuation.resume("")
                }
                .addOnCompleteListener {
                    recognizer.close()
                }
        }
    }

    suspend fun recognizeText(image: android.media.Image): String {
        return try {
            val bitmap = imageToBitmap(image) ?: return ""
            recognizeText(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao converter imagem para OCR", e)
            ""
        }
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String {
        return recognizeText(bitmap)
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val planes: Array<android.media.Image.Plane> = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        uBuffer.get(nv21, ySize, uSize)
        vBuffer.get(nv21, ySize + uSize, vSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val byteArrayOutputStream = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, byteArrayOutputStream)
        val jpegBytes = byteArrayOutputStream.toByteArray()

        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    fun close() {
        // ML Kit já fecha o recognizer automaticamente no addOnCompleteListener
        isInitialized = false
    }
}
