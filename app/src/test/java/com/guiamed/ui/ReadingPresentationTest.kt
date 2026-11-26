package com.guiamed.ui

import com.guiamed.data.MedicamentoReceita
import com.guiamed.data.ReceitaResponse
import com.guiamed.data.VerificarResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPresentationTest {
    @Test
    fun showsEveryPrescriptionItemAndMissingFrequency() {
        val reading = ReceitaResponse(
            medicamentos = listOf(
                MedicamentoReceita("Amostra A", "10 mg", "conforme a receita", "5 dias", "uso externo"),
                MedicamentoReceita("Amostra B", "20 mg", "frequência não identificada", null, null),
            ),
            posologiaCompleta = "",
            horarios = listOf("08:00"),
            mensagem = "Confirme os dados na receita original.",
        ).toReadingPresentation()

        assertEquals("Receita lida", reading.title)
        assertTrue(reading.details.contains("\n\nAmostra B\nDosagem: 20 mg\nFrequência não identificada"))
        for (fact in listOf("Amostra A", "10 mg", "5 dias", "uso externo", "08:00", "Confirme os dados")) {
            assertTrue("Campo ausente na tela: $fact", reading.details.contains(fact))
            assertTrue("Campo ausente na voz: $fact", reading.speech.contains(fact))
        }
    }

    @Test
    fun showsAndSpeaksAllCriticalAlerts() {
        val reading = VerificarResponse(
            nome = "Amostra",
            miligramas = "10 mg",
            validade = "10/2026",
            vencido = true,
            alertaAlergia = true,
            posologia = null,
            mensagem = "Medicamento vencido. Alerta de alergia detectado.",
            perigo = true,
        ).toReadingPresentation(showMissingValidity = true)

        assertEquals("Alerta de alergia\nMedicamento vencido", reading.alert)
        assertTrue(reading.speech.startsWith("Alerta de alergia. Medicamento vencido."))
        assertTrue(reading.details.contains("Validade: 10/2026"))
        assertFalse(reading.details.contains("Medicamento vencido."))
    }

    @Test
    fun showsAndSpeaksCompleteRecognizedPrescriptionText() {
        val completeText = "Receituário Médico\nPaciente: José\nENALAPRIL 5MG\nUSO CONTÍNUO"
        val reading = ReceitaResponse(
            medicamentos = listOf(
                MedicamentoReceita("Enalapril", "5mg", "de manhã", null, "via oral"),
            ),
            posologiaCompleta = "",
            horarios = emptyList(),
            mensagem = "Receita processada com sucesso.",
            textoOcrCompleto = completeText,
        ).toReadingPresentation()

        assertTrue(reading.details.contains(completeText))
        assertTrue(reading.speech.contains("Receituário Médico"))
        assertTrue(reading.speech.contains("Paciente: José"))
        assertTrue(reading.speech.contains("USO CONTÍNUO"))
    }
}
