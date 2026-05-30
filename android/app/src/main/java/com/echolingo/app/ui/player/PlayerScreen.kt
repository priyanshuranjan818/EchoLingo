package com.echolingo.app.ui.player

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Long-press threshold in ms before hold-to-pause activates. */
private const val HOLD_THRESHOLD_MS = 500L

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

    // Local Y for smooth subtitle drag (no DataStore round-trip delay per frame)
    var subtitleYPx  by remember { mutableFloatStateOf(-1f) }

    // ── Hold-to-pause state ───────────────────────────────────────────────────
    val isHolding   = remember { mutableStateOf(false) }
    val holdJob     = remember { mutableStateOf<Job?>(null) }
    // Touch-down position — used to cancel hold if the finger moves (e.g. seekbar drag)
    val downX       = remember { mutableFloatStateOf(0f) }
    val downY       = remember { mutableFloatStateOf(0f) }

    // ── Controller / top-bar visibility ──────────────────────────────────────
    // Mirrors ExoPlayer's own controller visibility so our Back + CC bar
    // hides and shows in perfect sync.
    var controllerVisible by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status = error.cause?.message ?: "Could not play video stream"
            }
        }
        player.addListener(listener)
        onDispose {
            holdJob.value?.cancel()
            player.removeListener(listener)
            player.release()
        }
    }

    // ── Load video + subtitles ────────────────────────────────────────────────
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

    // ── Subtitle sync loop ────────────────────────────────────────────────────
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

        // Initialise subtitle Y from saved setting on first composition
        if (subtitleYPx < 0f) subtitleYPx = maxHeightPx * settings.subtitleYPercent

        // ── ExoPlayer with full native controls ───────────────────────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player   = player
                    useController = true          // full seekbar, play/pause, time
                    subtitleView?.visibility = View.GONE   // hide ExoPlayer CC — we draw our own
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player

                // ── Sync top-bar with ExoPlayer controller visibility ─────
                view.setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = (visibility == View.VISIBLE)
                    }
                )

                // ── Hold-to-pause touch listener ──────────────────────────
                // Returns false so ExoPlayer STILL handles the event normally
                // (seekbar drag, play/pause tap, double-tap ±10 s — all work).
                view.setOnTouchListener { _, event ->
                    when (event.actionMasked) {

                        MotionEvent.ACTION_DOWN -> {
                            downX.floatValue = event.x
                            downY.floatValue = event.y
                            holdJob.value?.cancel()
                            holdJob.value = scope.launch {
                                delay(HOLD_THRESHOLD_MS)
                                // Only activate hold if player is actually playing
                                if (player.isPlaying) {
                                    player.pause()
                                    isHolding.value  = true
                                }
                            }
                        }

                        MotionEvent.ACTION_MOVE -> {
                            // Cancel hold if finger moves more than ~8 dp
                            // (user is probably dragging the seekbar, not holding)
                            val movedX = abs(event.x - downX.floatValue)
                            val movedY = abs(event.y - downY.floatValue)
                            if (movedX > 24f || movedY > 24f) {
                                holdJob.value?.cancel()
                                holdJob.value = null
                            }
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            holdJob.value?.cancel()
                            holdJob.value = null
                            if (isHolding.value) {
                                player.play()
                                isHolding.value  = false
                            }
                        }
                    }
                    false   // ← let ExoPlayer handle the event as normal
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Error / loading text ──────────────────────────────────────────
        if (status.isNotBlank()) {
            Text(
                text     = status,
                color    = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // ── Dual subtitle overlay (draggable vertically) ──────────────────
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

        // ── Top bar: Back + CC toggles ─────────────────────────────────────
        // Fades in/out in sync with ExoPlayer's own controller visibility.
        AnimatedVisibility(
            visible  = controllerVisible,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.50f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
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
        }
    }
}
