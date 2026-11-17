package com.guiamed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class OfflineMedicationProcessorTest {
    private val processor = OfflineMedicationProcessor(
        listOf("azitromicina", "rosuvastatina cálcica"),
    )

    @Test
    fun identifiesAllergyAndExpiredMedicineWithoutBackend() {
        val november2026 = GregorianCalendar(2026, Calendar.NOVEMBER, 1)

        val response = processor.verify(
            textOcr = "NOVALGINA 500 MG VAL 10/2026",
            allergies = setOf("dipirona"),
            referenceDate = november2026,
        )

        assertEquals("Dipirona Sódica", response.nome)
        assertEquals("500mg", response.miligramas)
        assertEquals("10/2026", response.validade)
        assertTrue(response.vencido)
        assertTrue(response.alertaAlergia)
        assertTrue(response.perigo)
    }

    @Test
    fun readsCompactExpirationAndIgnoresManufacturingDate() {
        val november2026 = GregorianCalendar(2026, Calendar.NOVEMBER, 1)

        val response = processor.verify(
            textOcr = "DIPIRONA 500 MG L5M2694 F02 26 V02 28",
            allergies = emptySet(),
            referenceDate = november2026,
        )

        assertEquals("02/2028", response.validade)
        assertFalse(response.vencido)
    }

    @Test
    fun recognizesAnvisaDcbWithSmallOcrError() {
        val response = processor.verify(
            textOcr = "AZITROMICNA 500 MG",
            allergies = emptySet(),
        )

        assertEquals("azitromicina", response.nome)
        assertEquals("500mg", response.miligramas)
    }

    @Test
    fun parsesMultilinePrescriptionLocally() {
        val response = processor.parseRecipe(
            """
            IBUPROFENO
            600 MG
            TOMAR 1 COMPRIMIDO A CADA 8 HORAS POR 5 DIAS APOS AS REFEICOES
            OMEPRAZOL 20 MG
            TOMAR 1 CAPSULA EM JEJUM
            """.trimIndent(),
        )

        assertEquals(2, response.medicamentos.size)
        assertEquals("Ibuprofeno", response.medicamentos[0].nome)
        assertEquals("600mg", response.medicamentos[0].dosagem)
        assertEquals("5 dias", response.medicamentos[0].duracao)
        assertEquals("Omeprazol", response.medicamentos[1].nome)
        assertEquals("20mg", response.medicamentos[1].dosagem)
    }

    @Test
    fun removesSpokenAllergyPrefix() {
        assertEquals(
            "dipirona",
            LocalAllergyStore.cleanSpokenName("Eu tenho alergia a dipirona"),
        )
    }
}
