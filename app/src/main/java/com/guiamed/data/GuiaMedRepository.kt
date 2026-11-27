package com.guiamed.data

import android.content.Context
import com.guiamed.GuiaMedApplication
import com.guiamed.BuildConfig
import com.guiamed.ocr.ReadBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    internal val remoteReserveMillis: Long get() = if (useRemoteApi) ReadBudget.HTTP_MILLIS else 0L

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

    internal suspend fun verificarMedicamento(
        context: Context,
        textoOcr: String,
        budget: ReadBudget = ReadBudget(ReadBudget.HTTP_MILLIS),
    ): Result<VerificarResponse> {
        val localResponse = verificarLocal(context, textoOcr)
        if (!useRemoteApi || localResponse.perigo ||
            !localResponse.nome.startsWith("Medicamento não identificado")
        ) return Result.success(localResponse)

        val offlineProcessor = processor(context)
        val allergies = withContext(Dispatchers.IO) { LocalAllergyStore.getAll(context) }

        val remote = boundedRequest(budget) {
                val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
                api.verificarMedicamento(VerificarRequest(deviceId = deviceId, textoOcr = textoOcr))
        }.getOrNull()
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
        }.getOrElse {
            if (it is CancellationException) throw it
            return Result.failure(it)
        }

        if (!useRemoteApi) return Result.success(localResponse)

        return boundedRequest(ReadBudget(ReadBudget.HTTP_MILLIS)) {
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

    internal suspend fun complementarReceita(
        context: Context,
        local: ReceitaResponse,
        budget: ReadBudget,
    ): ReceitaResponse {
        val text = local.textoOcrCompleto ?: return local
        if (!shouldComplementPrescription(context, local)) {
            return local
        }

        return boundedRequest(budget) {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(context)
            api.lerReceita(ReceitaRequest(deviceId = deviceId, textoOcr = text))
        }.map { remote ->
            withContext(Dispatchers.IO) {
                reconcileRemotePrescription(
                    local, remote, processor(context), LocalAllergyStore.getAll(context),
                )
            }
        }.getOrElse {
            local.copy(mensagem = appendMessage(local.mensagem, networkErrorMessage))
        }
    }

    internal fun shouldComplementPrescription(context: Context, local: ReceitaResponse): Boolean =
        useRemoteApi && local.medicamentosComAlerta.isEmpty() &&
            (local.needsPrescriptionReview() || processor(context).hasUnrecognizedPrescriptionLine(
                local.textoOcrCompleto.orEmpty(), local,
            ))

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

    private fun processor(context: Context): OfflineMedicationProcessor =
        (context.applicationContext as GuiaMedApplication).medicationProcessor
}
