package com.guiamed.data

import com.google.gson.annotations.SerializedName

data class VerificarRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("texto_ocr") val textoOcr: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class VerificarResponse(
    @SerializedName("nome") val nome: String,
    @SerializedName("miligramas") val miligramas: String?,
    @SerializedName("validade") val validade: String?,
    @SerializedName("vencido") val vencido: Boolean,
    @SerializedName("alerta_alergia") val alertaAlergia: Boolean,
    @SerializedName("componentes") val componentes: List<String> = emptyList(),
    @SerializedName("posologia") val posologia: String?,
    @SerializedName("mensagem") val mensagem: String?,
    @SerializedName("perigo") val perigo: Boolean = false
)

data class AlergiaRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("nome_alergia") val nomeAlergia: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class AlergiaResponse(
    @SerializedName("sucesso") val sucesso: Boolean,
    @SerializedName("mensagem") val mensagem: String,
    @SerializedName("alergias_registradas") val alergiasRegistradas: List<String> = emptyList()
)

data class ReceitaRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("texto_ocr") val textoOcr: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class ReceitaResponse(
    @SerializedName("medicamentos") val medicamentos: List<MedicamentoReceita>,
    @SerializedName("posologia_completa") val posologiaCompleta: String,
    @SerializedName("horarios") val horarios: List<String>,
    @SerializedName("mensagem") val mensagem: String?
)

data class MedicamentoReceita(
    @SerializedName("nome") val nome: String,
    @SerializedName("dosagem") val dosagem: String,
    @SerializedName("frequencia") val frequencia: String,
    @SerializedName("duracao") val duracao: String?,
    @SerializedName("instrucoes") val instrucoes: String?
)