package com.guiamed.ui

import com.guiamed.data.ReceitaResponse
import com.guiamed.data.VerificarResponse

internal data class ReadingPresentation(
    val title: String,
    val details: String,
    val alert: String? = null,
) {
    val speech: String
        get() = listOfNotNull(alert, title, details)
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
    return ReadingPresentation(nome, details.joinToString("\n"), alerts.joinToString("\n").ifBlank { null })
}

internal fun ReceitaResponse.toReadingPresentation(): ReadingPresentation {
    val details = buildList {
        for (medicine in medicamentos) {
            add(buildList {
                add(medicine.nome)
                add(if (medicine.dosagem.isBlank() || medicine.dosagem == "dosagem não identificada")
                    "Dosagem não identificada" else "Dosagem: ${medicine.dosagem}")
                add(if (medicine.frequencia.isBlank() || medicine.frequencia == "frequência não identificada")
                    "Frequência não identificada" else "Frequência: ${medicine.frequencia}")
                medicine.duracao?.takeIf(String::isNotBlank)?.let { add("Duração: $it") }
                medicine.instrucoes?.takeIf(String::isNotBlank)?.let { add("Instruções: $it") }
            }.joinToString("\n"))
        }
        if (horarios.isNotEmpty()) add("Horários: ${horarios.joinToString(", ")}")
        mensagem
            ?.takeIf { it.isNotBlank() && !it.contains("processada com sucesso", ignoreCase = true) }
            ?.let(::add)
        textoOcrCompleto?.trim()?.takeIf(String::isNotBlank)?.let { fullText ->
            add("Texto completo reconhecido:\n$fullText")
        }
    }
    val alert = medicamentosComAlerta.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = "Alerta de alergia. ")
    return ReadingPresentation("Receita lida", details.joinToString("\n\n"), alert)
}
