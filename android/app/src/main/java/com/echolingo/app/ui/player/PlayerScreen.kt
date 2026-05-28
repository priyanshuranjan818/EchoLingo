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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.echolingo.app.data.api.ApiFactory
import com.echolingo.app.data.api.toDomain
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
    shadowingEnabled: Boolean = false,       // set by ModeSelectScreen
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val player  = remember { ExoPlayer.Builder(context).build() }

    // --- Subtitle state ---
    var sourceCues   by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var transCues    by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var activeSource by remember { mutableStateOf<Cue?>(null) }
    var activeTrans  by remember { mutableStateOf<Cue?>(null) }
    var positionMs   by remember { mutableLongStateOf(0L) }
    var status       by remember { mutableStateOf("Loading video...") }

    // --- Shadowing state ---
    // If the user chose Shadow mode, start ON automatically
    var shadowingOn      by remember { mutableStateOf(shadowingEnabled) }
    var shadowingState   by remember { mutableStateOf<ShadowingState>(ShadowingState.Idle) }
    val recorder         = remember { ShadowingRecorder(context) }
    var lastCueForShadow by remember { mutableStateOf<Cue?>(null) }

    // Mic permission launcher
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) shadowingOn = true
    }

    // --- Load video ---
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

    // --- Position polling + shadowing trigger ---
    LaunchedEffect(player, sourceCues, transCues, shadowingOn) {
        while (true) {
            positionMs   = player.currentPosition
            activeSource = findActiveCue(sourceCues, positionMs)
            activeTrans  = findActiveCue(transCues,  positionMs)

            // Shadowing: detect when a cue just ended
            if (shadowingOn && shadowingState is ShadowingState.Idle) {
                val prev = lastCueForShadow
                val cur  = activeSource
                // We just left a cue (cur is null or different) and the previous was valid
                if (prev != null && cur != prev &&
                    positionMs > prev.endMs && positionMs < prev.endMs + 800) {
                    // Pause video and start recording
                    player.pause()
                    lastCueForShadow  = null
                    shadowingState    = ShadowingState.Recording
                    recorder.startRecording()
                }
                if (cur != null) lastCueForShadow = cur
            }

            delay(100)
        }
    }

    DisposableEffect(player) {
        onDispose {
            recorder.cleanup()
            player.release()
        }
    }

    // --- Functions wired to shadowing UI ---
    fun stopRecordingAndEvaluate(targetCue: Cue?) {
        shadowingState = ShadowingState.Processing
        val audioFile = recorder.stopRecording()
        if (audioFile == null || targetCue == null) {
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
            } catch (e: Exception) {
                ""
            }
            audioFile.delete()
            val score = SimilarityEngine.score(transcript, targetCue.text)
            shadowingState = ShadowingState.Result(
                passed     = score >= SHADOW_PASS_THRESHOLD,
                score      = score,
                userText   = transcript.ifBlank { "(nothing heard)" },
                targetText = targetCue.text,
            )
            // Auto-resume after 2 s if passed
            if (score >= SHADOW_PASS_THRESHOLD) {
                delay(2_000)
                shadowingState = ShadowingState.Idle
                player.play()
            }
        }
    }

    // Capture cue at moment of stopping for the lambda closures
    val cueAtRecordingStop = remember(activeSource) { activeSource }

    // --- UI ---
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
                        showSource    = settings.showSource,
                        showTrans     = settings.showTrans,
                        onToggleSource = {
                            scope.launch { settingsRepository.setShowSource(!settings.showSource) }
                        },
                        onToggleTrans = {
                            scope.launch { settingsRepository.setShowTrans(!settings.showTrans) }
                        },
                    )

                    // Only show the toggle chip in Watch mode (in Shadow mode it auto-started)
                    if (!shadowingEnabled) {
                        FilterChip(
                            selected = shadowingOn,
                            onClick  = {
                                if (!shadowingOn) {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    shadowingOn = false
                                    shadowingState = ShadowingState.Idle
                                    if (recorder.isRecording()) recorder.stopRecording()
                                    player.play()
                                }
                            },
                            label = { Text(if (shadowingOn) "🎤 Shadow ON" else "Shadow", fontSize = androidx.compose.ui.unit.TextUnit.Unspecified) },
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
            onClick = { scope.launch { settingsRepository.setSubtitleYPercent(0.78f) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Text("Reset Subtitles")
        }

        // Shadowing overlay (sits on top of everything)
        ShadowingOverlay(
            state            = shadowingState,
            onStopRecording  = { stopRecordingAndEvaluate(cueAtRecordingStop ?: lastCueForShadow) },
            onTryAgain       = {
                shadowingState = ShadowingState.Recording
                recorder.startRecording()
            },
            onSkip = {
                shadowingState = ShadowingState.Idle
                player.play()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

