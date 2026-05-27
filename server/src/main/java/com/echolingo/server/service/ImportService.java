package com.echolingo.server.service;

import com.echolingo.server.exception.AppError;
import com.echolingo.server.model.Cue;
import com.echolingo.server.model.SubtitleScores;
import com.echolingo.server.model.VideoMeta;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ImportService {
    private static final Pattern YOUTUBE_ID = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})");

    private final Map<String, VideoBundle> memoryCache = new ConcurrentHashMap<>();

    public VideoMeta importVideo(String url, String sourceLang, String targetLang) {
        String videoId = extractVideoId(url);
        VideoBundle existing = memoryCache.get(videoId);
        if (existing != null) {
            return withCached(existing.meta(), true);
        }

        List<Cue> source = List.of(
                new Cue(0, 0.0, 2.8, "Willkommen bei EchoLingo."),
                new Cue(1, 3.0, 6.2, "Ziehe die Untertitel nach oben oder unten."),
                new Cue(2, 6.4, 9.5, "Bald kommen echte YouTube-Untertitel dazu.")
        );
        List<Cue> translation = List.of(
                new Cue(0, 0.0, 2.8, "Welcome to EchoLingo."),
                new Cue(1, 3.0, 6.2, "Drag subtitles up or down."),
                new Cue(2, 6.4, 9.5, "Real YouTube subtitles are coming next.")
        );

        VideoMeta meta = new VideoMeta(
                videoId,
                "EchoLingo import scaffold",
                10,
                "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg",
                true,
                true,
                sourceLang == null || sourceLang.isBlank() ? "de" : sourceLang,
                targetLang == null || targetLang.isBlank() ? "en" : targetLang,
                new SubtitleScores(100, 90, 90, 93),
                false,
                true
        );

        memoryCache.put(videoId, new VideoBundle(meta, source, translation));
        return meta;
    }

    public VideoMeta getMeta(String videoId) {
        VideoBundle bundle = getBundle(videoId);
        return withCached(bundle.meta(), true);
    }

    public List<Cue> getSubtitles(String videoId, String lang) {
        VideoBundle bundle = getBundle(videoId);
        if ("en".equalsIgnoreCase(lang)) {
            return bundle.translation();
        }
        return bundle.source();
    }

    private VideoBundle getBundle(String videoId) {
        VideoBundle bundle = memoryCache.get(videoId);
        if (bundle == null) {
            throw new AppError(HttpStatus.NOT_FOUND, "Video has not been imported yet.");
        }
        return bundle;
    }

    private static VideoMeta withCached(VideoMeta meta, boolean cached) {
        return new VideoMeta(
                meta.videoId(),
                meta.title(),
                meta.duration(),
                meta.thumbnailUrl(),
                meta.hasDe(),
                meta.hasEn(),
                meta.deSource(),
                meta.enSource(),
                meta.scores(),
                cached,
                meta.ready()
        );
    }

    private static String extractVideoId(String url) {
        Matcher matcher = YOUTUBE_ID.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (url.matches("[A-Za-z0-9_-]{11}")) {
            return url;
        }
        throw new AppError(HttpStatus.BAD_REQUEST, "Enter a valid YouTube URL or video ID.");
    }

    private record VideoBundle(VideoMeta meta, List<Cue> source, List<Cue> translation) {
    }
}
