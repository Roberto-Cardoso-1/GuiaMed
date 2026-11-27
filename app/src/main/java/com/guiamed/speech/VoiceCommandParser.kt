package com.guiamed.speech

import java.text.Normalizer
import java.util.Locale

internal enum class VoiceCommandType {
    READ_PACKAGE,
    READ_PRESCRIPTION,
    REGISTER_ALLERGY,
    LIST_ALLERGIES,
    REMOVE_ALLERGY,
    REPEAT_LAST_READING,
    CREATE_REMINDER,
    REPEAT_INSTRUCTIONS,
    CANCEL,
    PAUSE_VOICE,
    UNKNOWN,
}

internal data class ParsedVoiceCommand(
    val type: VoiceCommandType,
    val argument: String? = null,
)

internal object VoiceCommandParser {

    private val partialEligibleTypes = setOf(
        VoiceCommandType.READ_PACKAGE,
        VoiceCommandType.READ_PRESCRIPTION,
        VoiceCommandType.LIST_ALLERGIES,
        VoiceCommandType.REPEAT_LAST_READING,
        VoiceCommandType.REPEAT_INSTRUCTIONS,
        VoiceCommandType.CANCEL,
        VoiceCommandType.PAUSE_VOICE,
    )

    fun parse(spokenText: String, strictStandby: Boolean = false): ParsedVoiceCommand {
        val normalized = normalize(spokenText)
        if (normalized.isBlank()) return ParsedVoiceCommand(VoiceCommandType.UNKNOWN)

        if (containsAny(
                normalized,
                "pausar voz",
                "silenciar",
                "silenciar voz",
                "encerrar comandos",
                "desligar voz",
                "parar de ouvir",
            )
        ) {
            return ParsedVoiceCommand(VoiceCommandType.PAUSE_VOICE)
        }

        if (!strictStandby && containsAny(normalized, "cancelar", "cancele", "sair", "parar", "pare", "encerrar", "voltar")) {
            return ParsedVoiceCommand(VoiceCommandType.CANCEL)
        }

        // Checar instruções antes de repetir leitura para que "repetir instruções" não caia em REPEAT_LAST_READING.
        if (containsAny(
                normalized,
                "repetir instrucoes",
                "repetir as instrucoes",
                "instrucoes",
                "comandos",
                "funcoes",
            ) || (!strictStandby && containsAny(normalized, "ajuda", "o que posso fazer"))
        ) {
            return ParsedVoiceCommand(VoiceCommandType.REPEAT_INSTRUCTIONS)
        }

        if (containsAny(
                normalized,
                "repetir leitura",
                "repetir a leitura",
                "repetir ultima leitura",
                "repetir a ultima leitura",
                "ultima leitura",
                "ler novamente",
                "repetir resultado",
                "falar de novo",
                "ouvir de novo",
            )
        ) {
            return ParsedVoiceCommand(VoiceCommandType.REPEAT_LAST_READING)
        }

        if (containsAny(
                normalized,
                "criar lembrete",
                "criar lembretes",
                "agendar lembrete",
                "criar alarme",
                "agendar alarme",
            ) || (!strictStandby && containsAny(normalized, "lembrete", "lembretes", "alarme", "alarmes", "despertador"))
        ) {
            return ParsedVoiceCommand(VoiceCommandType.CREATE_REMINDER, spokenText.trim())
        }

        if (containsAny(
                normalized,
                "minhas alergias",
                "listar alergias",
                "listar a alergia",
                "listar alergia",
                "ver alergias",
                "quais alergias",
                "consultar alergias",
                "alergias cadastradas",
                "alergias registradas",
            )
        ) {
            return ParsedVoiceCommand(VoiceCommandType.LIST_ALLERGIES)
        }

        if (Regex("""\b(?:remover|apagar|excluir|deletar|tirar)\s+(?:a\s+|as\s+|o\s+)?alergias?\b""").containsMatchIn(normalized)) {
            return ParsedVoiceCommand(
                VoiceCommandType.REMOVE_ALLERGY,
                extractAllergyTarget(normalized),
            )
        }

        val explicitAllergyRegister = containsAny(
            normalized,
            "cadastrar alergia",
            "registrar alergia",
            "adicionar alergia",
            "nova alergia",
            "tenho alergia",
            "sou alergico",
            "sou alergica",
        )
        if (explicitAllergyRegister || containsAny(normalized, "alergia", "alergico", "alergica")) {
            return ParsedVoiceCommand(
                VoiceCommandType.REGISTER_ALLERGY,
                extractAllergyName(normalized),
            )
        }

        if (containsAny(normalized, "ler receita", "ler a receita", "leia a receita", "modo receita", "receita medica", "receita")) {
            return ParsedVoiceCommand(VoiceCommandType.READ_PRESCRIPTION)
        }

        if (containsAny(
                normalized,
                "ler embalagem",
                "ler a embalagem",
                "leia a embalagem",
                "ler a caixa",
                "ler caixa",
                "ler outra caixa",
                "outra caixa",
                "embalagem",
                "ler medicamento",
                "ler o medicamento",
                "ler outro medicamento",
                "outro medicamento",
                "ler remedio",
                "ler o remedio",
                "ler outro remedio",
                "outro remedio",
                "identificar medicamento",
                "identificar remedio",
            )
        ) {
            return ParsedVoiceCommand(VoiceCommandType.READ_PACKAGE)
        }

        return ParsedVoiceCommand(VoiceCommandType.UNKNOWN)
    }

    /**
     * Retorna comando apenas quando o parcial é inambíguo e sem argumento pendente
     * (evita cortar frases como "cadastrar alergia a ..." ou "criar lembrete às ...").
     */
    fun canDispatchFromPartial(spokenText: String, strictStandby: Boolean = false): ParsedVoiceCommand? {
        val normalized = normalize(spokenText)
        if (normalized.isBlank()) return null
        val lastToken = normalized.substringAfterLast(' ')
        if (lastToken in setOf("ler", "repetir", "alergia", "minhas", "criar", "remover", "apagar", "parar", "encerrar", "para", "de", "a", "o")) {
            return null
        }
        val parsed = parse(normalized, strictStandby = strictStandby)
        return parsed.takeIf { it.type in partialEligibleTypes }
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

    private fun extractAllergyTarget(text: String): String? {
        val match = Regex(
            """(?:remover|apagar|excluir|deletar|tirar)\s+(?:a\s+|as\s+|o\s+)?alergias?(?:\s+(?:a|ao|de))?\s+(.+)$""",
        ).find(text) ?: return null
        return match.groupValues[1]
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
