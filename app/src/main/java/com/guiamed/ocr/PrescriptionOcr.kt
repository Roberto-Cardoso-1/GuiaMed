package com.guiamed.ocr

import android.content.Context
import android.graphics.Bitmap
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.ReceitaResponse

internal suspend fun MlKitOcrEngine.readPrescription(
    context: Context,
    repository: GuiaMedRepository,
    bitmap: Bitmap,
    budget: ReadBudget = ReadBudget(ReadBudget.PRESCRIPTION_MILLIS),
): ReceitaResponse? = readPrescriptionWithinBudget(
    budget = budget,
    remoteReserveMillis = repository.remoteReserveMillis,
    original = {
        recognizeDocumentTextFromBitmap(bitmap).takeIf(String::isNotBlank)
            ?.let { repository.lerReceitaLocal(context, it) }
    },
    enhanced = { publish ->
        val text = recognizeDocumentTextFromBitmap(bitmap, highContrast = true) { partial ->
            if (partial.isNotBlank()) publish(repository.lerReceitaLocal(context, partial))
        }
        text.takeIf(String::isNotBlank)?.let { repository.lerReceitaLocal(context, it) }
    },
    remote = { repository.complementarReceita(context, it, budget) },
    needsRemote = { repository.shouldComplementPrescription(context, it) },
)
