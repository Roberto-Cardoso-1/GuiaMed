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
    fun showsAndSpeaksAllCriticalAlertsWithMedicineAnnouncement() {
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

        assertEquals("Amostra", reading.title)
        assertEquals("Alerta de alergia\nMedicamento vencido", reading.alert)
        assertTrue(reading.speech.startsWith("Alerta de alergia. Medicamento vencido. O remédio é Amostra."))
        assertTrue(reading.details.contains("Validade: 10/2026"))
        assertFalse(reading.details.contains("Medicamento vencido."))
    }

    @Test
    fun speaksNaturalMedicineAnnouncementForIdentifiedPackageAndNotForUnidentified() {
        val identified = VerificarResponse(
            nome = "Dipirona Sódica",
            miligramas = "500 mg",
            validade = "12/2027",
            vencido = false,
            alertaAlergia = false,
            posologia = null,
            mensagem = null,
            perigo = false,
        ).toReadingPresentation(showMissingValidity = true)

        assertEquals("Dipirona Sódica", identified.title)
        assertTrue(identified.speech.startsWith("O remédio é Dipirona Sódica. Dosagem: 500 mg."))

        val unidentified = VerificarResponse(
            nome = "Medicamento não identificado com segurança",
            miligramas = null,
            validade = null,
            vencido = false,
            alertaAlergia = false,
            posologia = null,
            mensagem = null,
            perigo = false,
        ).toReadingPresentation(showMissingValidity = true)

        assertTrue(unidentified.speech.startsWith("Medicamento não identificado com segurança."))
        assertFalse(unidentified.speech.contains("O remédio é Medicamento não identificado"))
    }

    @Test
    fun readsOnlyImportantPrescriptionPartsWithoutRawOcrBoilerplate() {
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

        assertTrue(reading.details.contains("Enalapril"))
        assertTrue(reading.details.contains("Dosagem: 5mg"))
        assertTrue(reading.details.contains("Frequência: de manhã"))
        assertTrue(reading.details.contains("Instruções: via oral"))
        assertFalse(reading.details.contains("Receituário Médico"))
        assertFalse(reading.speech.contains("Receituário Médico"))
        assertFalse(reading.speech.contains("Paciente: José"))
    }

    @Test
    fun detailsAllergyInsidePrescriptionAndContinuesReadingRemainingMedicines() {
        val reading = ReceitaResponse(
            medicamentos = listOf(
                MedicamentoReceita("Dipirona Sódica", "500mg", "8/8h", "5 dias", "via oral"),
                MedicamentoReceita("Omeprazol", "20mg", "pela manhã", "14 dias", null),
            ),
            posologiaCompleta = "",
            horarios = listOf("08:00", "16:00"),
            mensagem = "Receita processada com sucesso.",
            medicamentosComAlerta = listOf("Dipirona Sódica"),
        ).toReadingPresentation()

        assertEquals("Alerta de alergia: Dipirona Sódica", reading.alert)
        assertTrue(reading.speech.contains("2 medicamentos identificados"))
        assertTrue(reading.speech.contains("você cadastrou alergia compatível com Dipirona Sódica"))
        assertTrue(reading.speech.contains("Omeprazol"))
        assertTrue(reading.speech.contains("14 dias"))
        assertTrue(reading.speech.contains("08:00, 16:00"))
    }

    @Test
    fun matchesAllergyAcrossAccentsAndPartialMedicationNames() {
        val reading = ReceitaResponse(
            medicamentos = listOf(
                MedicamentoReceita("Dipirona Sódica 500mg", "500mg", "6/6h", "3 dias", null),
                MedicamentoReceita("Paracetamol", "750mg", "8/8h", null, null),
            ),
            posologiaCompleta = "",
            horarios = emptyList(),
            mensagem = "Receita processada com sucesso.",
            medicamentosComAlerta = listOf("dipirona sodica"),
        ).toReadingPresentation()

        assertTrue(reading.speech.contains("você cadastrou alergia compatível com Dipirona Sódica 500mg"))
        assertTrue(reading.speech.contains("Paracetamol"))
    }
}
