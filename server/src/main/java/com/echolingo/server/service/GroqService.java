package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.exception.AppError;
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
 *
 * BYOK support: if a per-request key is provided (from the Android app's Settings),
 * that key is used instead of the server-configured ECHOLINGO_GROQ_API_KEY.
 * This means the EC2 server can run with NO Groq key — every user brings their own.
 */
@Service
public class GroqService {

    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions";

    private final OkHttpClient httpClient;
    private final AppConfig config;

    public GroqService(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    /**
     * Transcribes an audio file via Groq Whisper.
     *
     * @param audioFilePath  absolute path to the audio file (m4a / mp3 / wav)
     * @param language       ISO 639-1 hint, e.g. "de"
     * @param keyOverride    BYOK key from the client; null or blank → use server config key
     */
    public String transcribeAudio(String audioFilePath, String language, String keyOverride) {
        // BYOK: prefer the per-request key, fall back to the server-configured key
        String apiKey = (keyOverride != null && !keyOverride.isBlank())
                ? keyOverride
                : config.groqApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            throw new AppError(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No Groq API key. Enter your key in the app under Settings → Groq API Key.");
        }

        File audioFile = new File(audioFilePath);
        if (!audioFile.exists()) {
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Audio file not found: " + audioFilePath);
        }

        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse("audio/mpeg"));
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
                if (resp.code() == 401) {
                    throw new AppError(HttpStatus.UNAUTHORIZED,
                            "Invalid Groq API key. Check Settings → Groq API Key.");
                }
                throw new AppError(HttpStatus.BAD_GATEWAY,
                        "Groq API error " + resp.code() + ": " + responseBody);
            }
            // response_format=text → plain text response, not JSON
            return responseBody.trim();
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not reach Groq API: " + e.getMessage());
        }
    }
}
