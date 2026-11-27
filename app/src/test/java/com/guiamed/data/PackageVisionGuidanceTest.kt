package com.guiamed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageVisionGuidanceTest {

    @Test
    fun reportsNoTextWhenFrameIsEmptyWithoutBarcode() {
        val decision = PackageVisionGuidance.classify(
            text = "",
            ean = null,
            identifiedFrontMedication = false,
            stats = null,
        )
        assertEquals(FramingState.NO_TEXT, decision.framing)
        assertEquals(PackageFace.UNKNOWN, decision.face)
        assertFalse(decision.hasBatchOrExpiryCue)
    }

    @Test
    fun detectsBackOrSideFromBarcodeOrBatchAndExpiryMarkers() {
        val backEan = PackageVisionGuidance.classify(
            text = "SAC 0800 123 456\nCNPJ 00.000.000/0001-00",
            ean = "7891234567890",
            identifiedFrontMedication = false,
            stats = PackageVisionStats(
                hasAnyText = true,
                lineCount = 10,
                centerXRatio = 0.5f,
                centerYRatio = 0.5f,
                occupiedWidthRatio = 0.6f,
                occupiedHeightRatio = 0.5f,
                clippedEdge = false,
                medianLineHeightRatio = 0.018f,
                maxLineHeightRatio = 0.03f,
            ),
        )
        assertEquals(PackageFace.BACK_OR_SIDE, backEan.face)
        assertEquals(FramingState.ALIGNED, backEan.framing)

        val sideBatch = PackageVisionGuidance.classify(
            text = "LOTE L5M2694 FAB 02/26",
            ean = null,
            identifiedFrontMedication = false,
            stats = null,
        )
        assertEquals(PackageFace.BACK_OR_SIDE, sideBatch.face)
        assertTrue(sideBatch.hasBatchOrExpiryCue)
    }

    @Test
    fun detectsFrontFaceWhenMedicationAndDosageArePresent() {
        val front = PackageVisionGuidance.classify(
            text = "DIPIRONA SODICA 500 MG\nMEDICAMENTO GENERICO\n30 COMPRIMIDOS",
            ean = null,
            identifiedFrontMedication = true,
            stats = PackageVisionStats(
                hasAnyText = true,
                lineCount = 3,
                centerXRatio = 0.5f,
                centerYRatio = 0.48f,
                occupiedWidthRatio = 0.65f,
                occupiedHeightRatio = 0.45f,
                clippedEdge = false,
                medianLineHeightRatio = 0.05f,
                maxLineHeightRatio = 0.12f,
            ),
        )
        assertEquals(PackageFace.FRONT, front.face)
        assertEquals(FramingState.ALIGNED, front.framing)
    }

    @Test
    fun flagsTooCloseOffCenterAndTooFarBeforeIdentification() {
        val tooClose = PackageVisionGuidance.classify(
            text = "TEXTO CORTADO",
            ean = null,
            identifiedFrontMedication = false,
            stats = PackageVisionStats(
                hasAnyText = true,
                lineCount = 2,
                centerXRatio = 0.5f,
                centerYRatio = 0.5f,
                occupiedWidthRatio = 0.92f,
                occupiedHeightRatio = 0.70f,
                clippedEdge = true,
                medianLineHeightRatio = 0.08f,
                maxLineHeightRatio = 0.12f,
            ),
        )
        assertEquals(FramingState.TOO_CLOSE, tooClose.framing)

        val offCenter = PackageVisionGuidance.classify(
            text = "TEXTO LATERAL",
            ean = null,
            identifiedFrontMedication = false,
            stats = PackageVisionStats(
                hasAnyText = true,
                lineCount = 2,
                centerXRatio = 0.88f,
                centerYRatio = 0.5f,
                occupiedWidthRatio = 0.35f,
                occupiedHeightRatio = 0.30f,
                clippedEdge = false,
                medianLineHeightRatio = 0.04f,
                maxLineHeightRatio = 0.06f,
            ),
        )
        assertEquals(FramingState.OFF_CENTER, offCenter.framing)

        val tooFar = PackageVisionGuidance.classify(
            text = "TEXTO DISTANTE",
            ean = null,
            identifiedFrontMedication = false,
            stats = PackageVisionStats(
                hasAnyText = true,
                lineCount = 2,
                centerXRatio = 0.5f,
                centerYRatio = 0.5f,
                occupiedWidthRatio = 0.22f,
                occupiedHeightRatio = 0.12f,
                clippedEdge = false,
                medianLineHeightRatio = 0.015f,
                maxLineHeightRatio = 0.018f,
            ),
        )
        assertEquals(FramingState.TOO_FAR, tooFar.framing)
    }

    @Test
    fun avoidsFalseBackOrBatchCuesOnFrontLegalStripeAndVolumeText() {
        val frontStripeOnly = PackageVisionGuidance.classify(
            text = "VENDA SOB PRESCRICAO MEDICA\nINDUSTRIA BRASILEIRA\nVOL 100 ML",
            ean = null,
            identifiedFrontMedication = false,
            stats = null,
        )
        assertEquals(PackageFace.UNKNOWN, frontStripeOnly.face)
        assertFalse(frontStripeOnly.hasBatchOrExpiryCue)

        val eanOnlyEmptyText = PackageVisionGuidance.classify(
            text = "",
            ean = "7891234567890",
            identifiedFrontMedication = false,
            stats = null,
        )
        assertEquals(PackageFace.BACK_OR_SIDE, eanOnlyEmptyText.face)
        assertEquals(FramingState.ALIGNED, eanOnlyEmptyText.framing)

        val expiryOverridesEdgeClip = PackageVisionGuidance.classify(
            text = "VAL 10/2028",
            ean = null,
            identifiedFrontMedication = false,
            stats = PackageVisionStats(
                hasAnyText = true,
                lineCount = 1,
                centerXRatio = 0.90f,
                centerYRatio = 0.5f,
                occupiedWidthRatio = 0.40f,
                occupiedHeightRatio = 0.15f,
                clippedEdge = true,
                medianLineHeightRatio = 0.05f,
                maxLineHeightRatio = 0.05f,
            ),
        )
        assertEquals(FramingState.ALIGNED, expiryOverridesEdgeClip.framing)
        assertTrue(expiryOverridesEdgeClip.hasBatchOrExpiryCue)
    }
}
