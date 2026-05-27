package com.echolingo.app.ui.player.shadowing

import com.echolingo.app.data.api.ApiFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File
import java.util.Base64

/** Retrofit interface for the transcribe endpoint. */
interface TranscribeApi {
    @POST("api/transcribe")
    suspend fun transcribe(@Body req: TranscribeRequest): TranscribeResponse

    data class TranscribeRequest(val audioBase64: String, val lang: String = "de")
    data class TranscribeResponse(val transcript: String)
}

/** Reads a recorded audio file, base64-encodes it, and calls /api/transcribe. */
suspend fun transcribeAudio(serverBaseUrl: String, audioFile: File, lang: String = "de"): String {
    val bytes   = audioFile.readBytes()
    val b64     = Base64.getEncoder().encodeToString(bytes)
    val api     = ApiFactory.create(serverBaseUrl)
            .let { retrofit ->
                // Re-use Retrofit instance but need the raw interface
                // ApiFactory.create returns EchoLingoApi; we create a separate instance here
                retrofit2.Retrofit.Builder()
                    .baseUrl(serverBaseUrl.trimEnd('/') + "/")
                    .client(
                        okhttp3.OkHttpClient.Builder()
                            .followRedirects(true)
                            .build()
                    )
                    .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                    .build()
                    .create(TranscribeApi::class.java)
            }
    return api.transcribe(TranscribeApi.TranscribeRequest(b64, lang)).transcript
}
