package com.guiamed.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
            recognizeTextFromBitmap(bitmap).also { bitmap.recycle() }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao converter imagem para OCR", e)
            ""
        }
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String {
        val attempts = mutableListOf<String>()
        attempts += recognizeText(bitmap)

        val horizontalMargin = (bitmap.width * 0.09f).toInt()
        val verticalMargin = (bitmap.height * 0.09f).toInt()
        val cropped = if (
            bitmap.width - horizontalMargin * 2 >= 320 &&
            bitmap.height - verticalMargin * 2 >= 320
        ) {
            Bitmap.createBitmap(
                bitmap,
                horizontalMargin,
                verticalMargin,
                bitmap.width - horizontalMargin * 2,
                bitmap.height - verticalMargin * 2,
            )
        } else {
            null
        }

        if (cropped != null) {
            try {
                attempts += recognizeText(cropped)
                val enhanced = enhanceForOcr(cropped)
                try {
                    attempts += recognizeText(enhanced)
                } finally {
                    enhanced.recycle()
                }
            } finally {
                cropped.recycle()
            }
        }

        return mergeRecognizedLines(attempts)
    }

    /**
     * Receitas têm texto menor e mais espalhado que embalagens. Mantém a folha
     * inteira, limita imagens excessivamente grandes e combina a leitura
     * original com uma versão em alto contraste.
     */
    suspend fun recognizeDocumentTextFromBitmap(bitmap: Bitmap): String {
        val prepared = scaleDownForOcr(bitmap, maxDimension = 2400)
        return try {
            val attempts = mutableListOf<String>()
            attempts += recognizeText(prepared)
            val enhanced = enhanceForOcr(prepared)
            try {
                attempts += recognizeText(enhanced)
            } finally {
                enhanced.recycle()
            }
            mergeRecognizedLines(attempts)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    private fun mergeRecognizedLines(attempts: List<String>): String {
        val uniqueLines = linkedMapOf<String, String>()
        attempts.asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line -> uniqueLines.putIfAbsent(line.uppercase(), line) }
        return uniqueLines.values.joinToString("\n")
    }

    private fun scaleDownForOcr(source: Bitmap, maxDimension: Int): Bitmap {
        val largestDimension = maxOf(source.width, source.height)
        if (largestDimension <= maxDimension) return source
        val scale = maxDimension.toFloat() / largestDimension.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun enhanceForOcr(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.45f
        val translation = (-0.5f * contrast + 0.5f) * 255f
        grayscale.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translation,
                    0f, contrast, 0f, 0f, translation,
                    0f, 0f, contrast, 0f, translation,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayscale)
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
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
