package com.echolingo.server.service;

import com.echolingo.server.model.Cue;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Pure scoring logic — no network, no I/O.
 * All scores are 0-100.
 */
@Service
public class ScoringEngine {

    public record Scores(int syncScore, int qualityScore, int translationScore, int overallScore) {}

    /**
     * @param sourceCues  German cues
     * @param transCues   English cues
     * @param durationSec Video duration in seconds
     * @param enSource    How English was obtained: "youtube_captions_en" | "google_translate"
     */
    public Scores computeAllScores(List<Cue> sourceCues, List<Cue> transCues,
                                   int durationSec, String enSource) {
        int sync    = computeSyncScore(sourceCues);
        int quality = computeQualityScore(sourceCues, durationSec);
        int trans   = computeTranslationScore(enSource, transCues);
        int overall = (int) Math.round(sync * 0.35 + quality * 0.35 + trans * 0.30);
        return new Scores(sync, quality, trans, overall);
    }

    // --- Sync score -------------------------------------------------------

    private static int computeSyncScore(List<Cue> cues) {
        if (cues.isEmpty()) return 0;
        int penalty = 0;
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            // Negative duration
            if (c.end() <= c.start()) penalty += 3;
            // Gap / overlap with next cue (allow up to 0.5 s gap, penalise overlap)
            if (i + 1 < cues.size()) {
                double gap = cues.get(i + 1).start() - c.end();
                if (gap < -0.05) penalty += 2; // overlap
            }
        }
        return Math.max(0, 100 - penalty);
    }

    // --- Quality score ----------------------------------------------------

    private static int computeQualityScore(List<Cue> cues, int durationSec) {
        if (cues.isEmpty()) return 0;

        // Coverage: how much of the video is covered by cues
        double totalCovered = cues.stream().mapToDouble(c -> c.end() - c.start()).sum();
        double coverage = durationSec > 0 ? Math.min(1.0, totalCovered / durationSec) : 0;

        // Average word count per cue (ideal 3-12 words)
        double avgWords = cues.stream()
                .mapToInt(c -> c.text().trim().split("\\s+").length)
                .average().orElse(0);
        double wordScore = avgWords >= 3 && avgWords <= 15 ? 1.0
                : avgWords < 3 ? avgWords / 3.0
                : Math.max(0, 1.0 - (avgWords - 15) / 20.0);

        // Garbled character ratio (question marks, unusual Unicode blocks)
        long garbled = cues.stream()
                .flatMapToInt(c -> c.text().chars())
                .filter(ch -> ch == '?' || ch > 0x2E7F)
                .count();
        long totalChars = cues.stream().mapToLong(c -> c.text().length()).sum();
        double cleanRatio = totalChars > 0 ? 1.0 - Math.min(1.0, (double) garbled / totalChars) : 0;

        return (int) Math.round((coverage * 0.4 + wordScore * 0.3 + cleanRatio * 0.3) * 100);
    }

    // --- Translation score -----------------------------------------------

    private static int computeTranslationScore(String enSource, List<Cue> transCues) {
        if (transCues.isEmpty()) return 0;
        return switch (enSource == null ? "" : enSource) {
            case "youtube_captions_en" -> 95;
            case "google_translate"    -> 83;
            default                    -> 75;
        };
    }
}
