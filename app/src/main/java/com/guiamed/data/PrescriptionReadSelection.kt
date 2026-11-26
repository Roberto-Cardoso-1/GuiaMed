package com.guiamed.data

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
    if (original == null || original.medicamentos.isEmpty()) return enhanced ?: original
    if (enhanced == null) return original

    val originalMedicines = original.medicamentos.associateBy { it.nome.lowercase() }
    val enhancedMedicines = enhanced.medicamentos.associateBy { it.nome.lowercase() }
    if (originalMedicines.keys != enhancedMedicines.keys) return original

    var completedField = false
    for ((name, first) in originalMedicines) {
        val second = enhancedMedicines.getValue(name)
        for ((before, after) in listOf(first.dosagem to second.dosagem, first.frequencia to second.frequencia)) {
            if (before.isRecognizedPrescriptionField() && !before.equals(after, ignoreCase = true)) {
                return original
            }
            if (!before.isRecognizedPrescriptionField() && after.isRecognizedPrescriptionField()) {
                completedField = true
            }
        }
    }
    return if (completedField) enhanced else original
}
