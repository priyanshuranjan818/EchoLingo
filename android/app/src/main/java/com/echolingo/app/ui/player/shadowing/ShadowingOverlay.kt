package com.echolingo.app.ui.player.shadowing

import com.echolingo.app.domain.model.Cue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class ShadowingState {
    data object Idle       : ShadowingState()
    data class  Recording(val targetCue: Cue) : ShadowingState()
    data object Processing : ShadowingState()
    data class  Result(
        val passed: Boolean,
        val score: Int,
        val userText: String,
        val targetText: String,
        val targetCue: Cue,
    ) : ShadowingState()
    data class Listening(val targetCue: Cue) : ShadowingState()
}

/**
 * Clean, minimal shadowing overlay.
 *
 * Design principles:
 *  - NO full-screen dark overlay — video stays visible at all times
 *  - Recording state: just a pulsing mic button at the bottom. Tap to stop early,
 *    or it auto-stops at 8 s. No "Speak now" text, no popup.
 *  - Result (pass): small green pill fades in at bottom, auto-dismisses in 2 s
 *  - Result (fail): compact card slides up from bottom with Listen Again / Try Again / Skip
 *  - Processing: tiny spinner at bottom centre
 */
@Composable
fun ShadowingOverlay(
    state: ShadowingState,
    onStopRecording: () -> Unit,
    onTryAgain: () -> Unit,
    onListenAgain: (Cue) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {

        // ── Recording: pulsing mic at bottom, NO background ──────────────────
        AnimatedVisibility(
            visible = state is ShadowingState.Recording,
            enter   = fadeIn(),
            exit    = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PulsingMicButton(onClick = onStopRecording)
        }

        // ── Processing: tiny pill at bottom ──────────────────────────────────
        AnimatedVisibility(
            visible = state is ShadowingState.Processing,
            enter   = fadeIn(),
            exit    = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("⏳  Checking…", color = Color.White, fontSize = 14.sp)
            }
        }

        // ── Listening: subtle pill at bottom ─────────────────────────────────
        AnimatedVisibility(
            visible = state is ShadowingState.Listening,
            enter   = fadeIn(),
            exit    = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .background(Color(0xFF1565C0).copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("🔊  Listen…  Recording starts automatically", color = Color.White, fontSize = 13.sp)
            }
        }

        // ── Result: slides up from bottom ────────────────────────────────────
        AnimatedVisibility(
            visible = state is ShadowingState.Result,
            enter   = slideInVertically { it } + fadeIn(),
            exit    = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (state is ShadowingState.Result) {
                ResultCard(
                    state         = state,
                    onTryAgain    = onTryAgain,
                    onListenAgain = { onListenAgain(state.targetCue) },
                    onSkip        = onSkip,
                )
            }
        }
    }
}

// ── Pulsing mic ───────────────────────────────────────────────────────────────

@Composable
private fun PulsingMicButton(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(84.dp)
                .scale(scale)
                .background(Color(0xFFEF5350).copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Inner mic button — tap to stop early
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFFEF5350), CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text("🎤", fontSize = 28.sp)
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            "Tap to stop",
            color     = Color.White.copy(alpha = 0.55f),
            fontSize  = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Result card ───────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(
    state: ShadowingState.Result,
    onTryAgain: () -> Unit,
    onListenAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (state.passed) Color(0xFF1B5E20) else Color(0xFF1C1C1E),
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Score row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = if (state.passed) "✅  Great job!" else "❌  Not quite…",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
            )
            Text(
                text    = "${state.score}%",
                fontSize = 16.sp,
                color   = if (state.passed) Color(0xFF69F0AE) else Color(0xFFEF9A9A),
                fontWeight = FontWeight.Bold,
            )
        }

        if (!state.passed) {
            // What you said vs target — compact two-line view
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniLabel(label = "You said", text = state.userText, color = Color(0xFFEF9A9A))
                MiniLabel(label = "Target",   text = state.targetText, color = Color(0xFFA5D6A7))
            }

            // Action buttons
            Button(
                onClick  = onListenAgain,
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("🔊  Listen Again", color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick  = onTryAgain,
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.weight(1f),
                ) { Text("🔄  Try Again") }

                Button(
                    onClick  = onSkip,
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                    modifier = Modifier.weight(1f),
                ) { Text("⏭  Skip", color = Color.White) }
            }
        }
    }
}

@Composable
private fun MiniLabel(label: String, text: String, color: Color) {
    Column {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
        Text(text,  fontSize = 14.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
