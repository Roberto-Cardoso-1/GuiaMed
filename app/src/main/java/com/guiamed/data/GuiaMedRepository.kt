package com.guiamed.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Normalizer
import java.util.Calendar

class GuiaMedRepository(
    private val api: GuiaMedApi = GuiaMedApi.create(),
    private val useMock: Boolean = false
) {

    companion object {
        private const val NETWORK_ERROR_MESSAGE = "Sem conexão com o servidor, tentando novamente..."
        private val mockAllergies = mutableSetOf<String>()

        /** Cria instância com mock ativado somente para testes offline. */
        fun createWithMock() = GuiaMedRepository(useMock = true)
    }

    suspend fun verificarMedicamento(context: Context, textoOcr: String): Result<VerificarResponse> {
        if (useMock) return mockVerificarMedicamento(textoOcr)
        
        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.verificarMedicamento(VerificarRequest(deviceId = deviceId, textoOcr = textoOcr))
        }
    }

    suspend fun registrarAlergia(context: Context, nomeAlergia: String): Result<AlergiaResponse> {
        if (useMock) return mockRegistrarAlergia(nomeAlergia)
        
        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.registrarAlergia(AlergiaRequest(deviceId = deviceId, nomeAlergia = nomeAlergia))
        }
    }

    suspend fun lerReceita(context: Context, textoOcr: String): Result<ReceitaResponse> {
        if (useMock) return mockLerReceita(textoOcr)
        
        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.lerReceita(ReceitaRequest(deviceId = deviceId, textoOcr = textoOcr))
        }
    }

    private suspend fun <T> requestWithRetry(request: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return@withContext Result.success(request())
            } catch (error: Exception) {
                lastError = error
                if (attempt == 0) delay(750)
            }
        }
        Result.failure(IOException(NETWORK_ERROR_MESSAGE, lastError))
    }

    // MOCKS para teste offline
    private fun mockVerificarMedicamento(textoOcr: String): Result<VerificarResponse> {
        val normalized = normalize(textoOcr)
        val medicine = when {
            listOf("dipirona", "novalgina", "metamizol").any(normalized::contains) ->
                Triple("Dipirona Sódica", listOf("dipirona", "metamizol"), "dipirona")
            listOf("nimesulida", "nisulid").any(normalized::contains) ->
                Triple("Nimesulida", listOf("nimesulida"), "nimesulida")
            listOf("ibuprofeno", "alivium", "advil").any(normalized::contains) ->
                Triple("Ibuprofeno", listOf("ibuprofeno"), "ibuprofeno")
            listOf("paracetamol", "tylenol").any(normalized::contains) ->
                Triple("Paracetamol", listOf("paracetamol"), "paracetamol")
            else -> Triple("Medicamento não identificado com segurança", emptyList(), "")
        }
        val dosage = Regex("\\b(\\d{1,4}(?:[.,]\\d+)?)\\s*(mcg|mg|g|ml)\\b")
            .find(normalized)
            ?.let { "${it.groupValues[1].replace(',', '.')}${it.groupValues[2]}" }
        val validityMatch = Regex("\\b(?:validade|valid|val|vencimento|venc)\\s*[:\\-]?\\s*(0?[1-9]|1[0-2])[/\\-. ](\\d{2}|\\d{4})\\b")
            .find(normalized)
        val validity = validityMatch?.let {
            val year = it.groupValues[2].toInt().let { value -> if (value < 100) value + 2000 else value }
            "%02d/%04d".format(it.groupValues[1].toInt(), year)
        }
        val now = Calendar.getInstance()
        val expired = validityMatch?.let {
            val month = it.groupValues[1].toInt()
            val yearValue = it.groupValues[2].toInt()
            val year = if (yearValue < 100) yearValue + 2000 else yearValue
            year < now.get(Calendar.YEAR) ||
                (year == now.get(Calendar.YEAR) && month < now.get(Calendar.MONTH) + 1)
        } ?: false
        val allergyAlert = medicine.third.isNotBlank() && mockAllergies.any {
            normalize(it).contains(medicine.third) || medicine.third.contains(normalize(it))
        }
        
        return Result.success(VerificarResponse(
            nome = medicine.first,
            miligramas = dosage,
            validade = validity,
            vencido = expired,
            alertaAlergia = allergyAlert,
            componentes = medicine.second,
            posologia = null,
            mensagem = when {
                allergyAlert -> "Alerta de alergia detectado."
                expired -> "Medicamento vencido."
                dosage == null -> "Dosagem não encontrada na imagem."
                validity == null -> "Validade não encontrada na imagem."
                else -> null
            },
            perigo = allergyAlert || expired
        ))
    }

    private fun mockRegistrarAlergia(nomeAlergia: String): Result<AlergiaResponse> {
        mockAllergies.add(nomeAlergia.trim())
        return Result.success(AlergiaResponse(
            sucesso = true,
            mensagem = "Alergia a $nomeAlergia registrada com sucesso (modo offline)",
            alergiasRegistradas = mockAllergies.sorted()
        ))
    }

    private fun mockLerReceita(textoOcr: String): Result<ReceitaResponse> {
        return Result.success(ReceitaResponse(
            medicamentos = listOf(
                MedicamentoReceita("Dipirona", "500mg", "4/4h", "5 dias", "Após refeições"),
                MedicamentoReceita("Omeprazol", "20mg", "12/12h", "30 dias", "Em jejum")
            ),
            posologiaCompleta = "Dipirona 500mg 1 comprimido a cada 6 horas por 5 dias após refeições. Omeprazol 20mg 1 cápsula a cada 12 horas por 30 dias em jejum.",
            horarios = listOf("06:00", "12:00", "18:00", "00:00"),
            mensagem = "Receita processada offline (mock)"
        ))
    }

    private fun normalize(value: String): String {
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        normalized = Regex("\\b([0-9oil]+(?:[.,][0-9oil]+)?)\\s*(mcg|mg|g|ml)\\b")
            .replace(normalized) { match ->
                val number = match.groupValues[1]
                    .replace('o', '0')
                    .replace('i', '1')
                    .replace('l', '1')
                "$number ${match.groupValues[2]}"
            }
        return Regex("\\b\\w*0\\w*\\b").replace(normalized) { token ->
            if (token.value.any { it.isLetter() }) token.value.replace('0', 'o') else token.value
        }
    }

}
