package com.echolingo.app.data.repository

import com.echolingo.app.data.db.HistoryDao
import com.echolingo.app.data.db.HistoryEntity
import com.echolingo.app.domain.model.VideoMeta
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: HistoryDao) {

    val history: Flow<List<HistoryEntity>> = dao.observeAll()

    suspend fun record(meta: VideoMeta) {
        dao.upsert(
            HistoryEntity(
                videoId     = meta.videoId,
                title       = meta.title,
                thumbnailUrl = meta.thumbnailUrl,
                watchedAt   = System.currentTimeMillis(),
                durationSec = meta.duration,
                deSource    = if (meta.hasDe) "de" else "",
                enSource    = if (meta.hasEn) "en" else "",
                overallScore = meta.scores.overallScore,
            )
        )
    }

    suspend fun delete(videoId: String) = dao.delete(videoId)

    suspend fun clearAll() = dao.clearAll()
}
