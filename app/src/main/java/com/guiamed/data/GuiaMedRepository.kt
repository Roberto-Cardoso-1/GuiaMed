package com.guiamed.data

import android.content.Context
import com.guiamed.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Repositório offline por padrão.
 *
 * O backend pode ser habilitado em um build de integração com
 * `-PGUIAMED_USE_REMOTE_API=true`. Mesmo nesse build, uma falha de rede retorna
 * o resultado processado localmente.
 */
class GuiaMedRepository(
    private val api: GuiaMedApi = GuiaMedApi.create(),
    private val useRemoteApi: Boolean = BuildConfig.USE_REMOTE_API,
) {

    companion object {
        private const val networkErrorMessage = "Sem conexão com o servidor. Usando processamento local."

        fun createWithRemoteApi() = GuiaMedRepository(useRemoteApi = true)
    }

    @Volatile
    private var localProcessor: OfflineMedicationProcessor? = null

    suspend fun verificarMedicamento(context: Context, textoOcr: String): Result<VerificarResponse> {
        val localResponse = withContext(Dispatchers.IO) {
            processor(context).verify(textoOcr, LocalAllergyStore.getAll(context))
        }
        if (!useRemoteApi) return Result.success(localResponse)

        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.verificarMedicamento(VerificarRequest(deviceId = deviceId, textoOcr = textoOcr))
        }.recover { localResponse }
    }

    suspend fun registrarAlergia(context: Context, nomeAlergia: String): Result<AlergiaResponse> {
        val localResponse = runCatching {
            withContext(Dispatchers.IO) {
                val (cleanedName, registeredAllergies) = LocalAllergyStore.add(context, nomeAlergia)
                AlergiaResponse(
                    sucesso = true,
                    mensagem = "Alergia a $cleanedName registrada com sucesso no aparelho.",
                    alergiasRegistradas = registeredAllergies,
                )
            }
        }.getOrElse { return Result.failure(it) }

        if (!useRemoteApi) return Result.success(localResponse)

        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.registrarAlergia(
                AlergiaRequest(
                    deviceId = deviceId,
                    nomeAlergia = LocalAllergyStore.cleanSpokenName(nomeAlergia),
                ),
            )
        }.recover { localResponse }
    }

    suspend fun lerReceita(context: Context, textoOcr: String): Result<ReceitaResponse> {
        val localResponse = withContext(Dispatchers.IO) {
            processor(context).parseRecipe(textoOcr)
        }
        if (!useRemoteApi) return Result.success(localResponse)

        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.lerReceita(ReceitaRequest(deviceId = deviceId, textoOcr = textoOcr))
        }.recover { localResponse }
    }

    private fun processor(context: Context): OfflineMedicationProcessor {
        localProcessor?.let { return it }
        return synchronized(this) {
            localProcessor ?: OfflineMedicationProcessor.fromContext(context.applicationContext)
                .also { localProcessor = it }
        }
    }

    private suspend fun <T> requestWithRetry(request: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    return@withContext Result.success(request())
                } catch (error: Exception) {
                    lastError = error
                    if (attempt == 0) delay(750)
                }
            }
            Result.failure(IOException(networkErrorMessage, lastError))
        }
}
