package com.echolingo.app.data.api

import com.echolingo.app.domain.model.Cue
import com.echolingo.app.domain.model.SubtitleScores
import com.echolingo.app.domain.model.VideoMeta

fun CueDto.toDomain(): Cue =
    Cue(
        index = index,
        startMs = (start * 1000).toLong(),
        endMs = (end * 1000).toLong(),
        text = text,
    )

fun SubtitleScoresDto.toDomain(): SubtitleScores =
    SubtitleScores(syncScore, qualityScore, translationScore, overallScore)

fun ImportResponse.toMeta(): VideoMeta =
    VideoMeta(videoId, title, duration, thumbnailUrl, hasDe, hasEn, scores.toDomain())

fun VideoMetaDto.toDomain(): VideoMeta =
    VideoMeta(videoId, title, duration, thumbnailUrl, hasDe, hasEn, scores.toDomain())
