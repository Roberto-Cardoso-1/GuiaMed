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
internal class OfflineMedicationProcessor internal constructor(
    dcbNames: List<String>,
    private val medicationByEan: Map<String, EanMedication> = emptyMap(),
) {

    internal data class EanMedication(val product: String, val substances: List<String>)

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

        fun loadDcbNames(context: Context): List<String> = runCatching {
                val listType = object : TypeToken<List<DcbCatalogItem>>() {}.type
                context.assets.open("dcb_medicamentos.json").bufferedReader().use { reader ->
                    val items: List<DcbCatalogItem> = Gson().fromJson(reader, listType)
                    items.map { it.nome }
                }
            }.getOrDefault(emptyList())

        fun loadEanNames(context: Context): Map<String, EanMedication> = try {
                context.assets.open("medicamentos_ean.tsv").bufferedReader().useLines { lines ->
                    lines.filterNot { it.startsWith('#') }.associate { row ->
                        val (ean, product, substances) = row.split('	', limit = 3)
                        ean to EanMedication(product, substances.split(';'))
                    }
                }
            } catch (_: Exception) {
                emptyMap()
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
            name = "Enalapril",
            aliases = listOf(
                "ENALAPRIL",
                "ENAPRIL",
                "MALEATO DE ENALAPRIL",
                "MALEATO DE ENAPRIL",
                "RENITEC",
            ),
            components = listOf("enalapril", "maleato de enalapril"),
        ),
        Medicine(
            name = "Ibuprofeno",
            aliases = listOf("IBUPROFENO", "ALIVIUM", "ADVIL"),
            components = listOf("ibuprofeno"),
        ),
        Medicine(
            name = "Metformina",
            aliases = listOf(
                "METFORMINA",
                "CLORIDRATO DE METFORMINA",
                "GLIFAGE",
                "GLIFAGE XR",
            ),
            components = listOf("metformina", "cloridrato de metformina"),
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

    private val curatedAliases = curatedMedicines.flatMap { medicine ->
        (medicine.aliases + medicine.name).distinct().map { alias -> normalize(alias) to medicine }
    }
    private val curatedByName = curatedAliases.toMap()
    private val curatedPatterns = curatedAliases.map { (alias, medicine) ->
        Regex("""(?<![A-Z0-9])${Regex.escape(alias)}(?![A-Z0-9])""") to medicine
    }
    private val exactDcbIndex = dcbNames.asSequence()
        .map(String::trim)
        .map { name -> normalize(name) to name }
        .filter { (normalized, _) -> normalized.length >= 4 }
        .distinctBy { (normalized, _) -> normalized }
        .associate { (normalized, name) ->
            normalized to (curatedByName[normalized] ?: Medicine(name, listOf(name), listOf(name)))
        }
    private val fuzzyDcbByLength = exactDcbIndex.entries.asSequence()
        .filter { (name, _) -> " " !in name && name.length >= 7 }
        .groupBy { (name, _) -> name.length }

    internal fun verify(
        textOcr: String,
        allergies: Collection<String>,
        referenceDate: Calendar = Calendar.getInstance(),
        ean: String? = null,
        allowFuzzy: Boolean = true,
    ): VerificarResponse {
        val textMedicine = findMedicineFromOcr(textOcr, allowFuzzy)
        val barcodeMedicine = ean?.let(medicationByEan::get)?.let { record ->
            val knownBySubstance = record.substances.flatMap { substance ->
                val normalizedSubstance = normalize(substance)
                curatedByName[normalizedSubstance]?.let { it.aliases + it.components }.orEmpty()
            }
            Medicine(record.product, listOf(record.product), (record.substances + knownBySubstance).distinct())
        }
        val agreesWithBarcode = barcodeMedicine != null && textMedicine != null &&
            (barcodeMedicine.aliases + barcodeMedicine.components).any { value ->
                val name = normalize(value)
                (textMedicine.aliases + textMedicine.components).any { normalize(it) == name }
            }
        val conflicting = barcodeMedicine != null && textMedicine != null && !agreesWithBarcode
        val medicine = if (conflicting) null else barcodeMedicine ?: textMedicine
        val possibleAllergy = conflicting && listOfNotNull(textMedicine, barcodeMedicine).any {
            hasAllergy(allergies, it.aliases + it.components + it.name)
        }
        val dosage = if (textMedicine != null && (barcodeMedicine == null || agreesWithBarcode)) {
            extractPackageDosage(textOcr, textMedicine, allowFuzzy)
        } else null
        val expiration = ExpirationReader.findDates(textOcr).singleOrNull()
        val validity = expiration?.label
        val expired = expiration?.isExpired(referenceDate) == true

        if (medicine == null) {
            return VerificarResponse(
                nome = "Medicamento não identificado com segurança",
                miligramas = dosage,
                validade = validity,
                vencido = expired,
                alertaAlergia = possibleAllergy,
                componentes = emptyList(),
                posologia = null,
                mensagem = "Não consegui identificar o medicamento com segurança. Reposicione a caixa e tente novamente.",
                perigo = expired || possibleAllergy,
            )
        }

        val allergyAlert = hasAllergy(
            allergies,
            medicine.aliases + medicine.components + medicine.name,
        )

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

    internal fun hasAllergy(allergies: Collection<String>, targets: Collection<String>): Boolean =
        allergies.any { allergy ->
            targets.any { target -> similarity(allergy, target) >= 0.78 }
        }

    internal fun recipeAllergyAlerts(
        medicines: List<MedicamentoReceita>,
        allergies: Collection<String>,
    ): List<String> = medicines.mapNotNull { item ->
        val normalized = normalize(item.nome)
        val medicine = curatedByName[normalized] ?: exactDcbIndex[normalized]
        val targets = medicine?.let { it.aliases + it.components + it.name } ?: listOf(item.nome)
        item.nome.takeIf { hasAllergy(allergies, targets) }
    }

    internal fun corroboratedRecipeName(name: String, textOcr: String): String? {
        val normalized = normalize(name)
        val medicine = curatedByName[normalized] ?: exactDcbIndex[normalized] ?: return null
        val text = normalize(textOcr)
        // Permit separated letters (NO VAL GI NA), never an approximate unrelated name.
        val supported = (medicine.aliases + medicine.name).any { alias ->
            val letters = normalize(alias).filterNot(Char::isWhitespace)
            val pattern = letters.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            Regex("(?<![A-Z0-9])$pattern(?![A-Z0-9])").containsMatchIn(text)
        }
        return medicine.name.takeIf { supported }
    }

    internal fun prescriptionEvidence(name: String, textOcr: String): MedicamentoReceita? {
        val lines = textOcr.lines()
        val index = lines.indexOfFirst { corroboratedRecipeName(name, it) != null }
        if (index < 0) return null
        val canonical = corroboratedRecipeName(name, lines[index]) ?: return null
        val block = mutableListOf("$canonical ${lines[index]}")
        // Only explicit continuation instructions belong to this item. An unrecognized
        // neighbor or a bare dose on another line must never supply this item's fields.
        val continuation = Regex("^(TOMAR|TOME|USAR|USE|ADMINISTRAR|A CADA|DE \\d|POR \\d|DURACAO|HORARIOS)\\b")
        for (line in lines.drop(index + 1).take(3)) {
            if (!continuation.containsMatchIn(normalize(line)) || parseRecipe(line).medicamentos.isNotEmpty()) break
            block += line
        }
        return parseRecipe(block.joinToString("\n")).medicamentos
            .singleOrNull { normalize(it.nome) == normalize(canonical) }
    }

    internal fun hasUnrecognizedPrescriptionLine(textOcr: String, local: ReceitaResponse): Boolean {
        val known = local.medicamentos.map { normalize(it.nome) }.toSet()
        return textOcr.lineSequence().any { line ->
            val normalized = normalize(line)
            // A drug-like line with a dose is useful remote evidence even if another
            // already-recognized item has all its fields. Administrative prose is skipped.
            extractDosage(normalized) != null && Regex("[A-Z]{3,}").containsMatchIn(normalized) &&
                !Regex("^(TOMAR|TOME|USAR|USE|ADMINISTRAR)\\b").containsMatchIn(normalized) &&
                parseRecipe(line).medicamentos.none { normalize(it.nome) in known }
        }
    }

    internal fun parseRecipe(textOcr: String): ReceitaResponse {
        val normalizedLines = medicationLines(textOcr)

        val detected = normalizedLines.map { line ->
            findMedicine(line, prescription = true)
        }
        val medicines = mutableListOf<MedicamentoReceita>()
        val seen = mutableSetOf<String>()

        detected.forEachIndexed { lineIndex, medicine ->
            if (medicine == null || !seen.add(medicine.name)) return@forEachIndexed

            val context = medicationBlock(normalizedLines, lineIndex, medicine, allowFuzzy = true)

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

    private fun extractPackageDosage(
        textOcr: String,
        medicine: Medicine,
        allowFuzzy: Boolean,
    ): String? {
        val lines = medicationLines(textOcr)
        val matchedBlocks = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (findMedicine(line, allowFuzzy = allowFuzzy)?.name == medicine.name) {
                matchedBlocks += medicationBlock(lines, index, medicine, allowFuzzy, packageLayout = true)
                index += 1
                continue
            }
            if (index + 1 < lines.size &&
                findMedicine(line, allowFuzzy = false) == null &&
                findMedicine(lines[index + 1], allowFuzzy = false) == null
            ) {
                val combined = "${line} ${lines[index + 1]}"
                if (findMedicine(combined, allowFuzzy = allowFuzzy)?.name == medicine.name) {
                    val synthetic = lines.toMutableList().apply {
                        this[index] = combined
                        removeAt(index + 1)
                    }
                    matchedBlocks += medicationBlock(synthetic, index, medicine, allowFuzzy, packageLayout = true)
                    index += 2
                    continue
                }
            }
            index += 1
        }
        val candidates = matchedBlocks
            .flatMap(::extractAllDosages)
            .distinctBy { it.value }
        return resolvePrioritizedDosage(candidates)
    }

    private fun medicationLines(text: String): List<String> {
        val rawSegments = text.splitToSequence(Regex("""[\n;]+"""))
            .flatMap { segment ->
                segment.trim().split(
                    Regex("""\s+(?=(?:\(\d{1,3}\)|\[\d{1,3}\]|\d{1,3}\s*[.)\-:]|[A-Za-z][.)]|[-*•●▪‣–—])\s*[^\W\d_])"""),
                ).asSequence()
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

        // Une número grande e unidade separados em linhas adjacentes pelo OCR da caixa (ex.: "5OO\nmg").
        val mergedSegments = mutableListOf<String>()
        var index = 0
        while (index < rawSegments.size) {
            val current = rawSegments[index]
            val next = rawSegments.getOrNull(index + 1)
            if (next != null && isStandaloneNumberSegment(current) && isStandaloneUnitSegment(next)) {
                mergedSegments += "$current $next"
                index += 2
            } else {
                mergedSegments += current
                index += 1
            }
        }
        return mergedSegments.map(::normalize).filter(String::isNotBlank)
    }

    private fun isStandaloneNumberSegment(segment: String): Boolean {
        val ascii = stripAccentsToUpper(segment).trim()
        return Regex("""^[0-9OIL]{1,5}(?:[.,][0-9OIL]{1,3})?$""").matches(ascii)
    }

    private fun isStandaloneUnitSegment(segment: String): Boolean {
        val ascii = stripAccentsToUpper(segment).trim()
        return Regex("""^(?:MCG|MG|G|ML|UI|RNG|M9|MQ)(?:\s*/\s*(?:\d{1,3}\s*)?(?:MCG|MG|G|ML|DOSE))?$""").matches(ascii)
    }

    private fun medicationBlock(
        lines: List<String>,
        start: Int,
        medicine: Medicine,
        allowFuzzy: Boolean,
        packageLayout: Boolean = false,
    ): String {
        val first = textFromIdentity(lines[start], medicine, allowFuzzy) ?: return ""
        val block = mutableListOf<String>()

        if (packageLayout) {
            // Na frente da caixa a dosagem isolada pode vir acima de descritores neutros e do nome.
            for (back in start - 1 downTo maxOf(0, start - 3)) {
                val previousLine = lines[back]
                if (findMedicine(previousLine, allowFuzzy = allowFuzzy) != null) break
                if (isPackageDescriptorLine(previousLine)) continue
                if (isPureStandaloneDosageLine(previousLine)) {
                    val lineBeforeDose = lines.getOrNull(back - 1)
                    if (lineBeforeDose == null || isPackageDescriptorLine(lineBeforeDose)) {
                        block += previousLine
                    }
                    break
                }
                break
            }
        }

        block += first
        val lookahead = if (packageLayout) 7 else 4
        for (index in start + 1 until minOf(start + lookahead, lines.size)) {
            val line = lines[index]
            val next = findMedicine(line, allowFuzzy = allowFuzzy)
            if (next != null) {
                if (next.name != medicine.name) break
                block += textFromIdentity(line, medicine, allowFuzzy) ?: break
            } else {
                val prefix = block.joinToString(" ")
                val identityContinuation = (medicine.aliases + medicine.name).any { alias ->
                    val normalizedAlias = normalize(alias)
                    if (!normalizedAlias.startsWith("$prefix ")) false else {
                        val suffix = normalizedAlias.removePrefix("$prefix ")
                        line == suffix || (line.startsWith("$suffix ") &&
                            (isMedicationContinuation(line.removePrefix(suffix).trim()) ||
                                (packageLayout && isPackageDescriptorLine(line.removePrefix(suffix).trim()))))
                    }
                }
                val validContinuation = isMedicationContinuation(line) ||
                    (packageLayout && isPackageDescriptorLine(line))
                // Unknown headings (with or without an item marker) terminate the block.
                if (!identityContinuation && !validContinuation) break
                block += line
            }
        }
        return block.joinToString(" ")
    }

    private fun isMedicationContinuation(line: String): Boolean {
        val content = line.replace(Regex("""^[-–—•]\s*"""), "")
        return Regex(
            """^(?:\d{1,5}(?:[.,]\d+)?\s*(?:MCG|MG|G|ML|UI)(?:\s*/\s*(?:\d{1,3}\s*)?(?:MCG|MG|G|ML|DOSE))?\b|""" +
                """TOMAR\b|TOME\b|USAR\b|USE\b|APLICAR\b|INGERIR\b|ADMINISTRAR\b|USO\b|""" +
                """FREQUENCIA\b|POSOLOGIA\b|DURACAO\b|HORARIOS?\b|A CADA\b|DE \d{1,2}\b|""" +
                """POR \d|DURANTE \d|\d{1,2}\s*/\s*\d{1,2}\s*H\b|""" +
                """(?:\d{1,2}|UMA|DUAS|TRES)\s*(?:X|VEZ|VEZES)\b|""" +
                """TODOS OS DIAS\b|DIARIAMENTE\b|PELA MANHA\b|[AÀ] NOITE\b|""" +
                """DE MANHA\b|DE TARDE\b|A TARDE\b|DE NOITE\b|AO DEITAR\b|""" +
                """ANTES DE DORMIR\b|APOS AS? REFEICOES?\b|ANTES DAS? REFEICOES?\b|EM JEJUM\b|VIA ORAL\b)""",
        ).containsMatchIn(content)
    }

    private fun isPackageDescriptorLine(line: String): Boolean {
        if (Regex("""^(?:\(\d{1,3}\)|\[\d{1,3}\]|\d{1,3}\s*[.)\-:]|[A-Z][.)]|[-*•●▪‣–—])\s*""").containsMatchIn(line)) {
            return false
        }
        return Regex(
            """^(?:MEDICAMENTO\s+GENERICO|GENERICO|SIMILAR|REFERENCIA|LEI\s+N.*|""" +
                """USO\s+(?:ORAL|ADULTO|PEDIATRICO|ADULTO\s+E\s+PEDIATRICO|TOPICO|EXTERNO|DERMATOLOGICO|OFTALMICO|NASAL)|""" +
                """VIA\s+(?:ORAL|TOPICA)|SOLUCAO\s+(?:ORAL|INJETAVEL|NASAL|OFTALMICA)|""" +
                """SUSPENSAO\s+ORAL|EMULSAO\s+ORAL|GOTAS|XAROPE|CREME|POMADA|GEL|SPRAY|AEROSSOL|""" +
                """COMPRIMIDOS?(?:\s+(?:REVESTIDOS?|EFERVESCENTES?|MASTIGAVEIS?|SUBLINGUAIS?|SIMPLES|ORODISPERSIVEIS?))?|""" +
                """CAPSULAS?(?:\s+(?:DURAS?|MOLES?|GELATINOSAS?))?|""" +
                """(?:(?:CONTEM|EMBALAGEM\s+COM)\s+)?\d{1,3}\s+""" +
                """(?:COMPRIMIDOS?|CAPSULAS?|DRAGEAS?|AMPOLAS?|FLACONETES?|FRASCOS?|SACHES?|ENVELOPES?|ADESIVOS?|UNIDADES?)""" +
                """(?:\s+(?:REVESTIDOS?|DURAS?|MOLES?))?)$""",
        ).matches(line.trim())
    }

    private fun isPureStandaloneDosageLine(line: String): Boolean =
        Regex("""^\d{1,5}(?:[.,]\d+)?\s*(?:MCG|MG|G|ML|UI)(?:\s*/\s*(?:\d{1,3}\s*)?(?:MCG|MG|G|ML|DOSE))?$""")
            .matches(line.trim())

    private fun stripAccentsToUpper(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .uppercase(Locale.ROOT)

    private fun textFromIdentity(line: String, medicine: Medicine, allowFuzzy: Boolean): String? {
        val exactStart = (medicine.aliases + medicine.name).mapNotNull { alias ->
            Regex("(?<![A-Z0-9])${Regex.escape(normalize(alias))}(?![A-Z0-9])")
                .find(line)?.range?.first
        }.minOrNull()
        if (exactStart != null) return line.substring(exactStart)
        if (!allowFuzzy) return null
        // Locate an already accepted fuzzy identity without changing its thresholds.
        val words = Regex("[A-Z0-9]+").findAll(line).toList()
        for (index in words.indices) {
            for (size in 1..minOf(2, words.size - index)) {
                val start = words[index].range.first
                val candidate = line.substring(start, words[index + size - 1].range.last + 1)
                if (findMedicine(candidate)?.name == medicine.name) return line.substring(start)
            }
        }
        return null
    }

    internal fun normalize(value: String): String {
        var text = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .uppercase(Locale.ROOT)

        fun fixDigits(raw: String): String = raw
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')

        fun canonicalUnit(rawUnit: String): String = when (rawUnit) {
            "RNG", "M9", "MQ" -> "MG"
            else -> rawUnit
        }

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
        // Milhar em doses grandes (ex.: 50.000 UI) e zeros separados em fonte gigante (ex.: 5 00 mg).
        text = Regex("""\b([0-9OIL]{1,3})\.([O0]{3})\s*(MCG|MG|ML|G|UI|RNG|M9|MQ)\b""")
            .replace(text) { match ->
                fixDigits(match.groupValues[1]) + "000 " + canonicalUnit(match.groupValues[3])
            }
        text = Regex("""\b([1-9])\s+([0O]{2,3})\s*(MCG|MG|ML|G|UI|RNG|M9|MQ)\b""")
            .replace(text) { match ->
                match.groupValues[1] + fixDigits(match.groupValues[2]) + " " + canonicalUnit(match.groupValues[3])
            }
        text = Regex("""\b([0-9OIL]+(?:[.,][0-9OIL]+)?)\s*(MCG|MG|ML|G|UI|RNG|M9|MQ)\b""")
            .replace(text) { match ->
                fixDigits(match.groupValues[1]) + " " + canonicalUnit(match.groupValues[2])
            }
        text = Regex("""\b([0-9OIL]{1,2})\s*[/\-.]\s*([0-9OIL]{2,4})\b""")
            .replace(text) { match ->
                fixDigits(match.groupValues[1]) + "/" + fixDigits(match.groupValues[2])
            }

        text = text.split(Regex("""\s+""")).joinToString(" ") { token ->
            if (token.count(Char::isLetter) >= 2) {
                token.replace('0', 'O')
                    .replace(Regex("""(?<=[A-Z])1(?=[A-Z])"""), "I")
            } else {
                token
            }
        }
        return text.trim()
    }

    private fun findMedicineFromOcr(textOcr: String, allowFuzzy: Boolean): Medicine? {
        val lines = medicationLines(textOcr)

        // A evidência mais forte na embalagem é o nome junto da dosagem. Limitar
        // a busca a esse contexto evita tratar excipientes como princípio ativo.
        val dosageContexts = buildList {
            lines.forEachIndexed { index, line ->
                if (extractDosage(line) != null) {
                    add(line)
                    if (index > 0) add("${lines[index - 1]} $line")
                    if (index > 1) add("${lines[index - 2]} ${lines[index - 1]} $line")
                    if (index + 1 < lines.size) add("$line ${lines[index + 1]}")
                }
            }
        }
        val dosageMatches = dosageContexts.mapNotNull { findMedicine(it, allowFuzzy = false) }
            .distinctBy { it.name }
        if (dosageMatches.size == 1) return dosageMatches.first()
        if (dosageMatches.size > 1) return null

        return findMedicine(normalize(textOcr), allowFuzzy = allowFuzzy)
    }

    private fun findMedicine(
        normalizedText: String,
        prescription: Boolean = false,
        allowFuzzy: Boolean = true,
    ): Medicine? {
        // Nunca escolha arbitrariamente entre dois medicamentos lidos na mesma
        // imagem. Isso pode acontecer quando passes diferentes do OCR discordam.
        val curatedExactMatches = curatedPatterns.asSequence()
            .filter { (pattern, _) -> pattern.containsMatchIn(normalizedText) }
            .map { it.second }
            .distinctBy { it.name }
            .toList()
        if (curatedExactMatches.size == 1) return curatedExactMatches.first()
        if (curatedExactMatches.size > 1) return null

        val exactDcbMatches = findExactDcbMedicines(normalizedText).distinctBy { it.name }
        if (exactDcbMatches.size == 1) return exactDcbMatches.first()
        if (exactDcbMatches.size > 1) return null
        if (!allowFuzzy) return null

        if (prescription) {
            if (extractDosage(normalizedText) == null) return null
        }
        val words = Regex("""[A-Z]{3,}""")
            .findAll(normalizedText)
            .map { it.value }
            .toList()
        val candidates = words + words.zipWithNext { left, right -> "$left $right" }

        var bestMedicine: Medicine? = null
        var bestScore = 0.0
        var secondScore = 0.0
        for ((alias, medicine) in curatedAliases) {
            for (candidate in candidates) {
                val score = normalizedSimilarity(alias, candidate)
                if (score > bestScore) {
                    if (bestMedicine?.name != medicine.name) secondScore = bestScore
                    bestScore = score
                    bestMedicine = medicine
                } else if (bestMedicine?.name != medicine.name && score > secondScore) {
                    secondScore = score
                }
            }
        }

        if (bestScore >= 0.92 && bestScore - secondScore >= 0.08) return bestMedicine
        findDcbMedicine(normalizedText)?.let { return it }
        return null
    }

    private fun findExactDcbMedicines(normalizedText: String): Set<Medicine> {
        if (exactDcbIndex.isEmpty()) return emptySet()

        val words = Regex("""[A-Z0-9]{2,}""")
            .findAll(normalizedText)
            .map { it.value }
            .filter { word -> word.any(Char::isLetter) }
            .toList()

        val exactMatches = linkedSetOf<Medicine>()
        for (size in 1..minOf(6, words.size)) {
            for (index in 0..words.size - size) {
                val candidate = words.subList(index, index + size).joinToString(" ")
                exactDcbIndex[candidate]?.let(exactMatches::add)
            }
        }
        return exactMatches
    }

    private fun findDcbMedicine(normalizedText: String): Medicine? {
        if (exactDcbIndex.isEmpty()) return null

        val words = Regex("""[A-Z0-9]{2,}""")
            .findAll(normalizedText)
            .map { it.value }
            .filter { word -> word.any(Char::isLetter) }
            .toList()

        val candidates = words.asSequence()
            .filter { it.length >= 7 && it !in nonMedicineWords }
            .toSet()
        var bestMedicine: Medicine? = null
        var bestScore = 0.0
        var secondScore = 0.0

        candidates.forEach { candidate ->
            val tolerance = max(2, (candidate.length * 0.18).roundToInt())
            for (length in candidate.length - tolerance..candidate.length + tolerance) {
                fuzzyDcbByLength[length].orEmpty().forEach { (officialName, medicine) ->
                    val score = normalizedSimilarity(officialName, candidate)
                    if (score > bestScore) {
                        if (bestMedicine?.name != medicine.name) secondScore = bestScore
                        bestScore = score
                        bestMedicine = medicine
                    } else if (bestMedicine?.name != medicine.name && score > secondScore) {
                        secondScore = score
                    }
                }
            }
        }

        return bestMedicine?.takeIf { bestScore >= 0.90 && bestScore - secondScore >= 0.08 }
    }

    private data class DosageCandidate(val value: String, val priority: Int)

    private fun extractDosage(normalizedText: String): String? =
        resolvePrioritizedDosage(extractAllDosages(normalizedText))

    private fun extractAllDosages(normalizedText: String): List<DosageCandidate> {
        val regex = Regex("""\b(\d{1,5}(?:[.,]\d+)?)\s*(MCG|MG|G|ML|UI)(?:\s*/\s*(\d{1,3}\s*)?(MCG|MG|G|ML|DOSE))?\b""")
        return regex.findAll(normalizedText).map { match ->
            val number = match.groupValues[1].replace(',', '.')
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            val denomNum = match.groupValues[3].replace(Regex("""\s+"""), "")
            val denomUnit = match.groupValues[4].lowercase(Locale.ROOT)
            val value = if (denomUnit.isNotEmpty()) {
                "$number$unit/$denomNum$denomUnit"
            } else {
                "$number$unit"
            }
            val priority = if (denomUnit.isNotEmpty() || unit != "ml") 2 else 1
            DosageCandidate(value, priority)
        }.toList()
    }

    private fun resolvePrioritizedDosage(candidates: List<DosageCandidate>): String? {
        if (candidates.isEmpty()) return null
        val maxPriority = candidates.maxOf { it.priority }
        return candidates
            .filter { it.priority == maxPriority }
            .map { it.value }
            .distinct()
            .singleOrNull()
    }

    private fun extractRecipeFrequency(normalizedText: String): String {
        val patterns = listOf(
            """\b\d{1,2}\s*/\s*\d{1,2}\s*H\b""",
            """\bA CADA \d{1,2} HORAS?\b""",
            """\bDE \d{1,2} EM \d{1,2} HORAS?\b""",
            """\b(?:A CADA|DE \d{1,2} EM)\s*\d{1,2}\s*H\b""",
            """\b(?:\d{1,2}|UMA|DUAS|TRES)\s*(?:X|VEZ|VEZES)\s*(?:AO|POR)\s*DIA\b""",
            """\b(?:\d{1,2}\s*(?:X|VEZ|VEZES)\s*/\s*DIA|TODOS OS DIAS|DIARIAMENTE)\b""",
            """\b(?:PELA MANHA|A NOITE|AO DEITAR|ANTES DE DORMIR)\b""",
            """\b(?:DE MANHA|DE TARDE|A TARDE|DE NOITE)\b""",
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
            """\b(?:TOMAR|USAR|APLICAR|INGERIR|USO\s*:)\s*""" +
                """(?:\d{1,2}|UM|UMA|MEIO|MEIA)\s+""" +
                """(?:COMPRIMIDOS?|CP|CAPSULAS?|CAP|GOTAS?|ML|DOSES?|PUFFS?)\b""",
        ).find(normalizedText)?.value?.lowercase(Locale.forLanguageTag("pt-BR"))?.let(instructions::add)

        Regex(
            """\b(?:APOS AS? REFEICOES?|ANTES DAS? REFEICOES?|EM JEJUM|""" +
                """PELA MANHA|DE MANHA|A TARDE|DE TARDE|A NOITE|DE NOITE|""" +
                """AO DEITAR|ANTES DE DORMIR|VIA ORAL)\b""",
        ).find(normalizedText)?.value?.lowercase(Locale.forLanguageTag("pt-BR"))?.let { context ->
            if (context !in instructions) instructions += context
        }
        return instructions.joinToString(", ").ifBlank { null }
    }

    private fun similarity(left: String, right: String): Double =
        normalizedSimilarity(normalize(left), normalize(right))

    private fun normalizedSimilarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left.contains(right) || right.contains(left)) return 1.0
        val longest = max(left.length, right.length)
        return 1.0 - levenshteinDistance(left, right).toDouble() / longest
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
