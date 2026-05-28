package com.echolingo.server.service;

import com.echolingo.server.exception.AppError;
import com.echolingo.server.model.Cue;
import com.echolingo.server.model.SubtitleScores;
import com.echolingo.server.model.VideoMeta;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private static final Pattern YOUTUBE_ID =
            Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})");

    private static final String SOURCE_LANG = "de";
    private static final String TARGET_LANG = "en";

    private final Map<String, VideoBundle> memoryCache = new ConcurrentHashMap<>();

    private final CacheService      cacheService;
    private final CaptionsService   captionsService;
    private final VttParser         vttParser;
    private final YtdlpService      ytdlpService;
    private final TranslatorService translatorService;
    private final ScoringEngine     scoringEngine;
    private final GroqService       groqService;

    public ImportService(CacheService cacheService,
                         CaptionsService captionsService,
                         VttParser vttParser,
                         YtdlpService ytdlpService,
                         TranslatorService translatorService,
                         ScoringEngine scoringEngine,
                         GroqService groqService) {
        this.cacheService      = cacheService;
        this.captionsService   = captionsService;
        this.vttParser         = vttParser;
        this.ytdlpService      = ytdlpService;
        this.translatorService = translatorService;
        this.scoringEngine     = scoringEngine;
        this.groqService       = groqService;
    }

    public VideoMeta importVideo(String url, String ignoredSourceLang, String ignoredTargetLang) {
        return importVideoWithKey(url, null);
    }

    public VideoMeta importVideoWithKey(String url, String groqKeyOverride) {
        String videoId = extractVideoId(url);
        log.info("importVideo: videoId={}", videoId);

        VideoBundle warm = memoryCache.get(videoId);
        if (warm != null) return withCached(warm.meta(), true);

        var diskCached = cacheService.read(videoId);
        if (diskCached.isPresent()) {
            var b = diskCached.get();
            memoryCache.put(videoId, new VideoBundle(b.meta(), b.source(), b.trans()));
            return withCached(b.meta(), true);
        }

        // 1. Try YouTube page scrape for caption URLs
        CaptionsService.PageData page = captionsService.fetchPageData(videoId);
        log.info("captionTracks found: {}", page.captionTrackUrls().keySet());

        String deSource;
        List<Cue> sourceCues;

        String deVttUrl = page.captionTrackUrls().get(SOURCE_LANG);

        if (deVttUrl != null && !deVttUrl.isBlank()) {
            // 2a. YouTube captions available
            log.info("Using YouTube captions for DE");
            String vtt = captionsService.fetchVtt(deVttUrl);
            sourceCues = vttParser.parse(vtt);
            deSource = "youtube_captions";
        } else {
            // 2b. Try yt-dlp subtitle download
            log.info("No YouTube captions, trying yt-dlp subtitle download...");
            sourceCues = ytdlpService.fetchSubtitles(videoId, SOURCE_LANG, vttParser);
            deSource = "ytdlp";

            if (sourceCues.isEmpty()) {
                // 2c. Last resort: download audio + Groq Whisper
                log.info("yt-dlp subtitles empty, falling back to Groq Whisper transcription...");
                Path audioPath = null;
                try {
                    audioPath = ytdlpService.downloadAudio(videoId);
                    log.info("Audio downloaded to: {}", audioPath);
                    String transcript = groqService.transcribeAudio(
                            audioPath.toString(), SOURCE_LANG, groqKeyOverride);
                    log.info("Whisper transcript length: {} chars", transcript.length());
                    sourceCues = transcriptToCues(transcript);
                    deSource = "groq_whisper";
                } catch (Exception e) {
                    log.error("Groq Whisper fallback failed: {}", e.getMessage());
                    throw new AppError(HttpStatus.UNPROCESSABLE_ENTITY,
                            "No German subtitles found for this video. "
                            + "Try a video that has German auto-captions.");
                } finally {
                    if (audioPath != null) {
                        try {
                            java.nio.file.Files.deleteIfExists(audioPath);
                            java.nio.file.Files.deleteIfExists(audioPath.getParent());
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (sourceCues.isEmpty()) {
            throw new AppError(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No German subtitles found for this video. "
                    + "Try a video that has German auto-captions.");
        }

        log.info("Got {} DE cues via {}", sourceCues.size(), deSource);

        String enSource;
        List<Cue> transCues;

        String enVttUrl = page.captionTrackUrls().get(TARGET_LANG);
        if (enVttUrl != null && !enVttUrl.isBlank()) {
            transCues = vttParser.parse(captionsService.fetchVtt(enVttUrl));
            enSource = "youtube_captions_en";
        } else {
            transCues = translatorService.translateCues(sourceCues, SOURCE_LANG, TARGET_LANG);
            enSource = "google_translate";
        }

        ScoringEngine.Scores scores = scoringEngine.computeAllScores(
                sourceCues, transCues, page.durationSec(), enSource);

        VideoMeta meta = new VideoMeta(
                videoId, page.title(), page.durationSec(), page.thumbnailUrl(),
                !sourceCues.isEmpty(), !transCues.isEmpty(), deSource, enSource,
                new SubtitleScores(scores.syncScore(), scores.qualityScore(),
                        scores.translationScore(), scores.overallScore()),
                false, true
        );

        cacheService.write(videoId, meta, sourceCues, transCues);
        memoryCache.put(videoId, new VideoBundle(meta, sourceCues, transCues));
        log.info("importVideo complete: videoId={}", videoId);
        return meta;
    }

    public VideoMeta getMeta(String videoId) {
        return withCached(getBundle(videoId).meta(), true);
    }

    public List<Cue> getSubtitles(String videoId, String lang) {
        VideoBundle bundle = getBundle(videoId);
        return TARGET_LANG.equalsIgnoreCase(lang) ? bundle.translation() : bundle.source();
    }

    // Convert plain Whisper transcript text into timed cues (~7 words each, 3s apart)
    private static List<Cue> transcriptToCues(String transcript) {
        String[] words = transcript.trim().split("\\s+");
        List<Cue> cues = new java.util.ArrayList<>();
        int wordsPerCue = 7;
        double secPerCue = 3.0;
        for (int i = 0; i < words.length; i += wordsPerCue) {
            int end = Math.min(i + wordsPerCue, words.length);
            String text = String.join(" ", java.util.Arrays.copyOfRange(words, i, end));
            long startMs = (long) (i / wordsPerCue * secPerCue * 1000);
            long endMs   = startMs + (long) (secPerCue * 1000);
            cues.add(new Cue(i / wordsPerCue, (double) startMs / 1000.0, (double) endMs / 1000.0, text));
        }
        return cues;
    }

    private VideoBundle getBundle(String videoId) {
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
        throw new AppError(HttpStatus.BAD_REQUEST,
                "Enter a valid YouTube URL or 11-character video ID.");
    }

    private record VideoBundle(VideoMeta meta, List<Cue> source, List<Cue> translation) {}
}
