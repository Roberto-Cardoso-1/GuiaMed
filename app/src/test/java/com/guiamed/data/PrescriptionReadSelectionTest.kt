package com.guiamed.data

import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionReadSelectionTest {
    @Test
    fun acceptsContrastPassWhenItCompletesFrequency() {
        val original = response("600mg", "frequência não identificada")
        val enhanced = response("600mg", "de 8 em 8 horas")
        assertSame(enhanced, selectPrescriptionRead(original, enhanced))
    }

    @Test
    fun keepsOriginalWhenContrastPassChangesRecognizedDosage() {
        val original = response("600mg", "frequência não identificada")
        val enhanced = response("800mg", "de 8 em 8 horas")
        assertSame(original, selectPrescriptionRead(original, enhanced))
    }

    @Test
    fun keepsPartialResultIfContrastPassTimesOut() {
        val original = response("600mg", "frequência não identificada")
        assertSame(original, selectPrescriptionRead(original, null))
    }

    @Test fun discardedConflictingPassCannotEraseItsAllergyAlert() {
        val original = response("600mg", "frequência não identificada")
        val conflicting = response("800mg", "8 horas").copy(medicamentosComAlerta = listOf("Ibuprofeno"))
        val result = selectPrescriptionRead(original, conflicting)!!
        assertEquals(original.medicamentos, result.medicamentos)
        assertTrue(result.medicamentosComAlerta.contains("Ibuprofeno"))
    }
    @Test fun laterTileAddsMedicationWithoutDroppingEarlierTile() {
        val first = response("600mg", "8 horas")
        val second = first.copy(medicamentos = listOf(MedicamentoReceita("Amoxicilina", "500mg", "12 horas", null, null)))
        val result = selectPrescriptionRead(first, second)!!
        assertEquals(listOf("Ibuprofeno", "Amoxicilina"), result.medicamentos.map { it.nome })
        assertEquals("600mg", result.medicamentos.first().dosagem)
    }
    @Test fun repeatedNamesCannotCollapseDifferentDoses() {
        val original = response("600mg", "8 horas")
        val duplicate = original.copy(medicamentos = original.medicamentos + original.medicamentos.single().copy(dosagem = "800mg"))
        assertSame(original, selectPrescriptionRead(original, duplicate))
    }

    private fun response(dosage: String, frequency: String) = ReceitaResponse(
        medicamentos = listOf(MedicamentoReceita("Ibuprofeno", dosage, frequency, null, null)),
        posologiaCompleta = "",
        horarios = emptyList(),
        mensagem = null,
    )
}
