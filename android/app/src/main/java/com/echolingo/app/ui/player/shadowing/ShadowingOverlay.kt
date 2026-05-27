package com.echolingo.app.ui.player.shadowing

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
import androidx.compose.material3.MaterialTheme
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
 * The full-screen overlay shown during shadowing mode.
 *
 * States:
 *  RECORDING   → mic icon + "Speak now…" + Stop button
 *  PROCESSING  → spinner text
 *  RESULT_PASS → ✅ green feedback + auto-resumes
 *  RESULT_FAIL → ❌ what you said vs what it should be + Try Again / Skip
 */
sealed class ShadowingState {
    data object Idle       : ShadowingState()
    data object Recording  : ShadowingState()
    data object Processing : ShadowingState()
    data class  Result(
        val passed: Boolean,
        val score: Int,
        val userText: String,
        val targetText: String,
    ) : ShadowingState()
}

@Composable
fun ShadowingOverlay(
    state: ShadowingState,
    onStopRecording: () -> Unit,
    onTryAgain: () -> Unit,
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
                .background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ShadowingState.Recording -> RecordingPanel(onStop = onStopRecording)
                is ShadowingState.Processing -> ProcessingPanel()
                is ShadowingState.Result    -> ResultPanel(
                    state    = state,
                    onTryAgain = onTryAgain,
                    onSkip   = onSkip,
                )
                else -> {}
            }
        }
    }
}

// ---- Sub-panels -----------------------------------------------------------

@Composable
private fun RecordingPanel(onStop: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        // Pulsing mic indicator
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color(0xFFEF5350), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🎤", fontSize = 36.sp)
        }

        Text(
            "Speak now…",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Say the German sentence you just heard",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
        ) {
            Text("⏹ Done", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProcessingPanel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⏳", fontSize = 40.sp)
        Text("Checking your pronunciation…", color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun ResultPanel(
    state: ShadowingState.Result,
    onTryAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .background(
                if (state.passed) Color(0xFF1B5E20) else Color(0xFF4A0F0F),
                RoundedCornerShape(16.dp),
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (state.passed) "✅ Great job!" else "❌ Keep trying!",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )

        Text(
            text = "Score: ${state.score}%",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.85f),
        )

        if (!state.passed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledText(label = "You said", text = state.userText, color = Color(0xFFEF9A9A))
                LabeledText(label = "Target",   text = state.targetText, color = Color(0xFFA5D6A7))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onTryAgain,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("🔄 Try Again")
                }
                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                ) {
                    Text("⏭ Skip", color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun LabeledText(label: String, text: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
        Text(text, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
