package com.guiamed.data

import java.util.Locale

internal fun String.isRecognizedPrescriptionField(): Boolean =
    isNotBlank() && !contains("não identificad", ignoreCase = true)

internal fun ReceitaResponse.needsPrescriptionReview(): Boolean =
    medicamentos.isEmpty() || medicamentos.any {
        !it.dosagem.isRecognizedPrescriptionField() || !it.frequencia.isRecognizedPrescriptionField()
    }

internal fun selectPrescriptionRead(
    original: ReceitaResponse?,
    enhanced: ReceitaResponse?,
): ReceitaResponse? {
    val selected = selectCompatiblePrescription(original, enhanced) ?: return null
    val alerts = (original?.medicamentosComAlerta.orEmpty() + enhanced?.medicamentosComAlerta.orEmpty()).distinct()
    val text = listOfNotNull(original?.textoOcrCompleto, enhanced?.textoOcrCompleto)
        .maxByOrNull(String::length)
    return if (selected.medicamentosComAlerta == alerts && selected.textoOcrCompleto == text) selected
    else selected.copy(medicamentosComAlerta = alerts, textoOcrCompleto = text)
}

private fun selectCompatiblePrescription(original: ReceitaResponse?, enhanced: ReceitaResponse?): ReceitaResponse? {
    if (original == null || original.medicamentos.isEmpty()) return enhanced ?: original
    if (enhanced == null) return original

    val originalMedicines = original.medicamentos.associateBy { it.nome.trim().lowercase(Locale.ROOT) }
    val enhancedMedicines = enhanced.medicamentos.associateBy { it.nome.trim().lowercase(Locale.ROOT) }
    // Never collapse repeated prescriptions for the same name into one dose.
    if (originalMedicines.size != original.medicamentos.size ||
        enhancedMedicines.size != enhanced.medicamentos.size
    ) return original

    val merged = originalMedicines.map { (name, first) ->
        val second = enhancedMedicines[name] ?: return@map first
        val fields = listOf(
            first.dosagem to second.dosagem,
            first.frequencia to second.frequencia,
            first.duracao.orEmpty() to second.duracao.orEmpty(),
            first.instrucoes.orEmpty() to second.instrucoes.orEmpty(),
        )
        if (fields.any { (before, after) ->
            before.isRecognizedPrescriptionField() && after.isRecognizedPrescriptionField() &&
                !before.equals(after, ignoreCase = true)
        }) return@map first
        fun fill(before: String?, after: String?): String? =
            if (before.orEmpty().isRecognizedPrescriptionField()) before else after ?: before
        first.copy(
            dosagem = fill(first.dosagem, second.dosagem).orEmpty(),
            frequencia = fill(first.frequencia, second.frequencia).orEmpty(),
            duracao = fill(first.duracao, second.duracao),
            instrucoes = fill(first.instrucoes, second.instrucoes),
        )
    } + enhancedMedicines.filterKeys { it !in originalMedicines }.values
    if (merged == original.medicamentos) return original
    if (merged == enhanced.medicamentos) return enhanced
    return original.copy(
        medicamentos = merged,
        posologiaCompleta = merged.joinToString(". ") {
            listOfNotNull(it.nome, it.dosagem, it.frequencia, it.duracao, it.instrucoes).joinToString(" ")
        },
        textoOcrCompleto = listOfNotNull(original.textoOcrCompleto, enhanced.textoOcrCompleto)
            .distinct().joinToString("\n").ifBlank { null },
    )
}
