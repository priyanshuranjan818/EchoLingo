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

/**
 * Orchestrates the full subtitle import pipeline (de → en, hardcoded).
 *
 * Pipeline:
 *  1. Memory cache (warm, per JVM run)
 *  2. File cache  (persistent, version 4)
 *  3. YouTube page scrape for caption track URLs
 *  4. Source cues: YouTube VTT → yt-dlp fallback (Groq Whisper as last resort)
 *  5. Translation cues: YouTube EN VTT → Google Translate fallback
 *  6. Score → persist to file cache
 */
@Service
public class ImportService {

    private static final Pattern YOUTUBE_ID =
            Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})");

    private static final String SOURCE_LANG = "de";
    private static final String TARGET_LANG = "en";

    // Warm in-memory cache — avoids hitting disk on repeated calls within same run
    private final Map<String, VideoBundle> memoryCache = new ConcurrentHashMap<>();

    private final CacheService      cacheService;
    private final CaptionsService   captionsService;
    private final VttParser         vttParser;
    private final YtdlpService      ytdlpService;
    private final TranslatorService translatorService;
    private final ScoringEngine     scoringEngine;

    public ImportService(CacheService cacheService,
                         CaptionsService captionsService,
                         VttParser vttParser,
                         YtdlpService ytdlpService,
                         TranslatorService translatorService,
                         ScoringEngine scoringEngine) {
        this.cacheService      = cacheService;
        this.captionsService   = captionsService;
        this.vttParser         = vttParser;
        this.ytdlpService      = ytdlpService;
        this.translatorService = translatorService;
        this.scoringEngine     = scoringEngine;
    }

    // ---- Public API ---------------------------------------------------------

    public VideoMeta importVideo(String url, String ignoredSourceLang, String ignoredTargetLang) {
        String videoId = extractVideoId(url);

        // 1. Warm memory cache
        VideoBundle warm = memoryCache.get(videoId);
        if (warm != null) return withCached(warm.meta(), true);

        // 2. Persistent file cache
        var diskCached = cacheService.read(videoId);
        if (diskCached.isPresent()) {
            var b = diskCached.get();
            memoryCache.put(videoId, new VideoBundle(b.meta(), b.source(), b.trans()));
            return withCached(b.meta(), true);
        }

        // 3. Fetch YouTube page metadata + caption track URLs
        CaptionsService.PageData page = captionsService.fetchPageData(videoId);

        // 4. Get German (source) cues
        String deSource;
        List<Cue> sourceCues;

        String deVttUrl = page.captionTrackUrls().get(SOURCE_LANG);
        if (deVttUrl != null && !deVttUrl.isBlank()) {
            String vtt = captionsService.fetchVtt(deVttUrl);
            sourceCues = vttParser.parse(vtt);
            deSource = "youtube_captions";
        } else {
            // Fallback: yt-dlp subtitle download
            sourceCues = ytdlpService.fetchSubtitles(videoId, SOURCE_LANG, vttParser);
            deSource = "ytdlp";
        }

        if (sourceCues.isEmpty()) {
            throw new AppError(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No German subtitles found for this video. "
                    + "Try a video that has German auto-captions.");
        }

        // 5. Get English (translation) cues
        String enSource;
        List<Cue> transCues;

        String enVttUrl = page.captionTrackUrls().get(TARGET_LANG);
        if (enVttUrl != null && !enVttUrl.isBlank()) {
            String vtt = captionsService.fetchVtt(enVttUrl);
            transCues = vttParser.parse(vtt);
            enSource = "youtube_captions_en";
        } else {
            transCues = translatorService.translateCues(sourceCues, SOURCE_LANG, TARGET_LANG);
            enSource = "google_translate";
        }

        // 6. Score
        ScoringEngine.Scores scores = scoringEngine.computeAllScores(
                sourceCues, transCues, page.durationSec(), enSource);

        VideoMeta meta = new VideoMeta(
                videoId,
                page.title(),
                page.durationSec(),
                page.thumbnailUrl(),
                !sourceCues.isEmpty(),
                !transCues.isEmpty(),
                deSource,
                enSource,
                new SubtitleScores(scores.syncScore(), scores.qualityScore(),
                        scores.translationScore(), scores.overallScore()),
                false,
                true
        );

        // 7. Persist to file cache + warm memory cache
        cacheService.write(videoId, meta, sourceCues, transCues);
        memoryCache.put(videoId, new VideoBundle(meta, sourceCues, transCues));

        return meta;
    }

    public VideoMeta getMeta(String videoId) {
        return withCached(getBundle(videoId).meta(), true);
    }

    public List<Cue> getSubtitles(String videoId, String lang) {
        VideoBundle bundle = getBundle(videoId);
        return TARGET_LANG.equalsIgnoreCase(lang) ? bundle.translation() : bundle.source();
    }

    // ---- Helpers ------------------------------------------------------------

    private VideoBundle getBundle(String videoId) {
        // Check memory first, then disk
        VideoBundle b = memoryCache.get(videoId);
        if (b != null) return b;

        var disk = cacheService.read(videoId);
        if (disk.isPresent()) {
            var cached = disk.get();
            VideoBundle loaded = new VideoBundle(cached.meta(), cached.source(), cached.trans());
            memoryCache.put(videoId, loaded);
            return loaded;
        }

        throw new AppError(HttpStatus.NOT_FOUND,
                "Video '" + videoId + "' has not been imported yet. Call POST /api/import first.");
    }

    private static VideoMeta withCached(VideoMeta meta, boolean cached) {
        return new VideoMeta(
                meta.videoId(), meta.title(), meta.duration(), meta.thumbnailUrl(),
                meta.hasDe(), meta.hasEn(), meta.deSource(), meta.enSource(),
                meta.scores(), cached, meta.ready());
    }

    private static String extractVideoId(String url) {
        if (url == null || url.isBlank()) {
            throw new AppError(HttpStatus.BAD_REQUEST, "URL must not be blank.");
        }
        Matcher m = YOUTUBE_ID.matcher(url);
        if (m.find()) return m.group(1);
        if (url.matches("[A-Za-z0-9_-]{11}")) return url;
        throw new AppError(HttpStatus.BAD_REQUEST, "Enter a valid YouTube URL or 11-character video ID.");
    }

    private record VideoBundle(VideoMeta meta, List<Cue> source, List<Cue> translation) {}
}

