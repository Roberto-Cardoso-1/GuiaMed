package com.guiamed.data

/** Remote additions require a catalog identity present in the actual OCR. */
internal fun reconcileRemotePrescription(
    local: ReceitaResponse,
    remote: ReceitaResponse,
    processor: OfflineMedicationProcessor,
    allergies: Collection<String>,
): ReceitaResponse {
    val text = local.textoOcrCompleto.orEmpty()
    // Re-extract fields exclusively from the corroborated item's OCR block. Remote
    // dose/frequency/duration/instructions are not evidence, even for a known name.
    val supported = remote.medicamentos.mapNotNull { processor.prescriptionEvidence(it.nome, text) }
    val checked = remote.copy(
        medicamentos = supported,
        horarios = remote.horarios.filter { it in text },
        textoOcrCompleto = text,
        medicamentosComAlerta = processor.recipeAllergyAlerts(supported, allergies),
    )
    val selected = selectPrescriptionRead(local, checked) ?: local
    return selected.copy(
        textoOcrCompleto = text,
        medicamentosComAlerta = (selected.medicamentosComAlerta +
            processor.recipeAllergyAlerts(selected.medicamentos, allergies)).distinct(),
    )
}
