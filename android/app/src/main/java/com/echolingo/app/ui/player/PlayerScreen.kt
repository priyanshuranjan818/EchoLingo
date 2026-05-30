package com.echolingo.app.ui.player

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.echolingo.app.data.api.ApiFactory
import com.echolingo.app.data.api.toDomain
import com.echolingo.app.data.preferences.AppSettings
import com.echolingo.app.data.preferences.SettingsRepository
import com.echolingo.app.data.repository.HistoryRepository
import com.echolingo.app.domain.model.Cue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    videoId: String,
    settingsRepository: SettingsRepository,
    historyRepository: HistoryRepository,
    onBack: () -> Unit,
) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setUserAgent("EchoLingo/1.0")
                )
            )
            .build()
    }

    // ── Subtitle state ────────────────────────────────────────────────────────
    var sourceCues   by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var transCues    by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var activeSource by remember { mutableStateOf<Cue?>(null) }
    var activeTrans  by remember { mutableStateOf<Cue?>(null) }
    var positionMs   by remember { mutableLongStateOf(0L) }
    var status       by remember { mutableStateOf("Loading video...") }

    // Local Y for instant drag feedback (no DataStore round-trip lag during drag)
    var subtitleYPx  by remember { mutableFloatStateOf(-1f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status = error.cause?.message ?: "Could not play video stream"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // ── Load video + subtitles ─────────────────────────────────────────────────
    LaunchedEffect(videoId, settings.serverBaseUrl) {
        try {
            val api    = ApiFactory.create(settings.serverBaseUrl)
            sourceCues = api.getSubtitles(videoId, "de").map { it.toDomain() }
            transCues  = api.getSubtitles(videoId, "en").map { it.toDomain() }
            val meta   = api.getMeta(videoId).toDomain()
            scope.launch { historyRepository.record(meta) }

            val streamUrl = settings.serverBaseUrl.trimEnd('/') + "/api/video/$videoId/stream"
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
            status = ""
        } catch (e: Exception) {
            status = e.message ?: "Could not load player"
        }
    }

    // ── Subtitle sync loop (50 ms polling) ────────────────────────────────────
    LaunchedEffect(player, sourceCues, transCues) {
        while (true) {
            positionMs   = player.currentPosition
            activeSource = findActiveCue(sourceCues, positionMs)
            activeTrans  = findActiveCue(transCues,  positionMs)
            delay(50)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // Initialise subtitle Y on first composition
        if (subtitleYPx < 0f) subtitleYPx = maxHeightPx * settings.subtitleYPercent

        // ── ExoPlayer with full YouTube-style controls ────────────────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player   = player
                    useController = true          // full seekbar, play/pause, time etc.
                    // Hide ExoPlayer's own subtitle renderer — we draw our own on top
                    subtitleView?.visibility = View.GONE
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                // Keep player reference fresh after recomposition
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Error / loading status ────────────────────────────────────────
        if (status.isNotBlank()) {
            Text(
                text     = status,
                color    = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        // ── Dual subtitle overlay (DE + EN, draggable vertically) ─────────
        SubtitleOverlay(
            sourceCue  = activeSource,
            transCue   = activeTrans,
            showSource = settings.showSource,
            showTrans  = settings.showTrans,
            fontSize   = settings.fontSize,
            onDrag     = { deltaY ->
                subtitleYPx = (subtitleYPx + deltaY)
                    .coerceIn(maxHeightPx * 0.05f, maxHeightPx * 0.90f)
            },
            onDragEnd  = {
                scope.launch {
                    settingsRepository.setSubtitleYPercent(subtitleYPx / maxHeightPx)
                }
            },
            modifier   = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, subtitleYPx.roundToInt()) },
        )

        // ── Top bar: Back + CC toggles ────────────────────────────────────
        // Sits in the top-left/right corners — always visible over the video.
        // ExoPlayer's own controller (seekbar etc.) is at the bottom.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            // Back button
            TextButton(onClick = onBack) {
                Text(
                    "← Back",
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // CC DE / CC EN toggle buttons
            PlayerControls(
                showSource     = settings.showSource,
                showTrans      = settings.showTrans,
                onToggleSource = {
                    scope.launch { settingsRepository.setShowSource(!settings.showSource) }
                },
                onToggleTrans  = {
                    scope.launch { settingsRepository.setShowTrans(!settings.showTrans) }
                },
            )
        }
    }
}
