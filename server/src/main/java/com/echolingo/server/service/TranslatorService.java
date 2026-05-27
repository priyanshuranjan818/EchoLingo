package com.echolingo.server.service;

import com.echolingo.server.model.Cue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

/**
 * Translates German cues to English using Google Translate's free endpoint.
 * Batches 80 cues per request using "|||" as a separator (same approach as
 * the original Node.js backend).
 *
 * No API key required.
 */
@Service
public class TranslatorService {

    private static final int BATCH_SIZE = 80;
    private static final String SEPARATOR = "|||";
    private static final String TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx&sl=%s&tl=%s&dt=t&q=%s";

    private static final Gson GSON = new Gson();
    private final OkHttpClient httpClient;

    public TranslatorService() {
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    /**
     * Returns a new list of cues with translated text, preserving all timing.
     * Falls back to the original text for any cue that fails to translate.
     */
    public List<Cue> translateCues(List<Cue> sourceCues, String fromLang, String toLang) {
        List<Cue> result = new ArrayList<>(sourceCues.size());
        // Process in batches
        for (int i = 0; i < sourceCues.size(); i += BATCH_SIZE) {
            List<Cue> batch = sourceCues.subList(i, Math.min(i + BATCH_SIZE, sourceCues.size()));
            List<String> translated = translateBatch(batch, fromLang, toLang);
            for (int j = 0; j < batch.size(); j++) {
                Cue src = batch.get(j);
                String text = (j < translated.size() && translated.get(j) != null)
                        ? translated.get(j) : src.text();
                result.add(new Cue(src.index(), src.start(), src.end(), text));
            }
        }
        return result;
    }

    // ---- Internals ----------------------------------------------------------

    private List<String> translateBatch(List<Cue> batch, String fromLang, String toLang) {
        String joined = String.join(SEPARATOR, batch.stream().map(Cue::text).toList());
        String encoded = URLEncoder.encode(joined, StandardCharsets.UTF_8);
        String url = String.format(TRANSLATE_URL, fromLang, toLang, encoded);

        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; EchoLingo/1.0)")
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return fallback(batch);
            String body = resp.body().string();
            return parseTranslation(body, batch.size());
        } catch (IOException e) {
            return fallback(batch);
        }
    }

    /**
     * Google Translate free endpoint returns a nested JSON array.
     * Structure: [[["translatedText","originalText",null,null,null],...],...]
     * We concatenate all translated segments to rebuild the batch.
     */
    private List<String> parseTranslation(String json, int expectedCount) {
        try {
            JsonArray outer = GSON.fromJson(json, JsonArray.class);
            JsonArray sentences = outer.get(0).getAsJsonArray();

            StringBuilder sb = new StringBuilder();
            for (JsonElement sent : sentences) {
                JsonArray seg = sent.getAsJsonArray();
                if (!seg.get(0).isJsonNull()) {
                    sb.append(seg.get(0).getAsString());
                }
            }
            // Re-split on the separator (Google usually preserves it through translation)
            String[] parts = sb.toString().split("\\|\\|\\|");
            List<String> result = new ArrayList<>(expectedCount);
            for (String p : parts) result.add(p.trim());
            // Pad if Google merged some segments
            while (result.size() < expectedCount) result.add(null);
            return result;
        } catch (Exception e) {
            return List.of(); // triggers fallback to original text
        }
    }

    private static List<String> fallback(List<Cue> batch) {
        return batch.stream().map(Cue::text).toList();
    }
}
