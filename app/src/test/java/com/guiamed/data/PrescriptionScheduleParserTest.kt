package com.guiamed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrescriptionScheduleParserTest {
    @Test
    fun prefersExplicitSchedulesFromPrescription() {
        val response = ReceitaResponse(
            medicamentos = listOf(MedicamentoReceita("Enalapril", "10 mg", "de 12 em 12 horas", null, null)),
            posologiaCompleta = "",
            horarios = listOf("07:30", "19:30", "99:99"),
            mensagem = null,
        )

        val alarms = PrescriptionScheduleParser.fromPrescription(response)
        assertEquals(2, alarms.size)
        assertEquals("07:30", alarms[0].formattedTime())
        assertEquals("19:30", alarms[1].formattedTime())
        assertEquals("GuiaMed: Enalapril", alarms[0].label)
    }

    @Test
    fun derivesSchedulesFromFrequencyWhenExplicitTimesAreAbsent() {
        val response = ReceitaResponse(
            medicamentos = listOf(MedicamentoReceita("Amoxicilina", "500 mg", "de 8 em 8 horas", "7 dias", null)),
            posologiaCompleta = "",
            horarios = emptyList(),
            mensagem = null,
        )

        val alarms = PrescriptionScheduleParser.fromPrescription(response)
        assertEquals(listOf("08:00", "16:00", "00:00"), alarms.map { it.formattedTime() })
    }

    @Test
    fun supportsCompactAndPortugueseFrequenciesAndMergesMedicineLabels() {
        val response = ReceitaResponse(
            medicamentos = listOf(
                MedicamentoReceita("Ibuprofeno", "600 mg", "8/8h", "5 dias", null),
                MedicamentoReceita("Losartana", "50 mg", "duas vezes ao dia", null, null),
                MedicamentoReceita("Sinvastatina", "20 mg", "ao deitar", null, null),
                MedicamentoReceita("Omeprazol", "20 mg", "pela manhã", null, null),
            ),
            posologiaCompleta = "",
            horarios = emptyList(),
            mensagem = null,
        )

        val alarms = PrescriptionScheduleParser.fromPrescription(response).associate { it.formattedTime() to it.label }
        assertEquals("GuiaMed: Ibuprofeno, Losartana, Omeprazol", alarms["08:00"])
        assertEquals("GuiaMed: Ibuprofeno", alarms["16:00"])
        assertEquals("GuiaMed: Ibuprofeno", alarms["00:00"])
        assertEquals("GuiaMed: Losartana", alarms["20:00"])
        assertEquals("GuiaMed: Sinvastatina", alarms["22:00"])
    }

    @Test
    fun parsesSpokenTimesInPortuguese() {
        assertEquals("08:30", PrescriptionScheduleParser.fromSpokenTime("Criar alarme para 08:30")?.formattedTime())
        assertEquals("14:00", PrescriptionScheduleParser.fromSpokenTime("Lembrete às 14 horas")?.formattedTime())
        assertEquals("09:15", PrescriptionScheduleParser.fromSpokenTime("Alarme 9 e 15")?.formattedTime())
        assertNull(PrescriptionScheduleParser.fromSpokenTime("Criar lembrete"))
    }
}
