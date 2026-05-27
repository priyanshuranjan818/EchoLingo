package com.echolingo.app.domain.model

data class Cue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
