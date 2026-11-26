package com.guiamed.ocr

import android.content.Context
import android.graphics.Bitmap
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.ReceitaResponse
import com.guiamed.data.needsPrescriptionReview
import com.guiamed.data.selectPrescriptionRead
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun MlKitOcrEngine.readPrescription(
    context: Context,
    repository: GuiaMedRepository,
    bitmap: Bitmap,
): ReceitaResponse? {
    val original = withTimeoutOrNull(8_000) {
        recognizeDocumentTextFromBitmap(bitmap)
    }.orEmpty()
    val first = original.takeIf(String::isNotBlank)?.let { repository.lerReceitaLocal(context, it) }
    if (first != null && (first.medicamentosComAlerta.isNotEmpty() || !first.needsPrescriptionReview())) {
        return first
    }

    val second = withTimeoutOrNull(7_000) {
        val enhanced = recognizeDocumentTextFromBitmap(bitmap, highContrast = true)
        enhanced.takeIf { it.isNotBlank() && it != original }?.let {
            repository.lerReceitaLocal(context, it)
        }
    }
    val local = selectPrescriptionRead(first, second) ?: return null
    val alerts = second?.medicamentosComAlerta.orEmpty()
    if (alerts.isNotEmpty()) return local.copy(medicamentosComAlerta = alerts)
    return withTimeoutOrNull(2_000) { repository.complementarReceita(context, local) } ?: local
}
