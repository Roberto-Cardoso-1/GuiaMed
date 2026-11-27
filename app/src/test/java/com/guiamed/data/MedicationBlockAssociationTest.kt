package com.guiamed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class MedicationBlockAssociationTest {
    private val processor = OfflineMedicationProcessor(listOf("azitromicina", "rosuvastatina cálcica"))
    private val unknownItems = listOf(
        "2) Pressormin 900 MG",
        "2. Pressormin 900 MG",
        "2 - Pressormin 900 MG",
        "- Pressormin 900 MG",
        "Pressormin 900 MG",
        "Pressormin\n900 MG",
        "2) Pressormin\n900 MG",
        "- Pressormin\n900 MG",
    )

    @Test fun unknownItemNeverSuppliesDoseOrDirectionsToPreviousPrescriptionItem() {
        for (unknown in unknownItems) {
            val text = "OMEPRAZOL\n$unknown\nTOMAR 1 COMPRIMIDO A CADA 8 HORAS POR 5 DIAS"
            val response = processor.parseRecipe(text)
            val item = response.medicamentos.single()
            assertEquals(unknown, "Omeprazol", item.nome)
            assertEquals(unknown, "dosagem não identificada", item.dosagem)
            assertEquals(unknown, "frequência não identificada", item.frequencia)
            assertNull(unknown, item.duracao)
            assertNull(unknown, item.instrucoes)
            assertTrue(unknown, response.needsPrescriptionReview())
        }
    }

    @Test fun unknownItemNeverSuppliesPackageDose() {
        for (unknown in unknownItems) {
            val result = processor.verify("OMEPRAZOL\n$unknown", emptyList())
            assertEquals(unknown, "Omeprazol", result.nome)
            assertNull(unknown, result.miligramas)
        }
    }

    @Test fun inlineNumberedOrBulletedUnknownItemAlsoEndsTheBlock() {
        for (separator in listOf("2)", "2.", "(2)", "[2]", "2:", "A)", "-", "*", "–", "•", "●", "▪", "‣", "—", ";")) {
            val text = "OMEPRAZOL $separator Pressormin 900 MG A CADA 8 HORAS"
            val item = processor.parseRecipe(text).medicamentos.single()
            assertEquals(separator, "dosagem não identificada", item.dosagem)
            assertEquals(separator, "frequência não identificada", item.frequencia)
            assertNull(separator, processor.verify(text, emptyList()).miligramas)
        }
    }

    @Test fun semicolonsSeparateUnknownAndKnownPrescriptionItems() {
        val unknown = processor.parseRecipe("OMEPRAZOL; - Pressormin; 900 MG A CADA 6 HORAS")
        assertEquals("dosagem não identificada", unknown.medicamentos.single().dosagem)
        assertEquals("frequência não identificada", unknown.medicamentos.single().frequencia)
        val known = processor.parseRecipe("OMEPRAZOL 20 MG; PARACETAMOL 750 MG")
        assertEquals(listOf("20mg", "750mg"), known.medicamentos.map { it.dosagem })
    }

    @Test fun ownDoseDoesNotMakeUnknownNeighborFrequencySafe() {
        for (unknown in unknownItems) {
            val item = processor.parseRecipe("OMEPRAZOL 20 MG\n$unknown\nA CADA 8 HORAS")
                .medicamentos.single()
            assertEquals(unknown, "20mg", item.dosagem)
            assertEquals(unknown, "frequência não identificada", item.frequencia)
        }
    }

    @Test fun keepsOwnFieldsBeforeUnknownNeighborAndResumesAtNextKnownItem() {
        val response = processor.parseRecipe(
            "OMEPRAZOL 20 MG\nTOMAR 1 CAPSULA A CADA 12 HORAS POR 7 DIAS\n" +
                "2) Pressormin\n900 MG A CADA 8 HORAS\n3) IBUPROFENO 600 MG\nA CADA 6 HORAS",
        )
        assertEquals(listOf("Omeprazol", "Ibuprofeno"), response.medicamentos.map { it.nome })
        assertEquals(listOf("20mg", "600mg"), response.medicamentos.map { it.dosagem })
        assertEquals(listOf("a cada 12 horas", "a cada 6 horas"), response.medicamentos.map { it.frequencia })
        assertEquals("7 dias", response.medicamentos.first().duracao)
        assertEquals("tomar 1 capsula", response.medicamentos.first().instrucoes)
    }

    @Test fun packageDoseComesFromIdentityBlockAfterUnrelatedPreface() {
        for (text in listOf(
            "OBSERVACAO 100 MG\nOMEPRAZOL 20 MG",
            "OBSERVACAO 100 MG\nOMEPRAZOL\n20 MG",
            "OBSERVACAO 100 MG OMEPRAZOL 20 MG",
        )) {
            val result = processor.verify(text, emptyList())
            assertEquals(text, "Omeprazol", result.nome)
            assertEquals(text, "20mg", result.miligramas)
        }
    }

    @Test fun prefaceDoseCannotFillMissingIdentityDose() {
        for (text in listOf("OBSERVACAO 100 MG\nOMEPRAZOL", "OBSERVACAO 100 MG OMEPRAZOL")) {
            assertNull(text, processor.verify(text, emptyList()).miligramas)
            assertEquals(text, "dosagem não identificada", processor.parseRecipe(text).medicamentos.single().dosagem)
        }
    }

    @Test fun isolatedDoseAndExplicitDirectionsStillBelongToKnownIdentity() {
        val text = "1) OMEPRAZOL\n20 MG\n- TOMAR 1 CAPSULA A CADA 12 HORAS POR 7 DIAS\nEM JEJUM"
        val item = processor.parseRecipe(text).medicamentos.single()
        assertEquals("20mg", item.dosagem)
        assertEquals("a cada 12 horas", item.frequencia)
        assertEquals("7 dias", item.duracao)
        assertEquals("tomar 1 capsula, em jejum", item.instrucoes)
        assertEquals("20mg", processor.verify(text, emptyList()).miligramas)
    }

    @Test fun identitySuffixAndDoseOnSeparateLinesStillWorkWithoutFuzzy() {
        val result = processor.verify("ROSUVASTATINA\nCALCICA\n20 MG", emptyList(), allowFuzzy = false)
        assertEquals("Rosuvastatina Cálcica", result.nome)
        assertEquals("20mg", result.miligramas)
    }

    @Test fun fuzzyIdentityKeepsItsDoseAndNeverUsesPrefaceDose() {
        val result = processor.verify("OBSERVACAO 100 MG\nAZITROMICNA 500 MG", emptyList())
        assertEquals("azitromicina", result.nome)
        assertEquals("500mg", result.miligramas)
    }

    @Test fun conflictingDosesForSamePackageIdentityRemainUnknown() {
        val result = processor.verify("OMEPRAZOL 20 MG\nOMEPRAZOL 40 MG", emptyList())
        assertEquals("Omeprazol", result.nome)
        assertNull(result.miligramas)
    }

    @Test fun allergyAndExpirationDoNotDependOnFindingAnAssociatedDose() {
        val result = processor.verify(
            "OMEPRAZOL\n2) Pressormin 900 MG\nVAL 10/2026",
            listOf("omeprazol"), GregorianCalendar(2026, Calendar.NOVEMBER, 26),
        )
        assertNull(result.miligramas)
        assertEquals("10/2026", result.validade)
        assertTrue(result.alertaAlergia)
        assertTrue(result.vencido)
        assertTrue(result.perigo)
        assertEquals(listOf("omeprazol"), result.componentes)
    }

    @Test fun eanAgreementUsesOnlyAssociatedTextAndBarcodeAloneCannotBorrowDose() {
        val ean = "7891234567890"
        val indexed = OfflineMedicationProcessor(emptyList(), mapOf(
            ean to OfflineMedicationProcessor.EanMedication("Omeprazol Genérico", listOf("omeprazol")),
        ))
        val agreed = indexed.verify("OBSERVACAO 100 MG\nOMEPRAZOL 20 MG", listOf("omeprazol"), ean = ean)
        assertEquals("Omeprazol Genérico", agreed.nome)
        assertEquals("20mg", agreed.miligramas)
        assertTrue(agreed.alertaAlergia)
        val barcodeOnly = indexed.verify("Pressormin 900 MG", emptyList(), ean = ean)
        assertEquals("Omeprazol Genérico", barcodeOnly.nome)
        assertNull(barcodeOnly.miligramas)
        assertFalse(barcodeOnly.perigo)
    }

    @Test fun remoteEvidenceAlsoStopsBeforeUnknownNumberedItem() {
        val evidence = processor.prescriptionEvidence(
            "Omeprazol", "OMEPRAZOL\n2) Pressormin 900 MG\nTOMAR 1 COMPRIMIDO A CADA 8 HORAS",
        )!!
        assertEquals("dosagem não identificada", evidence.dosagem)
        assertEquals("frequência não identificada", evidence.frequencia)
        assertNull(evidence.instrucoes)
    }
}
