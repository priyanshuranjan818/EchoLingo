package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.exception.AppError;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CaptionsService {

    private static final Pattern PLAYER_RESPONSE = Pattern.compile(
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})(?=;\\s*(?:var|const|let|</script))",
            Pattern.DOTALL
    );

    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;

    public CaptionsService(AppConfig config) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().followRedirects(true);

        String proxyList = config.ytdlpProxyList();
        if (proxyList != null && !proxyList.isBlank()) {
            String[] proxies = proxyList.split(",");
            String proxyUrl = proxies[0].trim(); // use first proxy
            // Expected format: http://user:pass@host:port
            try {
                java.net.URI uri = new java.net.URI(proxyUrl);
                String host = uri.getHost();
                int port = uri.getPort();
                InetSocketAddress proxyAddr = new InetSocketAddress(host, port);
                builder.proxy(new Proxy(Proxy.Type.HTTP, proxyAddr));
                String userInfo = uri.getUserInfo();
                if (userInfo != null) {
                    String[] parts = userInfo.split(":", 2);
                    String user = parts[0];
                    String pass = parts.length > 1 ? parts[1] : "";
                    builder.proxyAuthenticator((route, response) ->
                        response.request().newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(user, pass))
                            .build()
                    );
                }
            } catch (Exception ignored) {}
        }

        this.httpClient = builder.build();
    }

    public record PageData(
            String title,
            int durationSec,
            String thumbnailUrl,
            Map<String, String> captionTrackUrls
    ) {}

    public PageData fetchPageData(String videoId) {
        String html = fetchHtml(videoId);
        return parsePageData(videoId, html);
    }

    public String fetchVtt(String vttUrl) {
        Request req = new Request.Builder()
                .url(vttUrl + "&fmt=vtt")
                .header("User-Agent", "Mozilla/5.0 (compatible; EchoLingo/1.0)")
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return "";
            return resp.body().string();
        } catch (IOException e) {
            return "";
        }
    }

    private PageData parsePageData(String videoId, String html) {
        Matcher m = PLAYER_RESPONSE.matcher(html);
        if (!m.find()) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not parse YouTube page for video: " + videoId);
        }

        JsonObject root = null;
        String candidate = m.group(1);
        for (int trim = 0; trim <= 200; trim++) {
            try {
                root = GSON.fromJson(candidate.substring(0, candidate.length() - trim), JsonObject.class);
                break;
            } catch (Exception ignored) {}
        }
        if (root == null) {
            throw new AppError(HttpStatus.BAD_GATEWAY, "Failed to parse ytInitialPlayerResponse for " + videoId);
        }

        String title = videoId;
        try {
            title = root.getAsJsonObject("videoDetails").get("title").getAsString();
        } catch (Exception ignored) {}

        int duration = 0;
        try {
            duration = Integer.parseInt(
                    root.getAsJsonObject("videoDetails").get("lengthSeconds").getAsString());
        } catch (Exception ignored) {}

        String thumb = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";

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
                tracks.putIfAbsent(lang, baseUrl);
                if (lang.contains("-")) {
                    String baseLang = lang.split("-")[0];
                    tracks.putIfAbsent(baseLang, baseUrl);
                }
            }
        } catch (Exception ignored) {}

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
