package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.exception.AppError;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Sends an audio file to Groq Whisper for transcription.
 * Used as the last-resort fallback when YouTube has no German captions
 * and yt-dlp subtitle download also fails.
 */
@Service
public class GroqService {

    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions";

    private static final Gson GSON = new Gson();
    private final OkHttpClient httpClient;
    private final AppConfig config;

    public GroqService(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    /**
     * Transcribes the given audio file using Groq Whisper.
     *
     * @param audioFilePath absolute path to the audio file (m4a / mp3 / wav)
     * @param language      ISO 639-1 language hint (e.g. "de")
     * @return raw transcript string, never null
     * @throws AppError if the API key is missing or the call fails
     */
    public String transcribeAudio(String audioFilePath, String language) {
        String apiKey = config.groqApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AppError(HttpStatus.SERVICE_UNAVAILABLE,
                    "Groq API key not configured (ECHOLINGO_GROQ_API_KEY).");
        }

        File audioFile = new File(audioFilePath);
        if (!audioFile.exists()) {
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Audio file not found: " + audioFilePath);
        }

        RequestBody fileBody = RequestBody.create(audioFile,
                MediaType.parse("audio/mpeg"));

        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "text")
                .build();

        Request req = new Request.Builder()
                .url(GROQ_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (resp.body() == null) {
                throw new AppError(HttpStatus.BAD_GATEWAY, "Groq returned an empty response.");
            }
            String responseBody = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new AppError(HttpStatus.BAD_GATEWAY,
                        "Groq API error " + resp.code() + ": " + responseBody);
            }
            // response_format=text → plain string response
            return responseBody.trim();
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not reach Groq API: " + e.getMessage());
        }
    }
}
