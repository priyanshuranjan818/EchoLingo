package com.echolingo.server.model;

public record VideoMeta(
        String videoId,
        String title,
        int duration,
        String thumbnailUrl,
        boolean hasDe,
        boolean hasEn,
        String deSource,
        String enSource,
        SubtitleScores scores,
        boolean cached,
        boolean ready
) {
}
