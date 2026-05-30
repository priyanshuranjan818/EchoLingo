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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

// ── Seek flash model ──────────────────────────────────────────────────────────
private sealed class SeekFlash {
    data object Back    : SeekFlash()   // ◀◀ 10 s
    data object Forward : SeekFlash()   // ▶▶ 20 s
}

// ── Time formatter ─────────────────────────────────────────────────────────────
private fun formatMs(ms: Long): String {
    val totalSecs = (ms / 1_000L).coerceAtLeast(0L)
    val h  = totalSecs / 3600
    val m  = (totalSecs % 3600) / 60
    val s  = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

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
    var sourceCues     by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var transCues      by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var activeSource   by remember { mutableStateOf<Cue?>(null) }
    var activeTrans    by remember { mutableStateOf<Cue?>(null) }
    var positionMs     by remember { mutableLongStateOf(0L) }
    var durationMs     by remember { mutableLongStateOf(0L) }
    var status         by remember { mutableStateOf("Loading video...") }

    // Local Y offset for smooth drag (no DataStore latency during drag)
    var subtitleYPx    by remember { mutableFloatStateOf(-1f) }

    // ── Seek state ────────────────────────────────────────────────────────────
    var isSeeking      by remember { mutableStateOf(false) }
    var seekFraction   by remember { mutableFloatStateOf(0f) }
    var seekFlash      by remember { mutableStateOf<SeekFlash?>(null) }

    // ── Controls visibility ───────────────────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(false) }
    var hideJob          by remember { mutableStateOf<Job?>(null) }

    fun showControls() {
        controlsVisible = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3_000)
            controlsVisible = false
        }
    }
    fun toggleControls() {
        if (controlsVisible) { hideJob?.cancel(); controlsVisible = false }
        else showControls()
    }

    // ── Seek helpers ──────────────────────────────────────────────────────────
    fun seekBack() {
        player.seekTo((positionMs - 10_000L).coerceAtLeast(0L))
        seekFlash = SeekFlash.Back
        scope.launch { delay(700); seekFlash = null }
    }
    fun seekForward() {
        val target = (positionMs + 20_000L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        player.seekTo(target)
        seekFlash = SeekFlash.Forward
        scope.launch { delay(700); seekFlash = null }
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

    // ── Position / duration polling ───────────────────────────────────────────
    LaunchedEffect(player, sourceCues, transCues) {
        while (true) {
            if (!isSeeking) {
                positionMs   = player.currentPosition
                val dur      = player.duration
                if (dur > 0) durationMs = dur
            }
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
        val maxWidthPx  = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        // Initialise subtitle Y from saved setting on first composition
        if (subtitleYPx < 0f) subtitleYPx = maxHeightPx * settings.subtitleYPercent

        // ── ExoPlayer (no built-in controls) ─────────────────────────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player   = player
                    useController = false
                    layoutParams  = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Tap / double-tap gesture layer ────────────────────────────────
        // Sits below the subtitle overlay so subtitle drags don't trigger pause.
        // onTap   → pause / resume
        // onDoubleTap left half  → ◀◀ 10 s
        // onDoubleTap right half → ▶▶ 20 s
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        onDoubleTap = { offset ->
                            if (offset.x < maxWidthPx / 2f) seekBack() else seekForward()
                        },
                    )
                },
        )

        // ── Status text ───────────────────────────────────────────────────
        if (status.isNotBlank()) {
            Text(
                text     = status,
                color    = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // ── Seek flash indicator ──────────────────────────────────────────
        seekFlash?.let { flash ->
            Box(
                modifier = Modifier
                    .align(
                        if (flash is SeekFlash.Back) Alignment.CenterStart
                        else Alignment.CenterEnd
                    )
                    .padding(horizontal = 28.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text     = if (flash is SeekFlash.Back) "◀◀" else "▶▶",
                        color    = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text     = if (flash is SeekFlash.Back) "10s" else "20s",
                        color    = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // ── Subtitle overlay (draggable, vertical) ────────────────────────
        SubtitleOverlay(
            sourceCue  = activeSource,
            transCue   = activeTrans,
            showSource = settings.showSource,
            showTrans  = settings.showTrans,
            fontSize   = settings.fontSize,
            onDrag     = { deltaY ->
                subtitleYPx = (subtitleYPx + deltaY)
                    .coerceIn(maxHeightPx * 0.05f, maxHeightPx * 0.92f)
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

        // ── Thin progress bar — ALWAYS visible at bottom ──────────────────
        if (durationMs > 0) {
            LinearProgressIndicator(
                progress    = { positionMs.toFloat() / durationMs.toFloat() },
                modifier    = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color       = Color(0xFF2196F3),
                trackColor  = Color.White.copy(alpha = 0.18f),
            )
        }

        // ── Controls overlay (fades in/out, auto-hides after 3 s) ─────────
        AnimatedVisibility(
            visible  = controlsVisible,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Intercept all taps inside the panel so they don't toggle pause
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
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
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

                // ── Bottom: seekbar + time ────────────────────────────────
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (durationMs > 0) {
                        val fraction = if (isSeeking) seekFraction
                                       else positionMs.toFloat() / durationMs.toFloat()

                        Slider(
                            value               = fraction.coerceIn(0f, 1f),
                            onValueChange       = { isSeeking = true; seekFraction = it },
                            onValueChangeFinished = {
                                player.seekTo((seekFraction * durationMs).toLong())
                                isSeeking = false
                                showControls()   // reset the 3 s auto-hide timer
                            },
                            colors = SliderDefaults.colors(
                                thumbColor       = Color(0xFF2196F3),
                                activeTrackColor = Color(0xFF2196F3),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp),
                        )

                        // Time labels
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatMs(if (isSeeking) (seekFraction * durationMs).toLong() else positionMs),
                                color    = Color.White,
                                fontSize = 11.sp,
                            )
                            Text(
                                formatMs(durationMs),
                                color    = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                            )
                        }
                    }

                    // Reset subtitle position
                    TextButton(
                        onClick  = {
                            subtitleYPx = maxHeightPx * 0.78f
                            scope.launch { settingsRepository.setSubtitleYPercent(0.78f) }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Reset Subtitles", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Settings gear — always visible in top-right corner ────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(40.dp)
                .background(
                    Color.Black.copy(alpha = if (controlsVisible) 0.65f else 0.30f),
                    CircleShape,
                )
                .clickable { toggleControls() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text     = "⚙",
                fontSize = 20.sp,
                color    = Color.White.copy(alpha = if (controlsVisible) 1f else 0.60f),
            )
        }
    }
}
