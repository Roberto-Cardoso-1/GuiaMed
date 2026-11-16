package com.guiamed.data

import android.content.Context

/** Mantém as alergias no armazenamento privado do aplicativo. */
internal object LocalAllergyStore {
    private const val preferencesName = "guiamed_offline"
    private const val allergiesKey = "allergies"

    fun getAll(context: Context): Set<String> {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        return preferences.getStringSet(allergiesKey, emptySet()).orEmpty().toSet()
    }

    fun add(context: Context, spokenName: String): Pair<String, List<String>> {
        val cleanedName = cleanSpokenName(spokenName)
        require(cleanedName.length >= 2) { "Não entendi o nome da alergia. Tente novamente." }

        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val allergies = preferences.getStringSet(allergiesKey, emptySet()).orEmpty().toMutableSet()
        allergies += cleanedName
        check(preferences.edit().putStringSet(allergiesKey, allergies).commit()) {
            "Não foi possível salvar a alergia no aparelho."
        }
        return cleanedName to allergies.sortedBy(String::lowercase)
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
}
