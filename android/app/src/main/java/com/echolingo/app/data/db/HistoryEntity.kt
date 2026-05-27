package com.echolingo.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val watchedAt: Long,          // epoch ms
    val durationSec: Int,
    val deSource: String,
    val enSource: String,
    val overallScore: Int,
)
