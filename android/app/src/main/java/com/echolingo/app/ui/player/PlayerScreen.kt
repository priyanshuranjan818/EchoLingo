package com.echolingo.app.ui.player

import android.Manifest
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
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
import com.echolingo.app.ui.player.shadowing.ShadowingOverlay
import com.echolingo.app.ui.player.shadowing.ShadowingRecorder
import com.echolingo.app.ui.player.shadowing.ShadowingState
import com.echolingo.app.ui.player.shadowing.SimilarityEngine
import com.echolingo.app.ui.player.shadowing.transcribeAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SHADOW_PASS_THRESHOLD = 70

@Composable
fun PlayerScreen(
    videoId: String,
    settingsRepository: SettingsRepository,
    historyRepository: HistoryRepository,
    shadowingEnabled: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())

    val player = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("EchoLingo/1.0")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()
    }

    // ── Subtitle state ────────────────────────────────────────────────────────
    var sourceCues   by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var transCues    by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var activeSource by remember { mutableStateOf<Cue?>(null) }
    var activeTrans  by remember { mutableStateOf<Cue?>(null) }
    var positionMs   by remember { mutableLongStateOf(0L) }
    var status       by remember { mutableStateOf("Loading video...") }

    // ── Shadowing state ───────────────────────────────────────────────────────
    var shadowingOn    by remember { mutableStateOf(shadowingEnabled) }
    var shadowingState by remember { mutableStateOf<ShadowingState>(ShadowingState.Idle) }
    val recorder       = remember { ShadowingRecorder(context) }

    // Tracks the LAST cue that was fully active so we know which cue to shadow.
    // This must NOT be cleared when shadowing starts — it is the target cue.
    var lastSeenCue by remember { mutableStateOf<Cue?>(null) }

    // Mic permission
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) shadowingOn = true }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status = error.cause?.message ?: error.message ?: "Could not play video stream"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            recorder.cleanup()
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

    // ── Position polling + shadowing trigger ──────────────────────────────────
    LaunchedEffect(player, sourceCues, transCues, shadowingOn) {
        while (true) {
            positionMs   = player.currentPosition
            activeSource = findActiveCue(sourceCues, positionMs)
            activeTrans  = findActiveCue(transCues,  positionMs)

            val cur = activeSource

            // ── Shadowing: trigger when a cue ends ────────────────────────
            if (shadowingOn && shadowingState is ShadowingState.Idle) {
                val prev = lastSeenCue
                // Detect: we had a valid cue, it just ended, and we're within 800ms of its end
                if (prev != null
                    && cur != prev
                    && positionMs > prev.endMs
                    && positionMs < prev.endMs + 800L
                ) {
                    // 'prev' IS the target cue the user must repeat.
                    // Store it in the state so it survives all state transitions.
                    player.pause()
                    shadowingState = ShadowingState.Recording(prev)
                    recorder.startRecording(onAutoStop = {
                        // Called by MediaRecorder when 8s max duration reached —
                        // automatically evaluate without user pressing any button
                        scope.launch { stopRecordingAndEvaluate() }
                    })
                }
                if (cur != null) lastSeenCue = cur
            }

            // ── Shadowing: "Listen Again" auto-stop ───────────────────────
            // When replaying a cue for the user to hear, auto-pause at cue end
            // and immediately switch to Recording so they can repeat it.
            val listenState = shadowingState
            if (listenState is ShadowingState.Listening) {
                if (positionMs >= listenState.targetCue.endMs) {
                    player.pause()
                    delay(300)
                    shadowingState = ShadowingState.Recording(listenState.targetCue)
                    recorder.startRecording(onAutoStop = {
                        scope.launch { stopRecordingAndEvaluate() }
                    })
                }
            }

            delay(50)
        }
    }

    // ── Shadowing actions ─────────────────────────────────────────────────────

    /** Called when user taps "Done speaking". Evaluates against the cue stored in state. */
    fun stopRecordingAndEvaluate() {
        val recordingState = shadowingState as? ShadowingState.Recording ?: return
        val targetCue = recordingState.targetCue

        shadowingState = ShadowingState.Processing
        val audioFile  = recorder.stopRecording()

        if (audioFile == null) {
            shadowingState = ShadowingState.Idle
            player.play()
            return
        }

        scope.launch {
            val transcript = try {
                transcribeAudio(
                    serverBaseUrl = settings.serverBaseUrl,
                    audioFile     = audioFile,
                    lang          = "de",
                )
            } catch (e: Exception) { "" }

            audioFile.delete()

            val score = SimilarityEngine.score(transcript, targetCue.text)
            shadowingState = ShadowingState.Result(
                passed     = score >= SHADOW_PASS_THRESHOLD,
                score      = score,
                userText   = transcript.ifBlank { "(nothing heard)" },
                targetText = targetCue.text,
                targetCue  = targetCue,
            )

            // Auto-resume if passed
            if (score >= SHADOW_PASS_THRESHOLD) {
                delay(2_000)
                shadowingState = ShadowingState.Idle
                player.play()
            }
        }
    }

    /** "🔊 Listen Again" — seek back to the cue, play it, then auto-start recording. */
    fun listenAgain(cue: Cue) {
        shadowingState = ShadowingState.Listening(cue)
        player.seekTo(cue.startMs)
        player.play()
    }

    /** "🔄 Try Again" — re-start recording for the same cue without replaying. */
    fun tryAgain() {
        val resultState = shadowingState as? ShadowingState.Result ?: return
        shadowingState = ShadowingState.Recording(resultState.targetCue)
        recorder.startRecording()
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val yPx = maxHeightPx * settings.subtitleYPercent

        // ExoPlayer view
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (status.isNotBlank()) {
            Text(
                text = status,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        // Draggable subtitle overlay
        SubtitleOverlay(
            sourceCue  = activeSource,
            transCue   = activeTrans,
            showSource = settings.showSource,
            showTrans  = settings.showTrans,
            fontSize   = settings.fontSize,
            onDrag = { deltaY ->
                val next = ((yPx + deltaY) / maxHeightPx).coerceIn(0.12f, 0.86f)
                scope.launch { settingsRepository.setSubtitleYPercent(next) }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, yPx.roundToInt()) },
        )

        // Top control bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

                    if (!shadowingEnabled) {
                        FilterChip(
                            selected = shadowingOn,
                            onClick  = {
                                if (!shadowingOn) {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    shadowingOn    = false
                                    shadowingState = ShadowingState.Idle
                                    if (recorder.isRecording()) recorder.stopRecording()
                                    player.play()
                                }
                            },
                            label = {
                                Text(
                                    if (shadowingOn) "🎤 Shadow ON" else "Shadow",
                                    fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEF5350),
                                selectedLabelColor     = Color.White,
                            ),
                        )
                    }
                }
            }
        }

        // Reset subtitle position button
        Button(
            onClick  = { scope.launch { settingsRepository.setSubtitleYPercent(0.78f) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Text("Reset Subtitles")
        }

        // Shadowing overlay (on top of everything)
        ShadowingOverlay(
            state           = shadowingState,
            onStopRecording = { stopRecordingAndEvaluate() },
            onTryAgain      = { tryAgain() },
            onListenAgain   = { cue -> listenAgain(cue) },
            onSkip          = {
                shadowingState = ShadowingState.Idle
                player.play()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
