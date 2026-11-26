package com.guiamed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class ExpirationReaderTest {
    private val today = GregorianCalendar(2026, Calendar.NOVEMBER, 18)

    @Test
    fun readsSideOfBoxWithNoMedicineName() {
        val date = ExpirationReader.findDates("LOTE 5M2694\nFAB 02/26\nV. O2 2O28").single()
        assertEquals("02/2028", date.label)
        assertFalse(date.isExpired(today))
    }

    @Test
    fun readsFullDateAndKeepsPrintedDay() {
        val expired = ExpirationReader.findDates("Válido até\n17/11/2026").single()
        val todayDate = ExpirationReader.findDates("VAL: 18/11/2026").single()
        assertEquals("17/11/2026", expired.label)
        assertTrue(expired.isExpired(today))
        assertFalse(todayDate.isExpired(today))
    }

    @Test
    fun acceptsCompactStampAndCurrentMonth() {
        val compact = ExpirationReader.findDates("L5M2694 F1124 V1126").single()
        assertEquals("11/2026", compact.label)
        assertFalse(compact.isExpired(today))
    }

    @Test
    fun rejectsManufacturingDateInvalidDateAndConflictingReads() {
        assertTrue(ExpirationReader.findDates("FAB 02/26\nL 5M2694\n12/28").isEmpty())
        assertTrue(ExpirationReader.findDates("VAL 31/02/2028").isEmpty())
        assertTrue(ExpirationReader.findDates("VAL 13/2028").isEmpty())
        val candidates = ExpirationReader.findDates("VAL 11/26") + ExpirationReader.findDates("VAL 12/26")
        assertNull(candidates.singleOrNull())
    }

    @Test
    fun updatesOnlyExpiryDataOfAlreadyIdentifiedMedicine() {
        val original = VerificarResponse(
            nome = "Dipirona Sódica",
            miligramas = "500mg",
            validade = null,
            vencido = false,
            alertaAlergia = true,
            componentes = listOf("dipirona"),
            posologia = null,
            mensagem = "Alerta de alergia detectado. Validade não encontrada na imagem.",
            perigo = true,
        )
        val date = ExpirationReader.findDates("V1026").single()
        val result = original.withReadExpiration(date, today)
        assertEquals(original.nome, result.nome)
        assertEquals(original.miligramas, result.miligramas)
        assertEquals(original.componentes, result.componentes)
        assertEquals("Alerta de alergia detectado.", result.mensagem)
        assertEquals("10/2026", result.validade)
        assertTrue(result.alertaAlergia && result.vencido && result.perigo)
    }
}
