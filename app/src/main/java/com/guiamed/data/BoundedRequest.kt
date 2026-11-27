package com.guiamed.data

import com.guiamed.ocr.ReadBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A single cancellable attempt; never start HTTP when its useful window is gone. */
internal suspend fun <T> boundedRequest(budget: ReadBudget, request: suspend () -> T): Result<T> {
    if (budget.remainingMillis < ReadBudget.MIN_HTTP_MILLIS) {
        return Result.failure(IOException("Tempo insuficiente para consultar o servidor."))
    }
    return withContext(Dispatchers.IO) {
        try {
            budget.run(ReadBudget.HTTP_MILLIS) { Result.success(request()) }
                ?: Result.failure(IOException("Tempo de consulta ao servidor esgotado."))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
