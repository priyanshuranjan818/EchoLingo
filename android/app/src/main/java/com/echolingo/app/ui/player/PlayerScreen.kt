package com.echolingo.app.ui.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.Job
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

    // ── Controls visibility (auto-hides after 3 s) ────────────────────────────
    var controlsVisible by remember { mutableStateOf(false) }
    var hideJob         by remember { mutableStateOf<Job?>(null) }

    fun showControls() {
        controlsVisible = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3_000)
            controlsVisible = false
        }
    }

    fun toggleControls() {
        if (controlsVisible) {
            hideJob?.cancel()
            controlsVisible = false
        } else {
            showControls()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status = error.cause?.message ?: "Could not play video stream"
            }
        }
        player.addListener(listener)
        onDispose {
            hideJob?.cancel()
            player.removeListener(listener)
            player.release()
        }
    }

    // ── Load video ────────────────────────────────────────────────────────────
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

    // ── Position polling ──────────────────────────────────────────────────────
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
            .background(Color.Black)
            // Tap anywhere on the video = toggle pause / resume
            .pointerInput(Unit) {
                detectTapGestures {
                    if (player.isPlaying) player.pause() else player.play()
                }
            },
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val yPx         = maxHeightPx * settings.subtitleYPercent

        // ── ExoPlayer (no built-in controls — we own all touch) ───────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player   = player
                    useController = false   // custom controls only
                    layoutParams  = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Status text
        if (status.isNotBlank()) {
            Text(
                text     = status,
                color    = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        // ── Subtitle overlay (draggable) ──────────────────────────────────
        SubtitleOverlay(
            sourceCue  = activeSource,
            transCue   = activeTrans,
            showSource = settings.showSource,
            showTrans  = settings.showTrans,
            fontSize   = settings.fontSize,
            onDrag     = { deltaY ->
                val next = ((yPx + deltaY) / maxHeightPx).coerceIn(0.12f, 0.86f)
                scope.launch { settingsRepository.setSubtitleYPercent(next) }
            },
            modifier   = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, yPx.roundToInt()) },
        )

        // ── Controls panel (fades in/out, auto-hides after 3 s) ──────────
        AnimatedVisibility(
            visible  = controlsVisible,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Intercept all taps inside the controls panel so they don't
            // toggle play/pause on the background layer.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { /* consume */ } },
            ) {
                // ── Top bar: Back + CC toggles ────────────────────────────
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    // Back button
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }

                    // CC toggles
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

                // ── Reset subtitle position ───────────────────────────────
                TextButton(
                    onClick  = {
                        scope.launch { settingsRepository.setSubtitleYPercent(0.78f) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Text(
                        "Reset Subtitles",
                        color    = Color.White.copy(alpha = 0.80f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // ── Settings gear button — always visible in top-right corner ─────
        // Semi-transparent when controls hidden, bright when visible.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(40.dp)
                .background(
                    Color.Black.copy(alpha = if (controlsVisible) 0.65f else 0.35f),
                    CircleShape,
                )
                .clickable { toggleControls() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text     = "⚙",
                fontSize = 20.sp,
                color    = Color.White.copy(alpha = if (controlsVisible) 1f else 0.65f),
            )
        }
    }
}
