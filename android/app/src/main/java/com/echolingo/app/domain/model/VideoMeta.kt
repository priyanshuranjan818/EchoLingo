package com.echolingo.app.domain.model

data class VideoMeta(
    val videoId: String,
    val title: String,
    val duration: Int,
    val thumbnailUrl: String,
    val hasDe: Boolean,
    val hasEn: Boolean,
    val scores: SubtitleScores,
)
