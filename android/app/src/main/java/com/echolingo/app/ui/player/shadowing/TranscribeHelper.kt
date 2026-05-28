package com.echolingo.app.ui.player.shadowing

import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File
import java.util.Base64

/** Retrofit interface for the transcribe endpoint. */
interface TranscribeApi {
    @POST("api/transcribe")
    suspend fun transcribe(@Body req: TranscribeRequest): TranscribeResponse

    data class TranscribeRequest(
        val audioBase64: String,
        val lang: String = "de",
    )
    data class TranscribeResponse(val transcript: String)
}

/**
 * Reads a recorded audio file, base64-encodes it, and calls /api/transcribe.
 * The server uses its own ECHOLINGO_GROQ_API_KEY from the environment.
 */
suspend fun transcribeAudio(
    serverBaseUrl: String,
    audioFile: File,
    lang: String = "de",
): String {
    val bytes = audioFile.readBytes()
    val b64   = Base64.getEncoder().encodeToString(bytes)

    val api = retrofit2.Retrofit.Builder()
        .baseUrl(serverBaseUrl.trimEnd('/') + "/")
        .client(
            okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(TranscribeApi::class.java)

    return api.transcribe(
        TranscribeApi.TranscribeRequest(
            audioBase64 = b64,
            lang        = lang,
        )
    ).transcript
}
