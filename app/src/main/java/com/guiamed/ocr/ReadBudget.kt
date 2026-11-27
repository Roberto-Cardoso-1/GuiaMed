package com.guiamed.ocr

import kotlinx.coroutines.withTimeoutOrNull

/** One monotonic deadline shared by focus, capture, OCR and HTTP. */
internal class ReadBudget(
    totalMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val startedAt = nowMillis()
    private val duration = totalMillis.coerceAtLeast(0)
    val remainingMillis: Long
        get() = (duration - (nowMillis() - startedAt)).coerceAtLeast(0)

    fun allowance(maxMillis: Long, reserveMillis: Long = 0): Long =
        minOf(maxMillis, (remainingMillis - reserveMillis).coerceAtLeast(0))

    suspend fun <T> run(maxMillis: Long, reserveMillis: Long = 0, block: suspend () -> T): T? =
        withTimeoutOrNull(allowance(maxMillis, reserveMillis)) { block() }

    companion object {
        const val PACKAGE_MILLIS = 38_000L
        const val PRESCRIPTION_MILLIS = 24_000L
        const val HTTP_MILLIS = 5_000L
        const val MIN_HTTP_MILLIS = 2_500L
    }
}
