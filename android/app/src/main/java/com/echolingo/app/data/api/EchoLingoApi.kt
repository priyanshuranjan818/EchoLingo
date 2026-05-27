package com.echolingo.app.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface EchoLingoApi {
    @POST("api/import")
    suspend fun importVideo(@Body req: ImportRequest): ImportResponse

    @GET("api/subtitles/{videoId}/{lang}")
    suspend fun getSubtitles(
        @Path("videoId") videoId: String,
        @Path("lang") lang: String,
    ): List<CueDto>

    @GET("api/meta/{videoId}")
    suspend fun getMeta(@Path("videoId") videoId: String): VideoMetaDto

    @GET("api/video/{videoId}/stream")
    suspend fun getStreamUrl(@Path("videoId") videoId: String): StreamUrlResponse
}

data class ImportRequest(
    val url: String,
    val sourceLang: String = "de",
    val targetLang: String = "en",
)

data class ImportResponse(
    val videoId: String,
    val title: String,
    val duration: Int,
    val thumbnailUrl: String,
    val hasDe: Boolean,
    val hasEn: Boolean,
    val deSource: String?,
    val enSource: String?,
    val scores: SubtitleScoresDto,
    val cached: Boolean,
    val ready: Boolean,
)

data class VideoMetaDto(
    val videoId: String,
    val title: String,
    val duration: Int,
    val thumbnailUrl: String,
    val hasDe: Boolean,
    val hasEn: Boolean,
    val scores: SubtitleScoresDto,
)

data class SubtitleScoresDto(
    val syncScore: Int,
    val qualityScore: Int,
    val translationScore: Int,
    val overallScore: Int,
)

data class CueDto(
    val index: Int,
    val start: Double,
    val end: Double,
    val text: String,
)

data class StreamUrlResponse(
    val url: String,
)
