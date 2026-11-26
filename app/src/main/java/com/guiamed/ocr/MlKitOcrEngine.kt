package com.guiamed.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.resume

/**
 * OCR usando o modelo ML Kit incluído no APK, disponível sem internet.
 * Compatível com português brasileiro automaticamente.
 */
class MlKitOcrEngine {
    internal data class PackageFrame(val text: String, val ean: String?)

    private val barcodeScannerDelegate = lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
                .build(),
        )
    }

    private val barcodeScanner by barcodeScannerDelegate

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    internal fun analyzePackageFrame(proxy: ImageProxy, onResult: (PackageFrame) -> Unit) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val textTask = runCatching { getOrCreateRecognizer().process(image) }
            .getOrElse { Tasks.forException(it as? Exception ?: IllegalStateException(it)) }
        val barcodeTask = runCatching { barcodeScanner.process(image) }
            .getOrElse { Tasks.forException(it as? Exception ?: IllegalStateException(it)) }
        Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener {
            try {
                val text = if (textTask.isSuccessful) textTask.result.text.trim() else ""
                val codes = if (barcodeTask.isSuccessful) barcodeTask.result else emptyList()
                val ean = codes.mapNotNull { code -> code.rawValue }.distinct().singleOrNull()
                onResult(PackageFrame(text, ean))
            } finally {
                proxy.close()
            }
        }
    }


    companion object {
        private const val TAG = "OcrEngine"
    }

    private var isInitialized = false
    private var recognizer: TextRecognizer? = null

    suspend fun initialize(): Boolean {
        return try {
            getOrCreateRecognizer()
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
            val activeRecognizer = getOrCreateRecognizer()
            val image = InputImage.fromBitmap(bitmap, 0)

            activeRecognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    val result = visionText.text.trim()
                    Log.d(TAG, "OCR concluído com ${result.length} caracteres")
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { e: Exception ->
                    Log.e(TAG, "Erro no OCR", e)
                    if (continuation.isActive) continuation.resume("")
                }
        }
    }

    @Synchronized
    private fun getOrCreateRecognizer(): TextRecognizer = recognizer
        ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }

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
        val focusedText = recognizeFocusedPackageText(bitmap)
        if (isLikelyReadablePackageText(focusedText)) return focusedText
        return mergeRecognizedLines(listOf(focusedText, recognizeFullPackageText(bitmap)))
    }

    suspend fun recognizeFocusedPackageText(bitmap: Bitmap): String {
        // Primeiro leia a região indicada pela moldura, recortada da foto nativa.
        // Isso preserva letras pequenas e evita processar o fundo desnecessariamente.
        val horizontalMargin = (bitmap.width * 0.12f).toInt()
        val verticalMargin = (bitmap.height * 0.08f).toInt()
        val cropped = if (
            bitmap.width - horizontalMargin * 2 >= 640 &&
            bitmap.height - verticalMargin * 2 >= 640
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

        if (cropped == null) return recognizeFullPackageText(bitmap)
        return try {
            val croppedPrepared = scaleDownForOcr(cropped, maxDimension = 2800)
            try {
                recognizeText(croppedPrepared).trim()
            } finally {
                if (croppedPrepared !== cropped && currentCoroutineContext().isActive) {
                    croppedPrepared.recycle()
                }
            }
        } finally {
            if (currentCoroutineContext().isActive) cropped.recycle()
        }
    }

    suspend fun recognizeFullPackageText(bitmap: Bitmap): String {
        // Fallback independente para embalagens fora do centro ou com formato incomum.
        val prepared = scaleDownForOcr(bitmap, maxDimension = 2200)
        return try {
            recognizeText(prepared).trim()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Falha na passagem ampla do OCR", error)
            ""
        } finally {
            if (prepared !== bitmap && currentCoroutineContext().isActive) prepared.recycle()
        }
    }

    private fun isLikelyReadablePackageText(text: String): Boolean {
        val words = Regex("""[\p{L}]{4,}""")
            .findAll(text)
            .map { it.value }
            .toList()
        return words.size >= 2
    }

    /**
     * Receitas têm texto menor e mais espalhado que embalagens. Mantém a folha
     * inteira e preserva a ordem das linhas em cada passagem.
     */
    suspend fun recognizeDocumentTextFromBitmap(
        bitmap: Bitmap,
        highContrast: Boolean = false,
    ): String {
        if (highContrast) return recognizeDocumentTiles(bitmap)

        val prepared = scaleDownForOcr(bitmap, maxDimension = 3200)
        return try {
            recognizeText(prepared)
        } finally {
            if (prepared !== bitmap && currentCoroutineContext().isActive) prepared.recycle()
        }
    }

    private suspend fun recognizeDocumentTiles(bitmap: Bitmap): String {
        val tiles = createOverlappingDocumentTiles(bitmap)
        val attempts = mutableListOf<String>()
        try {
            for (tile in tiles) {
                val prepared = scaleDownForOcr(tile, maxDimension = 2400)
                val enhanced = enhanceForOcr(prepared)
                try {
                    attempts += recognizeText(enhanced)
                } finally {
                    enhanced.recycle()
                    if (prepared !== tile) prepared.recycle()
                }
            }
        } finally {
            tiles.forEach { tile -> if (!tile.isRecycled) tile.recycle() }
        }
        return mergeOverlappingDocumentTexts(attempts)
    }

    private fun createOverlappingDocumentTiles(source: Bitmap): List<Bitmap> {
        val overlap = 0.16f
        return if (source.height >= source.width) {
            val tileHeight = (source.height * (0.5f + overlap / 2f)).toInt()
                .coerceIn(1, source.height)
            listOf(
                Bitmap.createBitmap(source, 0, 0, source.width, tileHeight),
                Bitmap.createBitmap(source, 0, source.height - tileHeight, source.width, tileHeight),
            )
        } else {
            val tileWidth = (source.width * (0.5f + overlap / 2f)).toInt()
                .coerceIn(1, source.width)
            listOf(
                Bitmap.createBitmap(source, 0, 0, tileWidth, source.height),
                Bitmap.createBitmap(source, source.width - tileWidth, 0, tileWidth, source.height),
            )
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

    private fun mergeOverlappingDocumentTexts(parts: List<String>): String {
        val merged = mutableListOf<String>()
        parts.forEach { part ->
            val nextLines = part.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            if (merged.isEmpty()) {
                merged += nextLines
                return@forEach
            }
            val maxOverlap = minOf(10, merged.size, nextLines.size)
            val overlap = (maxOverlap downTo 1).firstOrNull { size ->
                merged.takeLast(size).map(::normalizeMergeLine) ==
                    nextLines.take(size).map(::normalizeMergeLine)
            } ?: 0
            merged += nextLines.drop(overlap)
        }
        return merged.joinToString("\n")
    }

    private fun normalizeMergeLine(line: String): String = line
        .uppercase()
        .replace(Regex("""\s+"""), " ")
        .trim()

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
        if (barcodeScannerDelegate.isInitialized()) barcodeScanner.close()
        recognizer?.close()
        recognizer = null
        isInitialized = false
    }
}
