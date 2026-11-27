package com.guiamed.data

import java.text.Normalizer
import java.util.Locale

internal enum class PackageFace {
    FRONT,
    BACK_OR_SIDE,
    UNKNOWN,
}

internal enum class FramingState {
    NO_TEXT,
    TOO_CLOSE,
    OFF_CENTER,
    TOO_FAR,
    ALIGNED,
}

internal data class PackageVisionStats(
    val hasAnyText: Boolean,
    val lineCount: Int,
    val centerXRatio: Float,
    val centerYRatio: Float,
    val occupiedWidthRatio: Float,
    val occupiedHeightRatio: Float,
    val clippedEdge: Boolean,
    val medianLineHeightRatio: Float,
    val maxLineHeightRatio: Float,
)

internal data class PackageGuidanceDecision(
    val framing: FramingState,
    val face: PackageFace,
    val hasBatchOrExpiryCue: Boolean,
)

internal object PackageVisionGuidance {
    private val diacriticsRegex = Regex("""\p{M}+""")
    private val batchOrExpiryCueRegex = Regex(
        """\b(?:VALIDADE|VALID[OA]?|VENCIMENTO|VENC|VAL|VTO|EXPIRA|EXP|LOTE|LOT|FAB|FABRICACAO|FABRIC)\b|""" +
            """(?<![A-Z0-9])[VF]\s*(?:\d{2}\s*[/.\-]\s*\d{2,4}|\d{4})(?![A-Z0-9])""",
    )
    private val backOnlyMarkerRegex = Regex(
        """\b(?:EXCIPIENTES|COMPOSICAO|REG\.?\s*MS|CNPJ|SAC|FARMACEUTIC\w*|CONSERVAR|""" +
            """TODO MEDICAMENTO|MANTENHA FORA|LEIA A BULA)\b""",
    )
    private val frontStrengthRegex = Regex(
        """\b\d{1,5}(?:[.,]\d+)?\s*(?:MCG|MG|G|UI)(?:\s*/\s*(?:\d{1,3}\s*)?(?:MCG|MG|G|ML|DOSE))?\b""",
    )
    private val frontDescriptorRegex = Regex(
        """\b(?:GENERICO|MEDICAMENTO GENERICO|COMPRIMID\w*|CAPSUL\w*|SOLUCAO ORAL|SUSPENSAO|GOTAS|USO ADULTO|USO ORAL)\b""",
    )

    fun classify(
        text: String,
        ean: String?,
        identifiedFrontMedication: Boolean,
        stats: PackageVisionStats?,
    ): PackageGuidanceDecision {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .uppercase(Locale.ROOT)
        val hasExpiry = ExpirationReader.findDates(text).isNotEmpty()
        val hasBatchCue = hasExpiry || batchOrExpiryCueRegex.containsMatchIn(normalized)
        val hasBackMarker = hasBatchCue ||
            backOnlyMarkerRegex.containsMatchIn(normalized) ||
            (stats != null && stats.lineCount >= 9 && stats.medianLineHeightRatio in 0.001f..0.024f && !identifiedFrontMedication)
        val hasFrontStrength = frontStrengthRegex.containsMatchIn(normalized)
        val hasFrontDescriptor = frontDescriptorRegex.containsMatchIn(normalized)
        val hasFrontEvidence = identifiedFrontMedication || (hasFrontStrength && (hasFrontDescriptor || ean == null))

        val framing = when {
            ean == null && text.isBlank() && (stats == null || !stats.hasAnyText) -> FramingState.NO_TEXT
            stats == null -> FramingState.ALIGNED
            (stats.occupiedWidthRatio > 0.88f || stats.occupiedHeightRatio > 0.88f || stats.clippedEdge) &&
                !identifiedFrontMedication && !hasExpiry -> {
                if (stats.occupiedWidthRatio > 0.85f || stats.occupiedHeightRatio > 0.85f) FramingState.TOO_CLOSE
                else FramingState.OFF_CENTER
            }
            (stats.centerXRatio !in 0.20f..0.80f || stats.centerYRatio !in 0.18f..0.82f) &&
                !identifiedFrontMedication && !hasExpiry -> FramingState.OFF_CENTER
            stats.maxLineHeightRatio in 0.001f..0.022f &&
                stats.occupiedWidthRatio < 0.30f &&
                !identifiedFrontMedication && !hasExpiry -> FramingState.TOO_FAR
            else -> FramingState.ALIGNED
        }

        val face = when {
            ean != null && !hasFrontEvidence -> PackageFace.BACK_OR_SIDE
            hasBackMarker && !identifiedFrontMedication && !hasFrontStrength -> PackageFace.BACK_OR_SIDE
            hasBackMarker && !identifiedFrontMedication && hasExpiry -> PackageFace.BACK_OR_SIDE
            hasFrontEvidence -> PackageFace.FRONT
            hasBackMarker -> PackageFace.BACK_OR_SIDE
            else -> PackageFace.UNKNOWN
        }

        return PackageGuidanceDecision(
            framing = framing,
            face = face,
            hasBatchOrExpiryCue = hasBatchCue,
        )
    }
}
