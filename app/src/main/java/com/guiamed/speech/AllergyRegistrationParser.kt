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
    private val ignoredGenericWords = setOf(
        "alergia",
        "alergias",
        "alergico",
        "alergica",
        "cadastrar",
        "registrar",
        "medicamento",
        "remedio",
        "sim",
        "ok",
        "oi",
        "ola",
        "teste",
    )

    private val leadingPrefixes = listOf(
        "eu tenho alergia a ",
        "eu tenho alergia ao ",
        "eu tenho alergia de ",
        "tenho alergia a ",
        "tenho alergia ao ",
        "tenho alergia de ",
        "sou alergico a ",
        "sou alergica a ",
        "alergia a ",
        "alergia ao ",
        "alergia de ",
        "cadastrar alergia a ",
        "cadastrar alergia ",
        "registrar alergia a ",
        "registrar alergia ",
        "cadastrar ",
        "registrar ",
        "o medicamento ",
        "medicamento ",
        "o remedio ",
        "remedio ",
    )

    fun parse(spokenText: String): AllergyRegistrationInput {
        val normalized = normalize(spokenText)
        if (normalized.isBlank()) return AllergyRegistrationInput(AllergyRegistrationInputType.UNKNOWN)

        if (containsAny(normalized, "cancelar", "cancela", "nao", "sair", "parar", "errado", "nao e esse")) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.CANCEL)
        }
        if (containsAny(
                normalized,
                "camera",
                "camara",
                "usar camera",
                "usar a camera",
                "abrir camera",
                "abrir a camera",
                "pela camera",
                "com a camera",
                "ler pela camera",
                "apontar camera",
                "apontar a camera",
                "foto",
                "tirar foto",
                "bater foto",
                "fotografar",
                "escanear",
                "escaneia",
                "embalagem",
                "ler embalagem",
                "ler a embalagem",
                "mostrar embalagem",
                "caixa",
                "ler caixa",
                "ler a caixa",
                "mostrar caixa",
                "ler medicamento",
                "ler o medicamento",
                "ler remedio",
                "ler o remedio",
            )
        ) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.CAMERA)
        }
        if (containsAny(normalized, "confirmar", "confirmo", "confirma", "sim", "correto", "esta certo", "ta certo", "pode salvar", "pode confirmar", "isso")) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.CONFIRM)
        }

        val cleaned = stripLeadingPrefixes(normalized)
        if (cleaned.length < 2 || cleaned in ignoredGenericWords) {
            return AllergyRegistrationInput(AllergyRegistrationInputType.UNKNOWN)
        }
        return AllergyRegistrationInput(AllergyRegistrationInputType.MEDICATION_NAME, cleaned)
    }

    private fun stripLeadingPrefixes(normalizedText: String): String {
        var result = normalizedText
        var changed = true
        while (changed) {
            changed = false
            for (prefix in leadingPrefixes) {
                if (result.startsWith(prefix)) {
                    result = result.removePrefix(prefix).trim()
                    changed = true
                }
            }
        }
        return result
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
