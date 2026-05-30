package com.echolingo.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echolingo.app.domain.model.Cue
import com.echolingo.app.domain.model.FontSize

@Composable
fun SubtitleOverlay(
    sourceCue: Cue?,
    transCue: Cue?,
    showSource: Boolean,
    showTrans: Boolean,
    fontSize: FontSize,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if ((!showSource && !showTrans) || (sourceCue == null && transCue == null)) return

    val sourceSize = when (fontSize) {
        FontSize.S -> 13.sp
        FontSize.M -> 16.sp
        FontSize.L -> 20.sp
    }
    val transSize = (sourceSize.value * 0.82f).sp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                ) { change, dragAmount ->
                    change.consume()
                    // Only Y axis — ignore horizontal movement
                    onDrag(dragAmount.y)
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showSource && sourceCue != null) {
            SubtitleLine(text = sourceCue.text, fontSize = sourceSize, isSource = true)
        }
        if (showTrans && transCue != null) {
            SubtitleLine(text = transCue.text, fontSize = transSize, isSource = false)
        }
    }
}

@Composable
private fun SubtitleLine(text: String, fontSize: TextUnit, isSource: Boolean) {
    Text(
        text       = text,
        fontSize   = fontSize,
        color      = if (isSource) Color.White else Color(0xFFFFD54F),
        fontWeight = if (isSource) FontWeight.SemiBold else FontWeight.Normal,
        textAlign  = TextAlign.Center,
        style      = LocalTextStyle.current.copy(
            shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 6f),
        ),
        modifier   = Modifier
            .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
