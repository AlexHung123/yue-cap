package com.csc.yuecap.ui.history

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.csc.yuecap.data.TranscriptionEntity
import com.csc.yuecap.service.AudioPlaybackManager
import com.csc.yuecap.ui.components.AppVersionLabel
import com.csc.yuecap.ui.theme.YueCapColors
import com.csc.yuecap.ui.components.WaveformScrubber
import com.csc.yuecap.util.FormatUtils
import com.csc.yuecap.util.SessionExporter
import com.csc.yuecap.util.WaveformGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    record: TranscriptionEntity,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

    // Resolve audio file
    val audioFile = remember(record.audioFileName) {
        record.audioFileName?.let { relPath ->
            val f = File(context.filesDir, relPath)
            if (f.exists()) f else null
        }
    }

    // Audio playback manager
    val playbackManager = remember { AudioPlaybackManager() }
    DisposableEffect(audioFile) {
        audioFile?.let { playbackManager.load(it) }
        onDispose { playbackManager.release() }
    }

    val isPlaying by playbackManager.isPlaying.collectAsState()
    val currentPositionMs by playbackManager.currentPositionMs.collectAsState()
    val durationMs by playbackManager.durationMs.collectAsState()

    // Waveform bars (computed once on IO)
    var waveformBars by remember { mutableStateOf(FloatArray(200)) }
    LaunchedEffect(audioFile) {
        audioFile?.let { file ->
            waveformBars = withContext(Dispatchers.IO) {
                WaveformGenerator.generateFromWavFile(file)
            }
        }
    }

    val progress = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f

    // Export error state
    var showExportError by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = YueCapColors.Ink,
        topBar = {
            TopAppBar(
                title = { Text("Caption", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = YueCapColors.Ink,
                    titleContentColor = YueCapColors.Paper,
                    actionIconContentColor = YueCapColors.Gold,
                    navigationIconContentColor = YueCapColors.Paper
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // ZIP export button (only when audio exists)
                    if (audioFile != null) {
                        IconButton(onClick = {
                            try {
                                val result = SessionExporter.export(context, record, audioFile)
                                context.startActivity(result.shareIntent)
                            } catch (e: Exception) {
                                showExportError = true
                            }
                        }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Export ZIP")
                        }
                    }
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(record.text))
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, record.text)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share caption"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateFormat.format(Date(record.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = FormatUtils.formatDuration(record.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = YueCapColors.Glass,
                border = BorderStroke(1.dp, YueCapColors.Line)
            ) {
                Text(
                    text = record.modelUsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = YueCapColors.Gold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // Waveform player (only if audio exists)
            if (audioFile != null) {
                Spacer(modifier = Modifier.height(16.dp))

                WaveformScrubber(
                    bars = waveformBars,
                    progress = progress,
                    isPlaying = isPlaying,
                    currentTimeMs = currentPositionMs,
                    durationMs = durationMs,
                    onSeek = { fraction -> playbackManager.seekTo(fraction) },
                    onTogglePlayPause = { playbackManager.togglePlayPause() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = YueCapColors.Glass,
                border = BorderStroke(1.dp, YueCapColors.Line)
            ) {
                SelectionContainer {
                    Text(
                        text = record.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = YueCapColors.Paper,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            AppVersionLabel(
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showExportError) {
        AlertDialog(
            onDismissRequest = { showExportError = false },
            title = { Text("Export failed") },
            text = { Text("Could not export this caption.") },
            confirmButton = {
                TextButton(onClick = { showExportError = false }) {
                    Text("OK")
                }
            }
        )
    }
}
