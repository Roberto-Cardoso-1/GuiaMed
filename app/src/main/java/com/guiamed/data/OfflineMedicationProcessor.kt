package com.guiamed.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Processa embalagem, validade e receita inteiramente no aparelho.
 *
 * O catálogo DCB é embarcado em assets para que nenhuma leitura dependa de
 * internet ou do backend. O parser permanece conservador quando a confiança é
 * insuficiente.
 */
internal class OfflineMedicationProcessor internal constructor(dcbNames: List<String>) {

    private data class Medicine(
        val name: String,
        val aliases: List<String>,
        val components: List<String>,
    )

    private data class DcbCatalogItem(
        val nome: String = "",
    )

    companion object {
        private val nonMedicineWords = setOf(
            "CAIXA",
            "CAPSULA",
            "CAPSULAS",
            "COMPRIMIDO",
            "COMPRIMIDOS",
            "GENERICO",
            "MEDICAMENTO",
            "ORAL",
            "REVESTIDO",
            "REVESTIDOS",
            "USO",
        )

        fun fromContext(context: Context): OfflineMedicationProcessor {
            val names = runCatching {
                val listType = object : TypeToken<List<DcbCatalogItem>>() {}.type
                context.assets.open("dcb_medicamentos.json").bufferedReader().use { reader ->
                    val items: List<DcbCatalogItem> = Gson().fromJson(reader, listType)
                    items.map { it.nome }
                }
            }.getOrDefault(emptyList())
            return OfflineMedicationProcessor(names)
        }
    }

    private val curatedMedicines = listOf(
        Medicine(
            name = "Dipirona Sódica",
            aliases = listOf("DIPIRONA", "DIPIRONA SODICA", "NOVALGINA", "METAMIZOL"),
            components = listOf("dipirona", "metamizol"),
        ),
        Medicine(
            name = "Nimesulida",
            aliases = listOf("NIMESULIDA", "NISULID", "SCFLAM"),
            components = listOf("nimesulida"),
        ),
        Medicine(
            name = "Ibuprofeno",
            aliases = listOf("IBUPROFENO", "ALIVIUM", "ADVIL"),
            components = listOf("ibuprofeno"),
        ),
        Medicine(
            name = "Paracetamol",
            aliases = listOf("PARACETAMOL", "TYLENOL"),
            components = listOf("paracetamol", "acetaminofeno"),
        ),
        Medicine(
            name = "Ácido acetilsalicílico",
            aliases = listOf("ACIDO ACETILSALICILICO", "ASPIRINA", "AAS"),
            components = listOf("ácido acetilsalicílico", "aspirina"),
        ),
        Medicine(
            name = "Amoxicilina",
            aliases = listOf("AMOXICILINA", "AMOXIL"),
            components = listOf("amoxicilina", "penicilina"),
        ),
        Medicine(
            name = "Omeprazol",
            aliases = listOf("OMEPRAZOL", "LOSEC"),
            components = listOf("omeprazol"),
        ),
        Medicine(
            name = "Rosuvastatina Cálcica",
            aliases = listOf(
                "ROSUVASTATINA",
                "ROSUVASTATINA CALCICA",
                "CRESTOR",
                "ROSUCORE",
                "ROSUCOR",
            ),
            components = listOf("rosuvastatina", "rosuvastatina cálcica"),
        ),
    )

    private val dcbMedicines = dcbNames.asSequence()
        .map(String::trim)
        .filter { normalize(it).length >= 4 }
        .distinctBy(::normalize)
        .map { name -> Medicine(name, listOf(name), listOf(name)) }
        .toList()

    private val exactDcbIndex = dcbMedicines.associateBy { normalize(it.name) }
    private val fuzzyDcbByLength = dcbMedicines.asSequence()
        .map { normalize(it.name) to it }
        .filter { (name, _) -> " " !in name && name.length >= 7 }
        .groupBy { (name, _) -> name.length }

    internal fun verify(
        textOcr: String,
        allergies: Collection<String>,
        referenceDate: Calendar = Calendar.getInstance(),
    ): VerificarResponse {
        val normalized = normalize(textOcr)
        val medicine = findMedicine(normalized)
        val dosage = extractDosage(normalized)
        val (validity, expired) = extractExpiration(normalized, referenceDate)

        if (medicine == null) {
            return VerificarResponse(
                nome = "Medicamento não identificado com segurança",
                miligramas = dosage,
                validade = validity,
                vencido = expired,
                alertaAlergia = false,
                componentes = emptyList(),
                posologia = null,
                mensagem = "Não consegui identificar o medicamento com segurança. Reposicione a caixa e tente novamente.",
                perigo = expired,
            )
        }

        val allergyAlert = allergies.any { allergy ->
            medicine.aliases.plus(medicine.components).plus(medicine.name).any { target ->
                similarity(allergy, target) >= 0.78
            }
        }

        val messages = mutableListOf<String>()
        if (dosage == null) messages += "Dosagem não encontrada na imagem."
        if (validity == null) messages += "Validade não encontrada na imagem."
        if (allergyAlert) messages.add(0, "Alerta de alergia detectado.")
        if (expired) messages.add(0, "Medicamento vencido.")

        return VerificarResponse(
            nome = medicine.name,
            miligramas = dosage,
            validade = validity,
            vencido = expired,
            alertaAlergia = allergyAlert,
            componentes = medicine.components,
            posologia = null,
            mensagem = messages.joinToString(" ").ifBlank { null },
            perigo = expired || allergyAlert,
        )
    }

    internal fun parseRecipe(textOcr: String): ReceitaResponse {
        val normalizedLines = textOcr.lineSequence()
            .map(::normalize)
            .filter(String::isNotBlank)
            .toList()
            .ifEmpty { listOf(normalize(textOcr)) }

        val detected = normalizedLines.map(::findMedicine)
        val medicines = mutableListOf<MedicamentoReceita>()
        val seen = mutableSetOf<String>()

        detected.forEachIndexed { lineIndex, medicine ->
            if (medicine == null || !seen.add(medicine.name)) return@forEachIndexed

            val blockLines = mutableListOf(normalizedLines[lineIndex])
            for (nextIndex in lineIndex + 1 until minOf(lineIndex + 4, normalizedLines.size)) {
                val nextMedicine = detected[nextIndex]
                if (nextMedicine != null && nextMedicine.name != medicine.name) break
                blockLines += normalizedLines[nextIndex]
            }
            val context = blockLines.joinToString(" ")

            medicines += MedicamentoReceita(
                nome = medicine.name,
                dosagem = extractDosage(context) ?: "dosagem não identificada",
                frequencia = extractRecipeFrequency(context),
                duracao = extractRecipeDuration(context),
                instrucoes = extractRecipeInstructions(context),
            )
        }

        val times = Regex("""\b(?:[01]\d|2[0-3]):[0-5]\d\b""")
            .findAll(textOcr)
            .map { it.value }
            .toSortedSet()
            .toList()

        if (medicines.isEmpty()) {
            return ReceitaResponse(
                medicamentos = emptyList(),
                posologiaCompleta = "",
                horarios = times,
                mensagem = "Não consegui identificar medicamentos na receita. Tente outra foto com melhor foco.",
            )
        }

        val summary = medicines.joinToString(". ") { medicine ->
            listOfNotNull(
                medicine.nome,
                medicine.dosagem,
                medicine.frequencia,
                medicine.duracao,
                medicine.instrucoes,
            ).joinToString(" ")
        }
        val incomplete = medicines.any {
            it.dosagem == "dosagem não identificada" ||
                it.frequencia == "frequência não identificada"
        }

        return ReceitaResponse(
            medicamentos = medicines,
            posologiaCompleta = summary,
            horarios = times,
            mensagem = if (incomplete) {
                "Receita lida, mas alguns dados não foram identificados. Confirme na receita original."
            } else {
                "Receita processada com sucesso."
            },
        )
    }

    internal fun normalize(value: String): String {
        var text = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .uppercase(Locale.ROOT)

        fun fixDigits(raw: String): String = raw
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')

        text = Regex("""(?<![A-Z0-9])([VF])\s*([0-9OIL]{2})\s*[/\-. ]\s*([0-9OIL]{2,4})(?![A-Z0-9])""")
            .replace(text) { match ->
                match.groupValues[1] + " " + fixDigits(match.groupValues[2]) +
                    "/" + fixDigits(match.groupValues[3])
            }
        text = Regex("""(?<![A-Z0-9])([VF])([0-9OIL]{2})([0-9OIL]{2})(?![A-Z0-9])""")
            .replace(text) { match ->
                match.groupValues[1] + " " + fixDigits(match.groupValues[2]) +
                    "/" + fixDigits(match.groupValues[3])
            }
        text = Regex("""\b([0-9OIL]+(?:[.,][0-9OIL]+)?)\s*(MCG|MG|ML|G)\b""")
            .replace(text) { match ->
                fixDigits(match.groupValues[1]) + " " + match.groupValues[2]
            }
        text = Regex("""\b([0-9OIL]{1,2})\s*[/\-.]\s*([0-9OIL]{2,4})\b""")
            .replace(text) { match ->
                fixDigits(match.groupValues[1]) + "/" + fixDigits(match.groupValues[2])
            }

        text = text.split(Regex("""\s+""")).joinToString(" ") { token ->
            if ('0' in token && token.any(Char::isLetter)) token.replace('0', 'O') else token
        }
        return text.trim()
    }

    private fun findMedicine(normalizedText: String): Medicine? {
        val words = Regex("""[A-Z]{3,}""")
            .findAll(normalizedText)
            .map { it.value }
            .toList()
        val candidates = words + words.zipWithNext { left, right -> "$left $right" }

        var bestMedicine: Medicine? = null
        var bestScore = 0.0
        curatedMedicines.forEach { medicine ->
            medicine.aliases.forEach { alias ->
                val normalizedAlias = normalize(alias)
                if (normalizedText.contains(normalizedAlias)) return medicine
                candidates.forEach { candidate ->
                    val score = similarity(normalizedAlias, candidate)
                    if (score > bestScore) {
                        bestScore = score
                        bestMedicine = medicine
                    }
                }
            }
        }

        if (bestScore >= 0.86) return bestMedicine
        findDcbMedicine(normalizedText)?.let { return it }
        return bestMedicine?.takeIf { bestScore >= 0.72 }
    }

    private fun findDcbMedicine(normalizedText: String): Medicine? {
        if (exactDcbIndex.isEmpty()) return null

        val words = Regex("""[A-Z0-9]{2,}""")
            .findAll(normalizedText)
            .map { it.value }
            .filter { word -> word.any(Char::isLetter) }
            .toList()

        var exactMatch: Medicine? = null
        var exactLength = 0
        for (size in 1..minOf(6, words.size)) {
            for (index in 0..words.size - size) {
                val candidate = words.subList(index, index + size).joinToString(" ")
                val medicine = exactDcbIndex[candidate]
                if (medicine != null && candidate.length > exactLength) {
                    exactMatch = medicine
                    exactLength = candidate.length
                }
            }
        }
        if (exactMatch != null) return exactMatch

        val candidates = words.asSequence()
            .filter { it.length >= 7 && it !in nonMedicineWords }
            .toSet()
        var bestMedicine: Medicine? = null
        var bestName = ""
        var bestScore = 0.0
        var secondScore = 0.0

        candidates.forEach { candidate ->
            val tolerance = max(2, (candidate.length * 0.18).roundToInt())
            for (length in candidate.length - tolerance..candidate.length + tolerance) {
                fuzzyDcbByLength[length].orEmpty().forEach { (officialName, medicine) ->
                    val score = similarity(officialName, candidate)
                    if (score > bestScore) {
                        secondScore = bestScore
                        bestScore = score
                        bestMedicine = medicine
                        bestName = officialName
                    } else if (officialName != bestName && score > secondScore) {
                        secondScore = score
                    }
                }
            }
        }

        return bestMedicine?.takeIf { bestScore >= 0.86 && bestScore - secondScore >= 0.04 }
    }

    private fun extractDosage(normalizedText: String): String? {
        val match = Regex("""\b(\d{1,4}(?:[.,]\d+)?)\s*(MCG|MG|G|ML)\b""")
            .find(normalizedText)
            ?: return null
        return (match.groupValues[1].replace(',', '.') +
            match.groupValues[2].lowercase(Locale.ROOT))
    }

    private fun extractExpiration(
        normalizedText: String,
        referenceDate: Calendar,
    ): Pair<String?, Boolean> {
        val match = Regex(
            """\b(?:VALIDADE|VALID|VAL|VENCIMENTO|VENC|VTO|EXPIRA|EXP|V)\s*[:.\-]?\s*""" +
                """(0?[1-9]|1[0-2])\s*[/\-. ]\s*(\d{2}|\d{4})\b""",
        ).find(normalizedText) ?: return null to false

        val month = match.groupValues[1].toInt()
        val rawYear = match.groupValues[2].toInt()
        val year = if (rawYear < 100) rawYear + 2000 else rawYear
        val expired = year < referenceDate.get(Calendar.YEAR) ||
            (year == referenceDate.get(Calendar.YEAR) &&
                month < referenceDate.get(Calendar.MONTH) + 1)
        return "%02d/%04d".format(Locale.ROOT, month, year) to expired
    }

    private fun extractRecipeFrequency(normalizedText: String): String {
        val patterns = listOf(
            """\b\d{1,2}\s*/\s*\d{1,2}\s*H\b""",
            """\bA CADA \d{1,2} HORAS?\b""",
            """\bDE \d{1,2} EM \d{1,2} HORAS?\b""",
            """\b(?:\d{1,2}|UMA|DUAS|TRES)\s*(?:X|VEZ|VEZES)\s*(?:AO|POR)\s*DIA\b""",
            """\b(?:PELA MANHA|A NOITE|AO DEITAR|ANTES DE DORMIR)\b""",
        )
        patterns.forEach { pattern ->
            Regex(pattern).find(normalizedText)?.let {
                return it.value.lowercase(Locale.forLanguageTag("pt-BR"))
            }
        }
        return "frequência não identificada"
    }

    private fun extractRecipeDuration(normalizedText: String): String? {
        return Regex("""\b(?:POR|DURANTE|POR ATE)\s+(\d{1,3}\s+(?:DIAS?|SEMANAS?|MESES?))\b""")
            .find(normalizedText)
            ?.groupValues
            ?.get(1)
            ?.lowercase(Locale.forLanguageTag("pt-BR"))
    }

    private fun extractRecipeInstructions(normalizedText: String): String? {
        val instructions = mutableListOf<String>()
        Regex(
            """\b(?:TOMAR|USAR|APLICAR|INGERIR)\s+""" +
                """(?:\d{1,2}|UM|UMA|MEIO|MEIA)\s+""" +
                """(?:COMPRIMIDOS?|CP|CAPSULAS?|CAP|GOTAS?|ML|DOSES?|PUFFS?)\b""",
        ).find(normalizedText)?.value?.lowercase(Locale.forLanguageTag("pt-BR"))?.let(instructions::add)

        Regex(
            """\b(?:APOS AS? REFEICOES?|ANTES DAS? REFEICOES?|EM JEJUM|""" +
                """PELA MANHA|A NOITE|AO DEITAR|ANTES DE DORMIR)\b""",
        ).find(normalizedText)?.value?.lowercase(Locale.forLanguageTag("pt-BR"))?.let { context ->
            if (context !in instructions) instructions += context
        }
        return instructions.joinToString(", ").ifBlank { null }
    }

    private fun similarity(left: String, right: String): Double {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return 0.0
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return 1.0
        }
        val longest = max(normalizedLeft.length, normalizedRight.length)
        return 1.0 - levenshteinDistance(normalizedLeft, normalizedRight).toDouble() / longest
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}
