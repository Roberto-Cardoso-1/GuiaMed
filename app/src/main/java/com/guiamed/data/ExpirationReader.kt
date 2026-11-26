package com.guiamed.data

import java.text.Normalizer
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

internal data class ExpirationDate(val year: Int, val month: Int, val day: Int?) {
    val label: String
        get() = if (day == null) "%02d/%04d".format(Locale.ROOT, month, year)
        else "%02d/%02d/%04d".format(Locale.ROOT, day, month, year)

    fun isExpired(reference: Calendar): Boolean {
        val printed = year * 10_000 + month * 100 + (day ?: 31)
        val today = reference.get(Calendar.YEAR) * 10_000 +
            (reference.get(Calendar.MONTH) + 1) * 100 + reference.get(Calendar.DAY_OF_MONTH)
        return printed < today
    }
}

internal object ExpirationReader {
    private const val digit = "[0-9OIL]"
    private const val year = "(?:${digit}{4}|${digit}{2})"
    private const val separator = """[ \t]*[/.-][ \t]*"""
    private val labeledDate = Regex(
        """(?<![\p{L}\p{N}])(?:VALIDADE|VALID[OA][ \t]+ATE|VALID|VENCIMENTO|VENC|VAL|VTO|EXPIRA|EXP|V)""" +
            """[ \t]*[:.=\-]?[ \t]*(?:\r?\n[ \t]*)?""" +
            """(${digit}{1,2}$separator${digit}{1,2}$separator$year|""" +
            """${digit}{1,2}(?:$separator|[ \t]+)$year|${digit}{6}|${digit}{4})""" +
            """(?![\p{L}\p{N}]|[ \t]*[/.-][ \t]*${digit})"""
    )
    private val separators = Regex("""[ \t/.-]+""")

    fun findDates(ocr: String): Set<ExpirationDate> {
        val normalized = Normalizer.normalize(ocr, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .uppercase(Locale.ROOT)
        return labeledDate.findAll(normalized)
            .mapNotNull { parse(it.groupValues[1]) }
            .toSet()
    }

    private fun parse(match: String): ExpirationDate? {
        val digits = match.replace('O', '0').replace('I', '1').replace('L', '1')
        val parts = digits.split(separators)
        val fields = if (parts.size == 1) {
            listOf(digits.take(2), digits.drop(2))
        } else {
            parts
        }
        val month = fields[fields.size - 2].toInt()
        val printedYear = fields.last().toInt()
        val year = if (fields.last().length == 2) 2000 + printedYear else printedYear
        val day = if (fields.size == 3) fields.first().toInt() else null
        if (month !in 1..12 || year !in 1900..2199) return null
        if (day != null && day !in 1..GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)) {
            return null
        }
        return ExpirationDate(year, month, day)
    }
}

internal fun VerificarResponse.withReadExpiration(
    expiration: ExpirationDate,
    reference: Calendar = Calendar.getInstance(),
): VerificarResponse {
    val expired = expiration.isExpired(reference)
    return copy(
        validade = expiration.label,
        vencido = expired,
        perigo = perigo || alertaAlergia || expired,
        mensagem = mensagem?.replace("Validade não encontrada na imagem.", "")?.trim()?.ifBlank { null },
    )
}
