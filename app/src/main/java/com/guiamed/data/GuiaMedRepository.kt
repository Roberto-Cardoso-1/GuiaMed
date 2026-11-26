package com.guiamed.data

import android.content.Context
import com.guiamed.GuiaMedApplication
import com.guiamed.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    internal suspend fun verificarLocal(
        context: Context,
        textoOcr: String,
        ean: String? = null,
        allowFuzzy: Boolean = true,
    ): VerificarResponse {
        val offlineProcessor = processor(context)
        return withContext(Dispatchers.IO) {
            offlineProcessor.verify(
                textoOcr,
                LocalAllergyStore.getAll(context),
                ean = ean,
                allowFuzzy = allowFuzzy,
            )
        }
    }

    suspend fun verificarMedicamento(context: Context, textoOcr: String): Result<VerificarResponse> {
        val localResponse = verificarLocal(context, textoOcr)
        if (!useRemoteApi || localResponse.perigo ||
            !localResponse.nome.startsWith("Medicamento não identificado")
        ) return Result.success(localResponse)

        val offlineProcessor = processor(context)
        val allergies = withContext(Dispatchers.IO) { LocalAllergyStore.getAll(context) }

        val remote = withTimeoutOrNull(2_500) {
            requestWithRetry {
                val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
                api.verificarMedicamento(VerificarRequest(deviceId = deviceId, textoOcr = textoOcr))
            }.getOrNull()
        }
        return Result.success(
            if (remote != null) reconcileMedicationSafety(localResponse, remote, allergies, offlineProcessor)
            else localResponse.copy(mensagem = appendMessage(localResponse.mensagem, networkErrorMessage)),
        )
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
        }.recover {
            localResponse.copy(
                mensagem = requireNotNull(appendMessage(localResponse.mensagem, networkErrorMessage)),
            )
        }
    }

    internal suspend fun lerReceitaLocal(context: Context, textoOcr: String): ReceitaResponse =
        withContext(Dispatchers.IO) {
            val processor = processor(context)
            val result = processor.parseRecipe(textoOcr)
            result.copy(
                textoOcrCompleto = textoOcr,
                medicamentosComAlerta = processor.recipeAllergyAlerts(
                    result.medicamentos,
                    LocalAllergyStore.getAll(context),
                ),
            )
        }

    internal suspend fun complementarReceita(context: Context, local: ReceitaResponse): ReceitaResponse {
        val text = local.textoOcrCompleto ?: return local
        if (!useRemoteApi || local.medicamentosComAlerta.isNotEmpty() || !local.needsPrescriptionReview()) {
            return local
        }

        return requestWithRetry {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.lerReceita(ReceitaRequest(deviceId = deviceId, textoOcr = text))
        }.map { remote ->
            val selected = selectPrescriptionRead(local, remote) ?: local
            withContext(Dispatchers.IO) {
                selected.copy(
                    textoOcrCompleto = text,
                    medicamentosComAlerta = processor(context).recipeAllergyAlerts(
                        selected.medicamentos,
                        LocalAllergyStore.getAll(context),
                    ),
                )
            }
        }.getOrElse {
            local.copy(mensagem = appendMessage(local.mensagem, networkErrorMessage))
        }
    }

    private fun reconcileMedicationSafety(
        local: VerificarResponse,
        remote: VerificarResponse,
        allergies: Collection<String>,
        offlineProcessor: OfflineMedicationProcessor,
    ): VerificarResponse {
        val localIdentified = !local.nome.startsWith("Medicamento não identificado", ignoreCase = true)
        val remoteIdentified = !remote.nome.startsWith("Medicamento não identificado", ignoreCase = true)
        val conflictingNames = localIdentified && remoteIdentified &&
            offlineProcessor.normalize(local.nome) != offlineProcessor.normalize(remote.nome)
        val selected = if (conflictingNames) local else remote
        val localAllergyAlert = offlineProcessor.hasAllergy(
            allergies,
            selected.componentes + selected.nome,
        )
        val allergyAlert = selected.alertaAlergia || local.alertaAlergia || localAllergyAlert
        val expired = selected.vencido || local.vencido
        val conflictMessage = "Resultados local e remoto divergentes; mantida a identificação local por segurança."
            .takeIf { conflictingNames }
        return selected.copy(
            vencido = expired,
            alertaAlergia = allergyAlert,
            componentes = (selected.componentes + local.componentes).distinct(),
            perigo = selected.perigo || local.perigo || allergyAlert || expired,
            mensagem = appendMessage(selected.mensagem, conflictMessage),
        )
    }

    private fun appendMessage(current: String?, extra: String?): String? =
        listOfNotNull(current?.takeIf(String::isNotBlank), extra?.takeIf(String::isNotBlank))
            .distinct()
            .joinToString(" ")
            .ifBlank { null }

    private suspend fun processor(context: Context): OfflineMedicationProcessor =
        (context.applicationContext as GuiaMedApplication).medicationProcessor.await()

    private suspend fun <T> requestWithRetry(request: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    return@withContext Result.success(request())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    if (attempt == 0) delay(750)
                }
            }
            Result.failure(IOException(networkErrorMessage, lastError))
        }
}
