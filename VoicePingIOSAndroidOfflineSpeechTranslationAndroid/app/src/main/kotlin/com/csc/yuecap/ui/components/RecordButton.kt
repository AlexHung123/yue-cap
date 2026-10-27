package com.csc.yuecap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.csc.yuecap.ui.theme.YueCapColors

@Composable
fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fill by animateColorAsState(
        targetValue = if (isRecording) YueCapColors.Danger else YueCapColors.IconChrome,
        label = "recordFill"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isRecording) YueCapColors.Paper else YueCapColors.Paper,
        label = "recordIcon"
    )
    val border = if (isRecording) {
        BorderStroke(1.5.dp, Color.White.copy(alpha = 0.22f))
    } else {
        BorderStroke(1.5.dp, YueCapColors.Glow.copy(alpha = 0.55f))
    }
    val desc = if (isRecording) "Stop recording" else "Start recording"
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(76.dp)
            .semantics { contentDescription = desc },
        shape = CircleShape,
        color = fill,
        border = border
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
