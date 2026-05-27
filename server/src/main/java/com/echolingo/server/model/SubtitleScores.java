package com.echolingo.server.model;

public record SubtitleScores(
        int syncScore,
        int qualityScore,
        int translationScore,
        int overallScore
) {
}
