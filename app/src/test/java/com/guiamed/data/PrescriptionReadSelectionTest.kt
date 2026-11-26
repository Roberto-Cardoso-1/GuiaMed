package com.guiamed.data

import org.junit.Assert.assertSame
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

    private fun response(dosage: String, frequency: String) = ReceitaResponse(
        medicamentos = listOf(MedicamentoReceita("Ibuprofeno", dosage, frequency, null, null)),
        posologiaCompleta = "",
        horarios = emptyList(),
        mensagem = null,
    )
}
