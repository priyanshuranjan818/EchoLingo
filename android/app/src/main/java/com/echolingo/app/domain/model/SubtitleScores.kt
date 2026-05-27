package com.echolingo.app.domain.model

data class SubtitleScores(
    val syncScore: Int,
    val qualityScore: Int,
    val translationScore: Int,
    val overallScore: Int,
)
