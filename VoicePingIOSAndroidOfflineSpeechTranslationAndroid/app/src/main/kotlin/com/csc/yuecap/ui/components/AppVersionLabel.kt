package com.csc.yuecap.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.csc.yuecap.BuildConfig

@Composable
fun AppVersionLabel(modifier: Modifier = Modifier) {
    Text(
        text = "YueCap ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = modifier
    )
}
