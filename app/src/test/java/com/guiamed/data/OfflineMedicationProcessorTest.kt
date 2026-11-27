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
    fun checksAllSubstancesFromAnEanAndDoesNotBorrowDosageFromOtherText() {
        val code = "7891106000956"
        val indexed = OfflineMedicationProcessor(
            listOf("azitromicina"),
            mapOf(code to OfflineMedicationProcessor.EanMedication(
                "baycuten n",
                listOf("21-acetato de dexametasona", "clotrimazol"),
            )),
        )
        val response = indexed.verify("50 MG", setOf("clotrimazol"), ean = code)
        assertEquals("baycuten n", response.nome)
        assertEquals(null, response.miligramas)
        assertTrue(response.alertaAlergia)
        assertTrue(response.perigo)

        val conflict = indexed.verify("AZITROMICINA 500 MG", setOf("clotrimazol"), ean = code)
        assertTrue(conflict.nome.startsWith("Medicamento não identificado"))
        assertEquals(null, conflict.miligramas)
        assertTrue(conflict.alertaAlergia)
    }

    @Test
    fun checksPrescriptionAliasesForLocalAllergies() {
        val reading = processor.parseRecipe("NOVALGINA 500 MG a cada 8 horas")
        assertEquals(
            listOf("Dipirona Sódica"),
            processor.recipeAllergyAlerts(reading.medicamentos, listOf("dipirona")),
        )
    }

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
    fun recognizesEnalaprilAndAppliesRegisteredAllergy() {
        val response = processor.verify(
            textOcr = "MALEATO DE ENALAPR1L 5 MG",
            allergies = setOf("maleato de enalapril"),
        )

        assertEquals("Enalapril", response.nome)
        assertEquals("5mg", response.miligramas)
        assertTrue(response.alertaAlergia)
        assertTrue(response.perigo)
    }

    @Test
    fun refusesAmbiguousOrWeakMedicationMatch() {
        val conflicting = processor.verify(
            textOcr = "MALEATO DE ENALAPRIL 5 MG IBUPROFENO 600 MG",
            allergies = emptySet(),
        )
        val weakMatch = processor.verify(
            textOcr = "IBUPROFENA 600 MG",
            allergies = emptySet(),
        )

        assertTrue(conflicting.nome.startsWith("Medicamento não identificado"))
        assertTrue(weakMatch.nome.startsWith("Medicamento não identificado"))
    }

    @Test
    fun prioritizesMedicineBesideDosageOverPackageExcipients() {
        val processorWithExcipient = OfflineMedicationProcessor(
            listOf("maleato de enalapril", "lactose"),
        )

        val response = processorWithExcipient.verify(
            textOcr = """
                MALEATO DE ENALAPRIL 10 MG
                USO ORAL
                EXCIPIENTES: LACTOSE E AMIDO
            """.trimIndent(),
            allergies = emptySet(),
        )

        assertEquals("Enalapril", response.nome)
        assertEquals("10mg", response.miligramas)
    }

    @Test
    fun recognizesMedicineSplitAcrossOcrLinesWithoutSlowFuzzyFallback() {
        val response = processor.verify(
            textOcr = "ROSUVASTATINA\nCÁLCICA\n20 MG\nUSO ORAL",
            allergies = emptySet(),
        )

        assertEquals("Rosuvastatina Cálcica", response.nome)
        assertEquals("20mg", response.miligramas)
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
    fun parsesAbbreviatedPrescriptionFrequency() {
        val everyEightHours = processor.parseRecipe("IBUPROFENO 600 MG\nTOMAR DE 8 EM 8 H")
        val onceDaily = processor.parseRecipe("OMEPRAZOL 20 MG\nTOMAR 1X/DIA")

        assertEquals("de 8 em 8 h", everyEightHours.medicamentos.single().frequencia)
        assertEquals("1x/dia", onceDaily.medicamentos.single().frequencia)
    }

    @Test
    fun readsPrintedDemoFixtureText() {
        val response = processor.parseRecipe(
            """
            RECEITA FICTÍCIA
            AMOSTRA PARA DEMONSTRAÇÃO
            IBUPROFENO 600 MG
            FREQUÊNCIA DE TESTE: DE 8 EM 8 HORAS
            DOCUMENTO SEM VALIDADE CLÍNICA
            """.trimIndent(),
        )

        val medicine = response.medicamentos.single()
        assertEquals("Ibuprofeno", medicine.nome)
        assertEquals("600mg", medicine.dosagem)
        assertEquals("de 8 em 8 horas", medicine.frequencia)
    }

    @Test
    fun readsAllMedicinesFromRecordedPrescription() {
        val response = processor.parseRecipe(
            """
            MediCam Centro Médico
            Receituário Médico
            Paciente: Jose Roberto Duarte Cardoso
            USO CONTINUO
            ENALAPRIL 5MG
            USO: 01 COMPRIMIDO, VIA ORAL DE MANHÃ.
            GLIFAGE XR 500MG
            USO: 01 COMPRIMIDO, VIA ORAL A NOITE
            ROSUVASTATINA 10MG
            USO: 01 COMPRIMIDO, VIA ORAL DE MANHÃ.
            """.trimIndent(),
        )

        assertEquals(listOf("Enalapril", "Metformina", "Rosuvastatina Cálcica"), response.medicamentos.map { it.nome })
        assertEquals(listOf("5mg", "500mg", "10mg"), response.medicamentos.map { it.dosagem })
        assertEquals(listOf("de manha", "a noite", "de manha"), response.medicamentos.map { it.frequencia })
        assertFalse(response.needsPrescriptionReview())
    }

    @Test
    fun removesSpokenAllergyPrefix() {
        assertEquals(
            "dipirona",
            LocalAllergyStore.cleanSpokenName("Eu tenho alergia a dipirona"),
        )
    }

    @Test
    fun readsLargeFrontDosageSplitAcrossLinesAndPackageDescriptors() {
        val multilineSplit = processor.verify(
            textOcr = "DIPIRONA SODICA\nMEDICAMENTO GENERICO\n30 COMPRIMIDOS\nUSO ORAL\n5OO\nmg",
            allergies = emptySet(),
        )
        assertEquals("Dipirona Sódica", multilineSplit.nome)
        assertEquals("500mg", multilineSplit.miligramas)

        val dosageBeforeName = processor.verify(
            textOcr = "20 MG\nMEDICAMENTO GENERICO\nROSUVASTATINA CALCICA\nCOMPRIMIDO REVESTIDO",
            allergies = emptySet(),
        )
        assertEquals("Rosuvastatina Cálcica", dosageBeforeName.nome)
        assertEquals("20mg", dosageBeforeName.miligramas)

        val noisyGiantFont = processor.verify(
            textOcr = "PARACETAMOL\n5 00 mq\nUSO ADULTO",
            allergies = emptySet(),
        )
        assertEquals("Paracetamol", noisyGiantFont.nome)
        assertEquals("500mg", noisyGiantFont.miligramas)

        val concentrationAndBottle = processor.verify(
            textOcr = "DIPIRONA SODICA\n500 MG/ML\nSOLUCAO ORAL\nFRASCO 20 ML",
            allergies = emptySet(),
        )
        assertEquals("Dipirona Sódica", concentrationAndBottle.nome)
        assertEquals("500mg/ml", concentrationAndBottle.miligramas)
    }
}
