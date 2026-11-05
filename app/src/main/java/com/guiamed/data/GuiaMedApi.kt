package com.guiamed.data

import com.guiamed.BuildConfig
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface GuiaMedApi {

    @POST("/verificar")
    suspend fun verificarMedicamento(@Body request: VerificarRequest): VerificarResponse

    @POST("/alergias")
    suspend fun registrarAlergia(@Body request: AlergiaRequest): AlergiaResponse

    @POST("/receita")
    suspend fun lerReceita(@Body request: ReceitaRequest): ReceitaResponse

    companion object {
        private const val TIMEOUT_SECONDS = 15L

        fun create(): GuiaMedApi {
            val logging = HttpLoggingInterceptor().apply {
                // OCR e alergias sao dados sensiveis; nunca grave corpos no log.
                level = HttpLoggingInterceptor.Level.NONE
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val gson = GsonBuilder()
                .setLenient()
                .create()

            return Retrofit.Builder()
                .baseUrl(BuildConfig.API_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(GuiaMedApi::class.java)
        }
    }
}
