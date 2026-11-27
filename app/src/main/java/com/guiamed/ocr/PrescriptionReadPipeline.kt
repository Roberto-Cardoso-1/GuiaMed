package com.guiamed.ocr

import com.guiamed.data.ReceitaResponse
import com.guiamed.data.needsPrescriptionReview
import com.guiamed.data.selectPrescriptionRead

/** Persist every completed pass/tile before starting optional work. */
internal suspend fun readPrescriptionWithinBudget(
    budget: ReadBudget,
    remoteReserveMillis: Long,
    original: suspend () -> ReceitaResponse?,
    enhanced: suspend (publish: (ReceitaResponse) -> Unit) -> ReceitaResponse?,
    remote: suspend (ReceitaResponse) -> ReceitaResponse,
    needsRemote: (ReceitaResponse) -> Boolean = { it.needsPrescriptionReview() },
): ReceitaResponse? {
    var best: ReceitaResponse? = null
    var incomplete = false
    fun publish(candidate: ReceitaResponse) {
        best = selectPrescriptionRead(best, candidate)
    }

    val finished = budget.run(budget.remainingMillis) {
        val first = budget.run(8_000, remoteReserveMillis) { original() }
        if (first != null) publish(first) else incomplete = true
        if (best?.medicamentosComAlerta?.isNotEmpty() == true) return@run true
        if (best == null || best!!.needsPrescriptionReview()) {
            val second = budget.run(6_000, remoteReserveMillis) { enhanced(::publish) }
            if (second != null) publish(second) else incomplete = true
        }
        val local = best
        if (local != null && local.medicamentosComAlerta.isEmpty() && needsRemote(local)) {
            // Keep fallback diagnostics even when the medication fields did not change.
            val complemented = remote(local)
            publish(complemented)
            best = best?.copy(mensagem = complemented.mensagem ?: best?.mensagem)
        }
        true
    }
    val result = best ?: return null
    return if (finished == null || incomplete || result.needsPrescriptionReview()) {
        result.copy(mensagem = listOfNotNull(
            result.mensagem,
            "Leitura parcial. Confira os campos não identificados; para ler novamente, dê dois toques.",
        ).distinct().joinToString(" "))
    } else result
}
