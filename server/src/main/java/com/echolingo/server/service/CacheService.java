package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.model.Cue;
import com.echolingo.server.model.SubtitleScores;
import com.echolingo.server.model.VideoMeta;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Persists subtitle data to disk under {cacheDir}/{videoId}/.
 *
 * Cache version: 5. Any cached files with a different version are ignored and
 * re-processed.
 */
@Service
public class CacheService {

    private static final int CACHE_VERSION = 5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CUE_LIST_TYPE = new TypeToken<List<Cue>>() {}.getType();

    private final Path cacheRoot;

    public CacheService(AppConfig config) {
        this.cacheRoot = Path.of(config.cacheDir());
    }

    // ---- Read ---------------------------------------------------------------

    public Optional<CachedBundle> read(String videoId) {
        Path dir = dir(videoId);
        Path metaPath = dir.resolve("meta.json");
        if (!Files.exists(metaPath)) return Optional.empty();

        try {
            String metaJson = Files.readString(metaPath, StandardCharsets.UTF_8);
            CachedMeta cached = GSON.fromJson(metaJson, CachedMeta.class);
            if (cached == null || cached.v() != CACHE_VERSION) return Optional.empty();

            List<Cue> source = readCues(dir.resolve("de.json"));
            List<Cue> trans  = readCues(dir.resolve("en.json"));
            if (source == null || trans == null) return Optional.empty();

            VideoMeta meta = new VideoMeta(
                    cached.videoId(), cached.title(), cached.duration(), cached.thumbnailUrl(),
                    !source.isEmpty(), !trans.isEmpty(),
                    cached.deSource(), cached.enSource(),
                    new SubtitleScores(cached.syncScore(), cached.qualityScore(),
                            cached.translationScore(), cached.overallScore()),
                    true, true
            );
            return Optional.of(new CachedBundle(meta, source, trans));
        } catch (Exception e) {
            return Optional.empty(); // corrupt cache → re-process
        }
    }

    // ---- Write --------------------------------------------------------------

    public void write(String videoId, VideoMeta meta, List<Cue> source, List<Cue> trans) {
        Path dir = dir(videoId);
        try {
            Files.createDirectories(dir);

            CachedMeta cached = new CachedMeta(
                    CACHE_VERSION,
                    meta.videoId(), meta.title(), meta.duration(), meta.thumbnailUrl(),
                    meta.deSource(), meta.enSource(),
                    meta.scores().syncScore(), meta.scores().qualityScore(),
                    meta.scores().translationScore(), meta.scores().overallScore()
            );
            Files.writeString(dir.resolve("meta.json"), GSON.toJson(cached), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("de.json"),   GSON.toJson(source), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("en.json"),   GSON.toJson(trans),  StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Cache write failure is non-fatal — log and continue
            System.err.println("[CacheService] Failed to write cache for " + videoId + ": " + e.getMessage());
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private Path dir(String videoId) {
        return cacheRoot.resolve(videoId);
    }

    private static List<Cue> readCues(Path path) {
        try {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), CUE_LIST_TYPE);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Inner types --------------------------------------------------------

    public record CachedBundle(VideoMeta meta, List<Cue> source, List<Cue> trans) {}

    private record CachedMeta(
            int v,
            String videoId, String title, int duration, String thumbnailUrl,
            String deSource, String enSource,
            int syncScore, int qualityScore, int translationScore, int overallScore
    ) {}
}
