package com.echolingo.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlayerControls(
    showSource: Boolean,
    showTrans: Boolean,
    onToggleSource: () -> Unit,
    onToggleTrans: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubtitleToggleButton("DE", showSource, onToggleSource)
        SubtitleToggleButton("EN", showTrans, onToggleTrans)
    }
}

@Composable
private fun SubtitleToggleButton(label: String, isOn: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = isOn,
        onClick = onClick,
        label = { Text(text = if (isOn) "CC $label ON" else "CC $label OFF", fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White,
        ),
    )
}
