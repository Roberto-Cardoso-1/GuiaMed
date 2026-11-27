package com.guiamed.ui

import com.guiamed.data.ReceitaResponse
import com.guiamed.data.VerificarResponse
import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")
private val MULTI_SPACE_REGEX = Regex("\\s+")

private fun normalizeMedicationKey(value: String): String =
    Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")
        .replace(MULTI_SPACE_REGEX, " ")

internal data class ReadingPresentation(
    val title: String,
    val details: String,
    val alert: String? = null,
    val spokenTitle: String? = null,
) {
    val speech: String
        get() = listOfNotNull(alert, spokenTitle ?: title, details)
            .flatMap { it.lineSequence().filter(String::isNotBlank).toList() }
            .joinToString(" ") { line ->
                val text = line.trim()
                if (text.last() in ".!?") text else "$text."
            }
}

internal fun VerificarResponse.toReadingPresentation(showMissingValidity: Boolean): ReadingPresentation {
    val alerts = buildList {
        if (alertaAlergia) add("Alerta de alergia")
        if (vencido) add("Medicamento vencido")
        if (isEmpty() && perigo) add("Atenção")
    }
    val remainingMessage = mensagem.orEmpty()
        .replace("Alerta de alergia detectado.", "")
        .replace("Medicamento vencido.", "")
        .replace("Validade não encontrada na imagem.", "")
        .replace("Dosagem não encontrada na imagem.", "")
        .trim()

    val details = buildList {
        add("Dosagem: ${miligramas ?: "não identificada"}")
        if (validade != null) add("Validade: $validade")
        else if (showMissingValidity) add("Validade não encontrada")
        posologia?.takeIf(String::isNotBlank)?.let { add("Posologia: $it") }
        if (remainingMessage.isNotBlank()) add(remainingMessage)
    }
    val spokenTitle = nome.trim()
        .takeIf { it.isNotBlank() && !it.startsWith("Medicamento não identificado", ignoreCase = true) }
        ?.let { "O remédio é $it" }
    return ReadingPresentation(
        title = nome,
        details = details.joinToString("\n"),
        alert = alerts.joinToString("\n").ifBlank { null },
        spokenTitle = spokenTitle,
    )
}

internal fun ReceitaResponse.toReadingPresentation(): ReadingPresentation {
    val alertKeys = medicamentosComAlerta
        .map(::normalizeMedicationKey)
        .filter(String::isNotBlank)
    val details = buildList {
        if (medicamentos.isNotEmpty()) {
            add(
                if (medicamentos.size == 1) "1 medicamento identificado na receita."
                else "${medicamentos.size} medicamentos identificados na receita."
            )
        }
        for (medicine in medicamentos) {
            val normalizedName = normalizeMedicationKey(medicine.nome)
            val isAllergic = normalizedName.isNotBlank() && alertKeys.any { alert ->
                normalizedName == alert || normalizedName.contains(alert) || alert.contains(normalizedName)
            }
            add(buildList {
                add(medicine.nome)
                if (isAllergic) {
                    add("Atenção: você cadastrou alergia compatível com ${medicine.nome}. Não utilize sem falar com seu médico.")
                }
                add(
                    if (medicine.dosagem.isBlank() || medicine.dosagem.trim().equals("dosagem não identificada", ignoreCase = true)) {
                        "Dosagem não identificada"
                    } else {
                        "Dosagem: ${medicine.dosagem}"
                    }
                )
                add(
                    if (medicine.frequencia.isBlank() || medicine.frequencia.trim().equals("frequência não identificada", ignoreCase = true)) {
                        "Frequência não identificada"
                    } else {
                        "Frequência: ${medicine.frequencia}"
                    }
                )
                medicine.duracao?.takeIf(String::isNotBlank)?.let { add("Duração do tratamento: $it") }
                medicine.instrucoes?.takeIf(String::isNotBlank)?.let { add("Instruções: $it") }
            }.joinToString("\n"))
        }
        if (horarios.isNotEmpty()) add("Horários identificados: ${horarios.joinToString(", ")}")
        mensagem
            ?.replace("Receita processada com sucesso.", "", ignoreCase = true)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }
    val alert = medicamentosComAlerta.takeIf(List<String>::isNotEmpty)
        ?.let { "Alerta de alergia: ${it.joinToString(", ")}" }
    return ReadingPresentation("Receita lida", details.joinToString("\n\n"), alert)
}
