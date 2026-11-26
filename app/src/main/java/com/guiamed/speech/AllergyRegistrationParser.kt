package com.guiamed.speech

import java.text.Normalizer
import java.util.Locale

internal enum class AllergyRegistrationInputType {
    MEDICATION_NAME,
    CAMERA,
    CONFIRM,
    CANCEL,
    UNKNOWN,
}

internal data class AllergyRegistrationInput(
    val type: AllergyRegistrationInputType,
    val medicationName: String? = null,
)

internal object AllergyRegistrationParser {
    fun parse(spokenText: String): AllergyRegistrationInput {
        val normalized = normalize(spokenText)
        if (normalized.isBlank()) return AllergyRegistrationInput(AllergyRegistrationInputType.UNKNOWN)

        if (containsAny(normalized, "cancelar", "cancela", "nao", "sair", "parar")) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.CANCEL)
        }
        if (containsAny(normalized, "confirmar", "confirmo", "sim", "correto", "esta certo")) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.CONFIRM)
        }
        if (containsAny(
                normalized,
                "camera",
                "usar camera",
                "pela camera",
                "escanear",
                "ler medicamento",
                "ler embalagem",
            )
        ) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.CAMERA)
        }

        val name = spokenText.trim().takeIf { it.length >= 2 }
        return if (name != null) {
            AllergyRegistrationInput(AllergyRegistrationInputType.MEDICATION_NAME, name)
        } else {
            AllergyRegistrationInput(AllergyRegistrationInputType.UNKNOWN)
        }
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
