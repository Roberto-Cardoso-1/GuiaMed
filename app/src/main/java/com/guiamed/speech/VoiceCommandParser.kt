package com.guiamed.speech

import java.text.Normalizer
import java.util.Locale

internal enum class VoiceCommandType {
    READ_PACKAGE,
    READ_PRESCRIPTION,
    REGISTER_ALLERGY,
    REPEAT_INSTRUCTIONS,
    CANCEL,
    UNKNOWN,
}

internal data class ParsedVoiceCommand(
    val type: VoiceCommandType,
    val argument: String? = null,
)

internal object VoiceCommandParser {
    fun parse(spokenText: String): ParsedVoiceCommand {
        val normalized = normalize(spokenText)
        if (normalized.isBlank()) return ParsedVoiceCommand(VoiceCommandType.UNKNOWN)

        if (containsAny(normalized, "cancelar", "cancele", "sair", "parar", "pare", "encerrar")) {
            return ParsedVoiceCommand(VoiceCommandType.CANCEL)
        }
        if (containsAny(normalized, "instrucoes", "repetir instrucoes", "ajuda", "o que posso fazer")) {
            return ParsedVoiceCommand(VoiceCommandType.REPEAT_INSTRUCTIONS)
        }
        if (containsAny(normalized, "alergia", "alergico", "alergica")) {
            return ParsedVoiceCommand(
                VoiceCommandType.REGISTER_ALLERGY,
                extractAllergyName(normalized),
            )
        }
        if (containsAny(normalized, "ler receita", "ler a receita", "leia a receita", "modo receita", "receita medica")) {
            return ParsedVoiceCommand(VoiceCommandType.READ_PRESCRIPTION)
        }
        if (containsAny(
                normalized,
                "ler embalagem",
                "ler a embalagem",
                "leia a embalagem",
                "ler medicamento",
                "ler o medicamento",
                "ler remedio",
                "ler o remedio",
                "identificar medicamento",
                "identificar remedio",
            )
        ) {
            return ParsedVoiceCommand(VoiceCommandType.READ_PACKAGE)
        }
        return ParsedVoiceCommand(VoiceCommandType.UNKNOWN)
    }

    private fun extractAllergyName(text: String): String? {
        val match = Regex(
            """(?:alergia|alergico|alergica)(?:\s+(?:a|ao|de))?\s+(.+)$""",
        ).find(text) ?: return null
        return match.groupValues[1]
            .removePrefix("cadastrar ")
            .removePrefix("registrar ")
            .trim()
            .takeIf { it.length >= 2 && it !in setOf("remedio", "medicamento") }
    }

    private fun containsAny(text: String, vararg expressions: String): Boolean =
        expressions.any { expression ->
            Regex("""(?:^|\s)${Regex.escape(expression)}(?:$|\s)""").containsMatchIn(text)
        }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.forLanguageTag("pt-BR"))
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
}
