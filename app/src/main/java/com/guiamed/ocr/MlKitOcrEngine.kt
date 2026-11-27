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
import com.guiamed.data.ExpirationReader
import com.guiamed.data.PackageVisionStats
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

/**
 * OCR usando o modelo ML Kit incluído no APK, disponível sem internet.
 * Compatível com português brasileiro automaticamente.
 */
class MlKitOcrEngine {
    internal data class PackageFrame(
        val text: String,
        val ean: String?,
        val visionStats: PackageVisionStats? = null,
    )

    private val barcodeScannerDelegate = lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
                .build(),
        )
    }

    private val barcodeScanner by barcodeScannerDelegate

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    internal fun analyzePackageFrame(
        proxy: ImageProxy,
        executor: Executor,
        onResult: (PackageFrame) -> Unit,
    ) {
        if (closed) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val frameWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val frameHeight = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
        val safeExecutor = Executor { runnable ->
            try {
                executor.execute(runnable)
            } catch (_: RejectedExecutionException) {
                runnable.run()
            }
        }
        try {
            val recognizerInstance = synchronized(this) {
                if (closed) null else getOrCreateRecognizer()
            }
            if (recognizerInstance == null) {
                proxy.close()
                return
            }
            val image = InputImage.fromMediaImage(mediaImage, rotation)
            val textTask = runCatching { recognizerInstance.process(image) }
                .getOrElse { Tasks.forException(it as? Exception ?: IllegalStateException(it)) }
            val barcodeTask = runCatching { barcodeScanner.process(image) }
                .getOrElse { Tasks.forException(it as? Exception ?: IllegalStateException(it)) }
            Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener(safeExecutor) {
                val frame = try {
                    val visionText = if (textTask.isSuccessful) textTask.result else null
                    val text = visionText?.text?.trim().orEmpty()
                    val codes = if (barcodeTask.isSuccessful) barcodeTask.result else emptyList()
                    val ean = codes.mapNotNull { code -> code.rawValue }.distinct().singleOrNull()
                    val stats = visionText?.let { extractVisionStats(it, frameWidth, frameHeight) }
                    PackageFrame(text, ean, stats)
                } finally {
                    proxy.close()
                }
                if (!closed) onResult(frame)
            }
        } catch (_: Throwable) {
            proxy.close()
        }
    }

    private fun extractVisionStats(
        visionText: Text,
        frameWidth: Int,
        frameHeight: Int,
    ): PackageVisionStats? {
        if (frameWidth <= 0 || frameHeight <= 0 || visionText.textBlocks.isEmpty()) return null
        var minLeft = frameWidth
        var minTop = frameHeight
        var maxRight = 0
        var maxBottom = 0
        var hasBox = false
        val lineHeights = mutableListOf<Float>()

        for (block in visionText.textBlocks) {
            val box = block.boundingBox
            if (box != null) {
                minLeft = minOf(minLeft, box.left.coerceIn(0, frameWidth))
                minTop = minOf(minTop, box.top.coerceIn(0, frameHeight))
                maxRight = maxOf(maxRight, box.right.coerceIn(0, frameWidth))
                maxBottom = maxOf(maxBottom, box.bottom.coerceIn(0, frameHeight))
                hasBox = true
            }
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                if (lineBox.height() > 0) {
                    lineHeights += lineBox.height().toFloat() / frameHeight.toFloat()
                }
            }
        }
        if (!hasBox || maxRight <= minLeft || maxBottom <= minTop) return null
        lineHeights.sort()
        val medianHeight = if (lineHeights.isEmpty()) 0f else lineHeights[lineHeights.size / 2]
        val maxHeight = lineHeights.lastOrNull() ?: 0f
        val edgeMarginX = frameWidth * 0.03f
        val edgeMarginY = frameHeight * 0.03f
        return PackageVisionStats(
            hasAnyText = true,
            lineCount = lineHeights.size,
            centerXRatio = ((minLeft + maxRight) / 2f) / frameWidth.toFloat(),
            centerYRatio = ((minTop + maxBottom) / 2f) / frameHeight.toFloat(),
            occupiedWidthRatio = (maxRight - minLeft).toFloat() / frameWidth.toFloat(),
            occupiedHeightRatio = (maxBottom - minTop).toFloat() / frameHeight.toFloat(),
            clippedEdge = minLeft <= edgeMarginX || minTop <= edgeMarginY ||
                maxRight >= frameWidth - edgeMarginX || maxBottom >= frameHeight - edgeMarginY,
            medianLineHeightRatio = medianHeight,
            maxLineHeightRatio = maxHeight,
        )
    }


    companion object {
        private const val TAG = "OcrEngine"
    }

    private var isInitialized = false
    @Volatile private var closed = false
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
        // ML Kit owns a copy until its Task completes, including after timeout/cancellation.
        return recognizeText(bitmap.copy(Bitmap.Config.ARGB_8888, false), recycleOnCompletion = true)
    }

    private suspend fun recognizeText(bitmap: Bitmap, recycleOnCompletion: Boolean): String {
        return suspendCancellableCoroutine { continuation ->
            val activeRecognizer = getOrCreateRecognizer()
            val image = InputImage.fromBitmap(bitmap, 0)

            val task = try { activeRecognizer.process(image) } catch (error: Exception) {
                if (recycleOnCompletion) bitmap.recycle()
                if (continuation.isActive) continuation.resume("")
                return@suspendCancellableCoroutine
            }
            task.addOnCompleteListener {
                // ML Kit may still be using the bitmap after coroutine cancellation.
                if (recycleOnCompletion && !bitmap.isRecycled) bitmap.recycle()
            }
            task
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
            try { recognizeTextFromBitmap(bitmap) } finally { bitmap.recycle() }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao converter imagem para OCR", e)
            ""
        }
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String {
        val focusedText = recognizeFocusedPackageText(bitmap)
        if (isLikelyReadablePackageText(focusedText) && hasDosageCandidate(focusedText)) {
            return focusedText
        }
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
            recognizeScaledPackage(cropped, maxDimension = 2800, highContrast = false)
        } finally {
            cropped.recycle()
        }
    }

    suspend fun recognizeFullPackageText(bitmap: Bitmap): String {
        // Fallback para embalagens fora do centro ou com tipografia muito grande na frente.
        val fullText = recognizeScaledPackage(bitmap, maxDimension = 2200, highContrast = false)
        if (hasDosageCandidate(fullText)) return fullText
        val largePrintText = recognizeLargePrintPackageText(bitmap)
        return mergeRecognizedLines(listOf(fullText, largePrintText))
    }

    suspend fun recognizeLargePrintPackageText(bitmap: Bitmap): String {
        // Fontes gigantes na frente da caixa ficam grandes demais para o ML Kit em 2200-2800 px.
        val downscaled = recognizeScaledPackage(bitmap, maxDimension = 1024, highContrast = false)
        if (hasDosageCandidate(downscaled)) return downscaled
        val contrasted = recognizeScaledPackage(bitmap, maxDimension = 1024, highContrast = true)
        return mergeRecognizedLines(listOf(downscaled, contrasted))
    }

    suspend fun recognizeValidityText(bitmap: Bitmap): String {
        val normalPass = recognizeScaledPackage(bitmap, maxDimension = 3000, highContrast = false)
        if (ExpirationReader.findDates(normalPass).isNotEmpty()) return normalPass
        // Carimbo de validade claro/baixo contraste em abas brilhantes costuma responder melhor com contraste alto.
        val contrastPass = recognizeScaledPackage(bitmap, maxDimension = 2600, highContrast = true)
        return mergeRecognizedLines(listOf(normalPass, contrastPass))
    }

    private suspend fun recognizeScaledPackage(
        bitmap: Bitmap,
        maxDimension: Int,
        highContrast: Boolean,
    ): String {
        val prepared = scaleDownForOcr(bitmap, maxDimension = maxDimension)
        return try {
            if (highContrast) {
                val enhanced = enhanceForOcr(prepared)
                recognizeText(enhanced, recycleOnCompletion = true).trim()
            } else {
                recognizeText(prepared).trim()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Falha na passagem de OCR da embalagem (${maxDimension}px)", error)
            ""
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    private fun hasDosageCandidate(text: String): Boolean {
        val normalized = text.replace('\n', ' ')
        return Regex("""\b\d{1,5}(?:[.,]\d+)?\s*(?:MCG|MG|G|ML|UI)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
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
        onPartial: suspend (String) -> Unit = {},
    ): String {
        if (highContrast) return recognizeDocumentTiles(bitmap, onPartial)

        val prepared = scaleDownForOcr(bitmap, maxDimension = 3200)
        return try {
            recognizeText(prepared)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    private suspend fun recognizeDocumentTiles(bitmap: Bitmap, onPartial: suspend (String) -> Unit): String {
        val tiles = createOverlappingDocumentTiles(bitmap)
        val attempts = mutableListOf<String>()
        try {
            for (tile in tiles) {
                val prepared = scaleDownForOcr(tile, maxDimension = 2400)
                val enhanced = enhanceForOcr(prepared)
                try {
                    attempts += recognizeText(enhanced, recycleOnCompletion = true)
                    onPartial(mergeOverlappingDocumentTexts(attempts))
                } finally {
                    if (prepared !== tile) prepared.recycle()
                }
            }
        } finally {
            tiles.forEach { tile -> if (tile !== bitmap && !tile.isRecycled) tile.recycle() }
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

    @Synchronized
    fun close() {
        closed = true
        if (barcodeScannerDelegate.isInitialized()) barcodeScanner.close()
        recognizer?.close()
        recognizer = null
        isInitialized = false
    }
}
