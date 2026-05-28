package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.exception.AppError;
import com.echolingo.server.model.Cue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);
    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;
    private final AppConfig config;

    public GroqService(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                // Groq can take a while for longer audio files
                .callTimeout(java.time.Duration.ofSeconds(120))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    /**
     * Transcribes an audio file using Groq's Whisper API.
     *
     * Uses response_format=verbose_json to get real per-segment timestamps from Whisper.
     * This is the ONLY correct way — using response_format=text returns plain text with
     * no timing information, making subtitle sync impossible.
     *
     * @param audioFilePath path to the audio file (mp3/m4a/wav)
     * @param language      BCP-47 language hint (e.g. "de", "en")
     * @return list of Cue records with accurate start/end timestamps in seconds
     */
    public List<Cue> transcribeAudio(String audioFilePath, String language) {
        String apiKey = config.groqApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AppError(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No Groq API key configured on the server. Set ECHOLINGO_GROQ_API_KEY in .env.");
        }

        File audioFile = new File(audioFilePath);
        if (!audioFile.exists()) {
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Audio file not found: " + audioFilePath);
        }

        log.info("Uploading {} ({} bytes) to Groq Whisper (lang={})…",
                audioFile.getName(), audioFile.length(), language);

        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse("audio/mpeg"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("language", language)
                // verbose_json returns segments[] with start/end timestamps — required for sync
                .addFormDataPart("response_format", "verbose_json")
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
                            "Invalid Groq API key. Check ECHOLINGO_GROQ_API_KEY in .env.");
                }
                throw new AppError(HttpStatus.BAD_GATEWAY,
                        "Groq API error " + resp.code() + ": " + responseBody);
            }
            List<Cue> cues = parseSegments(responseBody);
            log.info("Groq transcription complete: {} cues", cues.size());
            return cues;
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not reach Groq API: " + e.getMessage());
        }
    }

    /**
     * Parses the verbose_json response from Groq Whisper.
     *
     * Expected shape:
     * {
     *   "segments": [
     *     { "id": 0, "start": 0.0, "end": 4.52, "text": " Guten Morgen!" },
     *     ...
     *   ]
     * }
     *
     * Each segment already has millisecond-accurate timestamps aligned by Whisper's
     * forced-alignment model — exactly what we need for subtitle sync.
     */
    private static List<Cue> parseSegments(String json) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonElement segEl = root.get("segments");
            if (segEl == null || !segEl.isJsonArray()) {
                log.warn("Groq response had no 'segments' array — response: {}",
                        json.length() > 200 ? json.substring(0, 200) + "…" : json);
                return List.of();
            }

            JsonArray segments = segEl.getAsJsonArray();
            List<Cue> cues = new ArrayList<>(segments.size());

            for (int i = 0; i < segments.size(); i++) {
                JsonObject seg = segments.get(i).getAsJsonObject();

                double start = seg.get("start").getAsDouble();
                double end   = seg.get("end").getAsDouble();
                String text  = seg.get("text").getAsString().trim();

                // Skip empty or timing-invalid segments
                if (text.isBlank() || end <= start) continue;

                // Round to 3 decimal places (millisecond precision)
                double startR = Math.round(start * 1000.0) / 1000.0;
                double endR   = Math.round(end   * 1000.0) / 1000.0;

                cues.add(new Cue(cues.size(), startR, endR, text));
            }

            return cues;
        } catch (Exception e) {
            log.error("Failed to parse Groq verbose_json response: {}", e.getMessage());
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not parse Groq transcription response: " + e.getMessage());
        }
    }
}
