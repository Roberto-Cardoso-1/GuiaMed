package com.guiamed.data

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** Mantém as alergias no armazenamento privado do aplicativo. */
internal object LocalAllergyStore {
    private const val preferencesName = "guiamed_offline"
    private const val allergiesKey = "allergies"

    fun getAll(context: Context): Set<String> {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        return preferences.getStringSet(allergiesKey, emptySet()).orEmpty().toSet()
    }

    fun listSorted(context: Context): List<String> =
        getAll(context).sortedBy(String::lowercase)

    fun add(context: Context, spokenName: String): Pair<String, List<String>> {
        val cleanedName = cleanSpokenName(spokenName)
        require(cleanedName.length >= 2) { "Não entendi o nome da alergia. Tente novamente." }

        val targetKey = normalizeForMatch(cleanedName)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val allergies = preferences.getStringSet(allergiesKey, emptySet()).orEmpty()
            .filterNot { normalizeForMatch(it) == targetKey }
            .toMutableSet()
        allergies += cleanedName
        check(preferences.edit().putStringSet(allergiesKey, allergies).commit()) {
            "Não foi possível salvar a alergia no aparelho."
        }
        return cleanedName to allergies.sortedBy(String::lowercase)
    }

    fun findMatch(context: Context, spokenName: String): String? {
        val cleanedTarget = normalizeForMatch(cleanSpokenName(spokenName))
        if (cleanedTarget.length < 2) return null
        val allergies = getAll(context)
        return allergies.firstOrNull { normalizeForMatch(it) == cleanedTarget }
            ?: allergies.filter {
                val item = normalizeForMatch(it)
                item.contains(cleanedTarget) || cleanedTarget.contains(item)
            }.takeIf { it.size == 1 }?.single()
    }

    fun remove(context: Context, spokenName: String): Pair<String?, List<String>> {
        val match = findMatch(context, spokenName) ?: return null to listSorted(context)
        val matchKey = normalizeForMatch(match)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val allergies = preferences.getStringSet(allergiesKey, emptySet()).orEmpty()
            .filterNot { normalizeForMatch(it) == matchKey }
            .toSet()
        check(preferences.edit().putStringSet(allergiesKey, allergies).commit()) {
            "Não foi possível atualizar as alergias no aparelho."
        }
        return match to allergies.sortedBy(String::lowercase)
    }

    internal fun cleanSpokenName(value: String): String {
        var cleaned = value.trim().trim('.', ',', ';', ':', '!', '?')
        val prefixes = listOf(
            Regex("""(?i)^eu\s+(?:tenho|sou)\s+(?:alergia|al[eé]rgic[oa])\s*(?:a|ao|à)?\s*"""),
            Regex("""(?i)^(?:tenho|sou)\s+(?:alergia|al[eé]rgic[oa])\s*(?:a|ao|à)?\s*"""),
            Regex("""(?i)^(?:alergia|al[eé]rgic[oa])\s*(?:a|ao|à)?\s*"""),
        )
        prefixes.forEach { prefix -> cleaned = cleaned.replace(prefix, "") }
        return cleaned.trim().trim('.', ',', ';', ':', '!', '?')
    }

    private fun normalizeForMatch(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.forLanguageTag("pt-BR"))
            .replace(Regex("""\s+"""), " ")
            .trim()
}
