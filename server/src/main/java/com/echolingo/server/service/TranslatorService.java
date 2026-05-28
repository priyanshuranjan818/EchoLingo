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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Translates German cues to English using Google Translate's free endpoint.
 *
 * WHY we no longer use the single-request batch approach (joining cues with "|||"):
 *   Google Translate occasionally merges, drops, or corrupts the "|||" separator —
 *   especially for short phrase-level cues (≤6 words) like those produced by the
 *   Groq word-level timestamp grouper. When even one separator is lost, every English
 *   cue after that point is shifted by one index, causing the DE↔EN subtitles to
 *   show completely unrelated content (e.g. "I'm sitting in the hairdresser's chair"
 *   while the German is showing "oder Christbaumkugeln").
 *
 * NEW STRATEGY — two-tier batching:
 *   1. Combine up to CHUNK_SIZE cues into a single request using a unique Unicode
 *      sentinel "⌇" (U+2307, "WAVY LINE") that Google never produces in translations
 *      of German/English text. Split on that.
 *   2. If the split count doesn't match (Google still mangled it), fall back to
 *      translating each cue in the chunk individually.
 *
 * This keeps network requests low while guaranteeing 1-to-1 DE↔EN cue mapping.
 */
@Service
public class TranslatorService {

    private static final Logger log = LoggerFactory.getLogger(TranslatorService.class);

    /**
     * Sentinel character used to separate cues in a single Google Translate request.
     * U+2307 WAVY LINE — appears in zero Google Translate outputs for DE→EN text.
     * Using a rare Unicode character instead of "|||" prevents Google from treating
     * it as punctuation and merging/dropping it.
     */
    private static final String SEP = "\u2307";

    /** Max cues per HTTP request to Google Translate. */
    private static final int CHUNK_SIZE = 40;

    private static final String TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx&sl=%s&tl=%s&dt=t&q=%s";

    private static final Gson GSON = new Gson();
    private final OkHttpClient httpClient;

    public TranslatorService() {
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * Translates all source cues to the target language, preserving timestamps.
     * Falls back to the original text for any cue that cannot be translated.
     */
    public List<Cue> translateCues(List<Cue> sourceCues, String fromLang, String toLang) {
        if (sourceCues.isEmpty()) return List.of();

        List<Cue> result = new ArrayList<>(sourceCues.size());

        for (int i = 0; i < sourceCues.size(); i += CHUNK_SIZE) {
            int end   = Math.min(i + CHUNK_SIZE, sourceCues.size());
            List<Cue> chunk = sourceCues.subList(i, end);
            List<String> translations = translateChunk(chunk, fromLang, toLang);

            for (int j = 0; j < chunk.size(); j++) {
                Cue src = chunk.get(j);
                String text = (j < translations.size() && translations.get(j) != null
                        && !translations.get(j).isBlank())
                        ? translations.get(j)
                        : src.text();  // fallback: keep original
                result.add(new Cue(src.index(), src.start(), src.end(), text));
            }
        }

        log.info("Translation complete: {} cues ({} → {})", result.size(), fromLang, toLang);
        return result;
    }

    // ── Two-tier chunk translation ────────────────────────────────────────────

    /**
     * Tier 1: try to translate all cues in one request using the SEP sentinel.
     * If the split count is wrong (Google corrupted the sentinel), falls back to
     * Tier 2: translate each cue individually.
     */
    private List<String> translateChunk(List<Cue> chunk, String fromLang, String toLang) {
        // Build a single string: "cue1 ⌇ cue2 ⌇ cue3 ..."
        String joined = String.join(" " + SEP + " ",
                chunk.stream().map(Cue::text).toList());

        String translated = callTranslateApi(joined, fromLang, toLang);
        if (translated != null) {
            // Split on the sentinel (allow optional whitespace around it)
            String[] parts = translated.split("\\s*" + SEP + "\\s*");
            if (parts.length == chunk.size()) {
                // Perfect match — use these translations
                List<String> result = new ArrayList<>(parts.length);
                for (String p : parts) result.add(p.trim());
                return result;
            }
            log.warn("Sentinel split mismatch: expected {} parts, got {} — falling back to per-cue translation",
                    chunk.size(), parts.length);
        }

        // Tier 2 fallback: translate each cue individually (slower but always correct)
        return translateIndividually(chunk, fromLang, toLang);
    }

    /**
     * Tier 2 fallback: translate each cue with its own API call.
     * Guaranteed 1-to-1 cue mapping — no separator corruption possible.
     * Used only when Tier 1 fails.
     */
    private List<String> translateIndividually(List<Cue> chunk, String fromLang, String toLang) {
        List<String> results = new ArrayList<>(chunk.size());
        for (Cue cue : chunk) {
            String translated = callTranslateApi(cue.text(), fromLang, toLang);
            results.add(translated != null ? translated : cue.text());
        }
        return results;
    }

    // ── Google Translate API call ─────────────────────────────────────────────

    /**
     * Calls Google Translate and returns the translated string, or null on failure.
     *
     * Response structure: [[[\"translatedText\",\"originalText\",...], ...], ...]
     * We concatenate all inner translated segments to get the full result.
     */
    private String callTranslateApi(String text, String fromLang, String toLang) {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = String.format(TRANSLATE_URL, fromLang, toLang, encoded);

        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; EchoLingo/1.0)")
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            return parseTranslatedText(resp.body().string());
        } catch (IOException e) {
            log.warn("Google Translate request failed: {}", e.getMessage());
            return null;
        }
    }

    private static String parseTranslatedText(String json) {
        try {
            JsonArray outer     = GSON.fromJson(json, JsonArray.class);
            JsonArray sentences = outer.get(0).getAsJsonArray();
            StringBuilder sb    = new StringBuilder();
            for (JsonElement sent : sentences) {
                JsonArray seg = sent.getAsJsonArray();
                if (!seg.get(0).isJsonNull()) {
                    sb.append(seg.get(0).getAsString());
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
