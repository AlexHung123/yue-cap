package com.csc.yuecap.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csc.yuecap.ui.theme.YueCapColors
import com.csc.yuecap.data.TranscriptionEntity
import com.csc.yuecap.ui.components.AppVersionLabel
import com.csc.yuecap.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onRecordClick: (String) -> Unit
) {
    val records by viewModel.records.collectAsState()

    Scaffold(
        containerColor = YueCapColors.Ink,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Archive", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Kept captions",
                            style = MaterialTheme.typography.labelSmall,
                            color = YueCapColors.Gold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = YueCapColors.Ink,
                    titleContentColor = YueCapColors.Paper
                )
            )
        }
    ) { paddingValues ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Nothing saved",
                        style = MaterialTheme.typography.titleMedium,
                        color = YueCapColors.Paper
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Save a live caption and it will land here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = YueCapColors.Mist
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryItem(
                        record = record,
                        onClick = { onRecordClick(record.id) },
                        onDelete = { viewModel.deleteRecord(record) }
                    )
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AppVersionLabel()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    record: TranscriptionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = YueCapColors.Panel
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormat.format(Date(record.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = YueCapColors.Gold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = record.text.take(120),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = YueCapColors.Paper
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.audioFileName != null) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Has audio",
                            modifier = Modifier.size(14.dp),
                            tint = YueCapColors.Gold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = FormatUtils.formatDuration(record.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = YueCapColors.Mist
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = YueCapColors.Mist
                )
            }
        }
    }
}
