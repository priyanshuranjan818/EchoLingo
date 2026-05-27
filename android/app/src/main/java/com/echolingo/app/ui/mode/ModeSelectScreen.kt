package com.echolingo.app.ui.mode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.echolingo.app.data.api.ApiFactory
import com.echolingo.app.data.api.toDomain
import com.echolingo.app.data.preferences.AppSettings
import com.echolingo.app.data.preferences.SettingsRepository
import com.echolingo.app.domain.model.VideoMeta

enum class PlayMode { WATCH, SHADOW }

@Composable
fun ModeSelectScreen(
    videoId: String,
    settingsRepository: SettingsRepository,
    onModeSelected: (PlayMode) -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settings.collectAsState(
        initial = AppSettings()
    )
    var meta by remember { mutableStateOf<VideoMeta?>(null) }

    LaunchedEffect(videoId, settings.serverBaseUrl) {
        if (settings.serverBaseUrl.isNotBlank()) {
            try {
                meta = ApiFactory.create(settings.serverBaseUrl)
                    .getMeta(videoId).toDomain()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D0D1A), Color(0xFF1A1A2E))
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Back button
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color.White.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Thumbnail
            meta?.thumbnailUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.DarkGray),
                )
                Spacer(Modifier.height(16.dp))
            }

            // Title
            Text(
                text = meta?.title ?: "Loading…",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "How do you want to study?",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(32.dp))

            // ── Mode cards ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ModeCard(
                    emoji      = "🎬",
                    title      = "Watch",
                    subtitle   = "Dual subtitles\nNo interruptions",
                    gradient   = Brush.verticalGradient(
                        listOf(Color(0xFF1565C0), Color(0xFF0D47A1))
                    ),
                    borderColor = Color(0xFF42A5F5),
                    modifier   = Modifier.weight(1f),
                    onClick    = { onModeSelected(PlayMode.WATCH) },
                )
                ModeCard(
                    emoji      = "🎤",
                    title      = "Shadow",
                    subtitle   = "Pause & repeat\nafter each cue",
                    gradient   = Brush.verticalGradient(
                        listOf(Color(0xFFB71C1C), Color(0xFF7F0000))
                    ),
                    borderColor = Color(0xFFEF5350),
                    modifier   = Modifier.weight(1f),
                    onClick    = { onModeSelected(PlayMode.SHADOW) },
                )
            }

            Spacer(Modifier.height(20.dp))

            // Description rows
            ModeDescription(
                emoji = "🎬", mode = "Watch Mode",
                desc  = "Video plays continuously. German + English subtitles shown. Great for comprehension."
            )
            Spacer(Modifier.height(12.dp))
            ModeDescription(
                emoji = "🎤", mode = "Shadow Mode",
                desc  = "Video pauses after each sentence. Speak it aloud. Scored by AI. Great for speaking."
            )
        }
    }
}

@Composable
private fun ModeCard(
    emoji: String,
    title: String,
    subtitle: String,
    gradient: Brush,
    borderColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .border(1.5.dp, borderColor.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 38.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun ModeDescription(emoji: String, mode: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(emoji, fontSize = 22.sp)
        Column {
            Text(mode, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
