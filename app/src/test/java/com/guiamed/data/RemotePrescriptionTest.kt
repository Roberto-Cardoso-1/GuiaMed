package com.guiamed.data

import org.junit.Assert.*
import org.junit.Test

class RemotePrescriptionTest {
    private val processor = OfflineMedicationProcessor(emptyList())
    private fun medicine(name: String, dose: String = "500mg", frequency: String = "a cada 8 horas") =
        MedicamentoReceita(name, dose, frequency, null, null)
    private fun response(vararg items: MedicamentoReceita, text: String? = null) =
        ReceitaResponse(items.toList(), "", emptyList(), null, textoOcrCompleto = text)

    @Test fun remoteCompletesAnotherDrugButCannotReplaceKnownDose() {
        val local = response(medicine("Ibuprofeno", "600mg"),
            text = "IBUPROFENO 600 MG a cada 8 horas\nAMOXI CILINA 500 MG a cada 12 horas")
        val remote = response(medicine("Ibuprofeno", "800mg"), medicine("Amoxicilina"))
        val result = reconcileRemotePrescription(local, remote, processor, listOf("penicilina"))
        assertEquals(2, result.medicamentos.size)
        assertEquals("600mg", result.medicamentos.first().dosagem)
        assertEquals("500mg", result.medicamentos.last().dosagem)
        assertTrue(result.medicamentosComAlerta.contains("Amoxicilina"))
    }
    @Test fun rejectsUnsupportedRemoteDrugEvenWhenCatalogKnowsIt() {
        val local = response(medicine("Ibuprofeno"), text = "IBUPROFENO 500 MG")
        val result = reconcileRemotePrescription(local, response(medicine("Amoxicilina")), processor, emptyList())
        assertEquals(local.medicamentos, result.medicamentos)
    }
    @Test fun remoteCannotInventDoseFrequencyDurationOrInstructions() {
        val local = response(text = "AMOXI CILINA")
        val remote = response(medicine("Amoxicilina").copy(duracao = "7 dias", instrucoes = "2 comprimidos"))
        val item = reconcileRemotePrescription(local, remote, processor, emptyList()).medicamentos.single()
        assertFalse(item.dosagem.isRecognizedPrescriptionField())
        assertFalse(item.frequencia.isRecognizedPrescriptionField())
        assertNull(item.duracao)
        assertNull(item.instrucoes)
    }
    @Test fun doseAndFrequencyFromNeighborCannotFillRemoteItem() {
        val local = response(text = "AMOXI CILINA\nIBUPROFENO 600 MG a cada 8 horas")
        val result = reconcileRemotePrescription(local, response(medicine("Amoxicilina", "600mg")), processor, emptyList())
        assertFalse(result.medicamentos.single().dosagem.isRecognizedPrescriptionField())
        assertFalse(result.medicamentos.single().frequencia.isRecognizedPrescriptionField())
    }
    @Test fun completeLocalDrugDoesNotHideAnotherCandidateLine() {
        // A space alone is already recovered locally. Use an actual unread name
        // so this exercises the additional candidate instead of a complete read.
        val text = "IBUPROFENO 600 MG a cada 8 horas\nAMXXICILINA 500 MG a cada 12 horas"
        val local = processor.parseRecipe(text)
        assertEquals(listOf("Ibuprofeno"), local.medicamentos.map { it.nome })
        assertFalse(local.needsPrescriptionReview())
        assertTrue(processor.hasUnrecognizedPrescriptionLine(text, local))
        val complete = "IBUPROFENO 600 MG a cada 8 horas"
        assertFalse(processor.hasUnrecognizedPrescriptionLine(complete, processor.parseRecipe(complete)))
    }
}
