package com.echolingo.server.service;

import com.echolingo.server.exception.AppError;
import com.echolingo.server.model.Cue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Scrapes the YouTube watch page to extract:
 *  - Caption track URLs (VTT) for 'de' and 'en'
 *  - Video title and duration
 *
 * Also fetches and returns the raw VTT text from a given track URL.
 */
@Service
public class CaptionsService {

    // Extracts ytInitialPlayerResponse JSON from the page script tag
    private static final Pattern PLAYER_RESPONSE = Pattern.compile(
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})(?=;\\s*(?:var|const|let|</script))",
            Pattern.DOTALL
    );

    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;

    public CaptionsService() {
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    // ---- Public API ---------------------------------------------------------

    public record PageData(
            String title,
            int durationSec,
            String thumbnailUrl,
            Map<String, String> captionTrackUrls  // lang code → VTT URL (may be empty)
    ) {}

    public PageData fetchPageData(String videoId) {
        String html = fetchHtml(videoId);
        return parsePageData(videoId, html);
    }

    public String fetchVtt(String vttUrl) {
        Request req = new Request.Builder()
                .url(vttUrl + "&fmt=vtt") // ask for VTT format explicitly
                .header("User-Agent", "Mozilla/5.0 (compatible; EchoLingo/1.0)")
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return "";
            return resp.body().string();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- Parsing ------------------------------------------------------------

    private PageData parsePageData(String videoId, String html) {
        Matcher m = PLAYER_RESPONSE.matcher(html);
        if (!m.find()) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not parse YouTube page for video: " + videoId);
        }

        // The regex might grab slightly more than needed; parse greedily
        JsonObject root = null;
        String candidate = m.group(1);
        // Try successively shorter substrings if Gson chokes (YouTube embeds extra JS after)
        for (int trim = 0; trim <= 200; trim++) {
            try {
                root = GSON.fromJson(candidate.substring(0, candidate.length() - trim), JsonObject.class);
                break;
            } catch (Exception ignored) { /* trim more */ }
        }
        if (root == null) {
            throw new AppError(HttpStatus.BAD_GATEWAY, "Failed to parse ytInitialPlayerResponse for " + videoId);
        }

        // Title
        String title = videoId;
        try {
            title = root.getAsJsonObject("videoDetails").get("title").getAsString();
        } catch (Exception ignored) {}

        // Duration
        int duration = 0;
        try {
            duration = Integer.parseInt(
                    root.getAsJsonObject("videoDetails").get("lengthSeconds").getAsString());
        } catch (Exception ignored) {}

        // Thumbnail
        String thumb = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";

        // Caption tracks
        Map<String, String> tracks = new LinkedHashMap<>();
        try {
            JsonArray trackList = root
                    .getAsJsonObject("captions")
                    .getAsJsonObject("playerCaptionsTracklistRenderer")
                    .getAsJsonArray("captionTracks");

            for (JsonElement el : trackList) {
                JsonObject track = el.getAsJsonObject();
                String lang = track.get("languageCode").getAsString();
                String baseUrl = track.get("baseUrl").getAsString();
                // Keep the first track per language (YouTube may list multiple)
                tracks.putIfAbsent(lang, baseUrl);
            }
        } catch (Exception ignored) {} // video has no captions at all

        return new PageData(title, duration, thumb, tracks);
    }

    private String fetchHtml(String videoId) {
        Request req = new Request.Builder()
                .url(WATCH_URL + videoId)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Safari/537.36")
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new AppError(HttpStatus.BAD_GATEWAY,
                        "YouTube returned HTTP " + resp.code() + " for video " + videoId);
            }
            return resp.body().string();
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not reach YouTube: " + e.getMessage());
        }
    }
}
