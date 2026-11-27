package com.guiamed.data

import java.text.Normalizer
import java.util.Locale

internal data class PrescriptionAlarm(
    val hour: Int,
    val minute: Int,
    val label: String,
) {
    fun formattedTime(): String = String.format(Locale.US, "%02d:%02d", hour, minute)
}

internal object PrescriptionScheduleParser {
    fun fromPrescription(response: ReceitaResponse): List<PrescriptionAlarm> {
        val medicineSummary = response.medicamentos
            .joinToString(", ") { it.nome }
            .ifBlank { "Medicamento da receita" }
        val label = "GuiaMed: $medicineSummary"

        val explicit = response.horarios.mapNotNull { time ->
            parseTimeToken(time)?.let { (h, m) -> PrescriptionAlarm(h, m, label) }
        }
        if (explicit.isNotEmpty()) {
            return explicit.distinctBy { it.hour to it.minute }
        }

        val medicinesByTime = linkedMapOf<Pair<Int, Int>, MutableSet<String>>()
        for (medicine in response.medicamentos) {
            for (slot in hoursFromFrequency(medicine.frequencia)) {
                medicinesByTime.getOrPut(slot) { linkedSetOf() }.add(medicine.nome)
            }
        }
        return medicinesByTime.map { (slot, names) ->
            PrescriptionAlarm(slot.first, slot.second, "GuiaMed: ${names.joinToString(", ")}")
        }
    }

    fun fromSpokenTime(spokenText: String, label: String = "GuiaMed: Lembrete de medicamento"): PrescriptionAlarm? {
        val normalized = Normalizer.normalize(spokenText, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.forLanguageTag("pt-BR"))

        Regex("""\b(\d{1,2})[:h](\d{2})\b""").find(normalized)?.let { match ->
            val h = match.groupValues[1].toIntOrNull()
            val m = match.groupValues[2].toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) {
                return PrescriptionAlarm(h, m, label)
            }
        }

        Regex("""\b(\d{1,2})\s+e\s+(\d{1,2})\b""").find(normalized)?.let { match ->
            val h = match.groupValues[1].toIntOrNull()
            val m = match.groupValues[2].toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) {
                return PrescriptionAlarm(h, m, label)
            }
        }

        Regex("""\b(\d{1,2})\s*(?:horas?|h)\b""").find(normalized)?.let { match ->
            val h = match.groupValues[1].toIntOrNull()
            if (h != null && h in 0..23) {
                return PrescriptionAlarm(h, 0, label)
            }
        }
        return null
    }

    private fun parseTimeToken(value: String): Pair<Int, Int>? {
        val match = Regex("""^(\d{1,2}):(\d{2})$""").find(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    private fun hoursFromFrequency(frequency: String): List<Pair<Int, Int>> {
        val normalized = Normalizer.normalize(frequency, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.forLanguageTag("pt-BR"))
        return when {
            Regex("""\b(?:6\s*em\s*6|a cada\s*6|6\s*/\s*6(?:\s*h(?:oras?)?)?|\b4\s*(?:x|vez(?:es)?)|quatro\s+vezes)\b""").containsMatchIn(normalized) ->
                listOf(6 to 0, 12 to 0, 18 to 0, 0 to 0)
            Regex("""\b(?:8\s*em\s*8|a cada\s*8|8\s*/\s*8(?:\s*h(?:oras?)?)?|3\s*(?:x|vez(?:es)?)|tres\s+vezes)\b""").containsMatchIn(normalized) ->
                listOf(8 to 0, 16 to 0, 0 to 0)
            Regex("""\b(?:12\s*em\s*12|a cada\s*12|12\s*/\s*12(?:\s*h(?:oras?)?)?|2\s*(?:x|vez(?:es)?)|duas\s+vezes)\b""").containsMatchIn(normalized) ->
                listOf(8 to 0, 20 to 0)
            Regex("""\b(?:a noite|de noite|ao deitar|antes de dormir)\b""").containsMatchIn(normalized) ->
                listOf(22 to 0)
            Regex("""\b(?:de tarde|a tarde)\b""").containsMatchIn(normalized) ->
                listOf(14 to 0)
            Regex("""\b(?:1\s*(?:x|vez)|uma vez|ao dia|por dia|/dia|todos os dias|diariamente|de manha|pela manha)\b""").containsMatchIn(normalized) ->
                listOf(8 to 0)
            else -> emptyList()
        }
    }
}
