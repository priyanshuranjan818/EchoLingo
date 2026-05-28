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

    /**
     * Maximum words per subtitle cue when using word-level timestamps.
     * At 6 words the subtitle stays ~1-2 seconds long, matching the speech rhythm
     * and preventing future words from being shown before they are spoken.
     */
    private static final int MAX_WORDS_PER_CUE = 6;

    private final OkHttpClient httpClient;
    private final AppConfig config;

    public GroqService(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(java.time.Duration.ofSeconds(120))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    /**
     * Transcribes audio using Groq Whisper with word-level timestamps.
     *
     * Why word-level timestamps fix the sync problem:
     *   - Default (segment-level): one cue per sentence, e.g. 8 seconds of speech in
     *     one subtitle block. The viewer sees "sprichst? Das sind ja beides deine
     *     Muttersprachen." before the speaker has said those words. Looks "ahead".
     *   - Word-level: we group into ~6-word phrases each timed to when those exact
     *     words are spoken. The subtitle now precisely tracks the voice.
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

        log.info("Uploading {} ({} bytes) to Groq Whisper (lang={}, word-level timestamps)…",
                audioFile.getName(), audioFile.length(), language);

        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse("audio/mpeg"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "verbose_json")
                // Request both granularities — word gives us per-word timing,
                // segment is kept as fallback in case words[] is absent.
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("timestamp_granularities[]", "segment")
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
            List<Cue> cues = parseResponse(responseBody);
            log.info("Groq transcription complete: {} cues", cues.size());
            return cues;
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not reach Groq API: " + e.getMessage());
        }
    }

    // ─── Response parsing ────────────────────────────────────────────────────

    private static List<Cue> parseResponse(String json) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);

            // Prefer word-level timestamps (best quality sync)
            JsonElement wordsEl = root.get("words");
            if (wordsEl != null && wordsEl.isJsonArray()
                    && wordsEl.getAsJsonArray().size() > 0) {
                log.info("Word-level timestamps available — building phrase cues");
                return buildCuesFromWords(wordsEl.getAsJsonArray());
            }

            // Fallback: segment-level (split at punctuation for shorter cues)
            log.warn("No word timestamps in Groq response — falling back to segments");
            JsonElement segEl = root.get("segments");
            if (segEl != null && segEl.isJsonArray()) {
                return buildCuesFromSegments(segEl.getAsJsonArray());
            }

            log.warn("Groq response contained neither 'words' nor 'segments'");
            return List.of();

        } catch (Exception e) {
            log.error("Failed to parse Groq response: {}", e.getMessage());
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not parse Groq transcription response: " + e.getMessage());
        }
    }

    // ─── Word-level grouping ─────────────────────────────────────────────────

    /**
     * Groups Whisper's individual words into short phrase-level cues.
     *
     * Split triggers (first match wins per word):
     *   1. Word ends with sentence punctuation (.  ?  !  。) → flush immediately
     *   2. MAX_WORDS_PER_CUE (6) words accumulated          → flush
     *   3. Word ends with clause punctuation (, ;) AND       → flush
     *      at least 3 words have accumulated
     *
     * Each cue gets: start = first word's start, end = last word's end.
     * This means the subtitle appears at the exact moment the first word is
     * spoken and disappears when the last word ends — no preview of future speech.
     *
     * Example (German interview):
     *   words: [Also(3.1), fühlst(3.3), du(3.5), dich(3.6), zum(3.8), Beispiel(4.0),
     *           anders,(4.2), wenn(4.5), du(4.6), Deutsch(4.8), oder(5.0),
     *           Türkisch(5.2), sprichst?(5.5)]
     *
     *   cues:
     *     [0] "Also fühlst du dich zum Beispiel"  3.1→4.0
     *     [1] "anders, wenn du"                   4.2→4.6   ← comma split at 3 words
     *     [2] "Deutsch oder Türkisch sprichst?"   4.8→5.5
     */
    private static List<Cue> buildCuesFromWords(JsonArray words) {
        // Temporary storage for a phrase group
        List<String> groupWords  = new ArrayList<>();
        List<Double> groupStarts = new ArrayList<>();
        List<Double> groupEnds   = new ArrayList<>();

        List<Cue> cues = new ArrayList<>();

        for (int i = 0; i < words.size(); i++) {
            JsonObject w = words.get(i).getAsJsonObject();
            String word  = w.get("word").getAsString().trim();
            double start = w.get("start").getAsDouble();
            double end   = w.get("end").getAsDouble();

            if (word.isBlank()) continue;

            groupWords.add(word);
            groupStarts.add(start);
            groupEnds.add(end);

            boolean sentenceEnd = word.endsWith(".")
                    || word.endsWith("?")
                    || word.endsWith("!")
                    || word.endsWith("。");
            boolean maxReached  = groupWords.size() >= MAX_WORDS_PER_CUE;
            boolean clauseBreak = groupWords.size() >= 3
                    && (word.endsWith(",") || word.endsWith(";"));

            if (sentenceEnd || maxReached || clauseBreak) {
                emitCue(groupWords, groupStarts, groupEnds, cues);
                groupWords.clear();
                groupStarts.clear();
                groupEnds.clear();
            }
        }

        // Flush any remaining words
        emitCue(groupWords, groupStarts, groupEnds, cues);
        return cues;
    }

    private static void emitCue(
            List<String> words,
            List<Double> starts,
            List<Double> ends,
            List<Cue> out) {
        if (words.isEmpty()) return;
        String text  = String.join(" ", words).trim();
        double start = starts.get(0);
        double end   = ends.get(ends.size() - 1);
        // Safety: ensure end > start (Whisper occasionally produces equal timestamps)
        if (end <= start) end = start + 1.0;
        out.add(new Cue(
                out.size(),
                Math.round(start * 1000.0) / 1000.0,
                Math.round(end   * 1000.0) / 1000.0,
                text));
    }

    // ─── Segment-level fallback ───────────────────────────────────────────────

    /**
     * Fallback when word timestamps are unavailable.
     * Splits each Whisper segment at sentence boundaries (.?!) and distributes
     * the time window proportionally by character count.
     *
     * This is an approximation — better than one giant block but not as accurate
     * as word-level timestamps.
     */
    private static List<Cue> buildCuesFromSegments(JsonArray segments) {
        List<Cue> cues = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            JsonObject seg  = segments.get(i).getAsJsonObject();
            double segStart = seg.get("start").getAsDouble();
            double segEnd   = seg.get("end").getAsDouble();
            String text     = seg.get("text").getAsString().trim();
            if (text.isBlank() || segEnd <= segStart) continue;

            cues.addAll(splitSegmentAtPunctuation(text, segStart, segEnd, cues.size()));
        }
        return cues;
    }

    /**
     * Splits segment text at sentence endings, distributing time proportionally.
     *
     * e.g. "Hello world. How are you?" [10.0 → 14.0]:
     *   "Hello world."  → 10.0 → 12.0  (12 of 24 chars = 50% of 4s)
     *   "How are you?"  → 12.0 → 14.0  (12 of 24 chars = 50% of 4s)
     */
    private static List<Cue> splitSegmentAtPunctuation(
            String text, double segStart, double segEnd, int idxOffset) {

        String[] parts = text.split("(?<=[.?!])\\s+");
        if (parts.length <= 1) {
            return List.of(new Cue(idxOffset,
                    Math.round(segStart * 1000.0) / 1000.0,
                    Math.round(segEnd   * 1000.0) / 1000.0,
                    text));
        }

        int totalChars   = text.length();
        double duration  = segEnd - segStart;
        List<Cue> result = new ArrayList<>(parts.length);
        double cursor    = segStart;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isBlank()) continue;
            double fraction = (double) part.length() / totalChars;
            double partEnd  = (i == parts.length - 1)
                    ? segEnd : cursor + duration * fraction;
            result.add(new Cue(
                    idxOffset + result.size(),
                    Math.round(cursor  * 1000.0) / 1000.0,
                    Math.round(partEnd * 1000.0) / 1000.0,
                    part));
            cursor = partEnd;
        }
        return result;
    }
}
