package com.echolingo.app.ui.player.shadowing

import com.echolingo.app.domain.model.Cue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * All states the shadowing flow can be in.
 *
 * IMPORTANT: Recording and Result carry the TARGET CUE so it is never lost
 * between state transitions. Previously Recording was a singleton object and
 * the cue was stored in a separate mutable that got cleared too early.
 */
sealed class ShadowingState {
    /** Normal playback — overlay hidden. */
    data object Idle : ShadowingState()

    /**
     * Video is paused, mic is active. [targetCue] is the cue the user must repeat.
     * Shown text: the German sentence + mic animation.
     */
    data class Recording(val targetCue: Cue) : ShadowingState()

    /** Audio uploaded, waiting for Groq to respond. */
    data object Processing : ShadowingState()

    /**
     * Groq returned a transcript, similarity scored.
     * [targetCue] kept so "Listen Again" can seek back to the cue.
     */
    data class Result(
        val passed: Boolean,
        val score: Int,
        val userText: String,
        val targetText: String,
        val targetCue: Cue,
    ) : ShadowingState()

    /**
     * "Listen Again" was tapped — ExoPlayer is replaying [targetCue].
     * When positionMs >= targetCue.endMs the screen auto-transitions to Recording.
     */
    data class Listening(val targetCue: Cue) : ShadowingState()
}

@Composable
fun ShadowingOverlay(
    state: ShadowingState,
    onStopRecording: () -> Unit,
    onTryAgain: () -> Unit,
    onListenAgain: (Cue) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state !is ShadowingState.Idle,
        enter = fadeIn(),
        exit  = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ShadowingState.Recording  -> RecordingPanel(
                    targetText = state.targetCue.text,
                    onStop     = onStopRecording,
                )
                is ShadowingState.Processing -> ProcessingPanel()
                is ShadowingState.Result     -> ResultPanel(
                    state        = state,
                    onTryAgain   = onTryAgain,
                    onListenAgain = { onListenAgain(state.targetCue) },
                    onSkip       = onSkip,
                )
                is ShadowingState.Listening  -> ListeningPanel()
                else -> {}
            }
        }
    }
}

// ── Sub-panels ────────────────────────────────────────────────────────────────

@Composable
private fun RecordingPanel(targetText: String, onStop: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .background(Color(0xFF1A1A2E), RoundedCornerShape(20.dp))
            .padding(28.dp),
    ) {
        // Mic indicator
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFFEF5350), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🎤", fontSize = 32.sp)
        }

        Text(
            "Repeat what you heard:",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
        )

        // Show the German text the user must say
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = targetText,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("⏹  Done speaking", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProcessingPanel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("⏳", fontSize = 44.sp)
        Text(
            "Checking your pronunciation…",
            color = Color.White,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun ListeningPanel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("🔊", fontSize = 44.sp)
        Text(
            "Listen carefully…",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Recording will start automatically",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ResultPanel(
    state: ShadowingState.Result,
    onTryAgain: () -> Unit,
    onListenAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .background(
                if (state.passed) Color(0xFF1B5E20) else Color(0xFF3E0A0A),
                RoundedCornerShape(20.dp),
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = if (state.passed) "✅ Great job!" else "❌ Not quite…",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )

        Text(
            text = "Score: ${state.score}%",
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.80f),
        )

        if (!state.passed) {
            // Show what user said vs what they should have said
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabeledText(
                    label = "You said",
                    text  = state.userText,
                    color = Color(0xFFEF9A9A),
                )
                LabeledText(
                    label = "Target",
                    text  = state.targetText,
                    color = Color(0xFFA5D6A7),
                )
            }

            // ── Action buttons ──────────────────────────────────────────────
            // "Listen Again" replays the original cue so the user can hear it again
            // before attempting. This is the key feature for language shadowing.
            Button(
                onClick = onListenAgain,
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("🔊  Listen Again", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onTryAgain,
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("🔄  Try Again")
                }
                Button(
                    onClick = onSkip,
                    colors  = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("⏭  Skip", color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun LabeledText(label: String, text: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
        Text(text,  fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
