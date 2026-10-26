package com.csc.yuecap.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.csc.yuecap.ui.theme.YueCapColors
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.csc.yuecap.data.AppDatabase
import com.csc.yuecap.data.TranscriptionEntity
import com.csc.yuecap.service.WhisperEngine
import com.csc.yuecap.ui.history.HistoryDetailScreen
import com.csc.yuecap.ui.history.HistoryScreen
import com.csc.yuecap.ui.history.HistoryViewModel
import com.csc.yuecap.ui.transcription.TranscriptionScreen
import com.csc.yuecap.ui.transcription.TranscriptionViewModel
import androidx.compose.ui.platform.LocalContext

object Routes {
    const val MAIN = "main"
    const val TRANSCRIBE = "transcribe"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{recordId}"
}

@Composable
fun AppNavigation(
    engine: WhisperEngine,
    database: AppDatabase
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainTabScreen(
                engine = engine,
                database = database
            )
        }

        composable(
            Routes.HISTORY_DETAIL,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId") ?: return@composable
            var record by remember { mutableStateOf<TranscriptionEntity?>(null) }
            LaunchedEffect(recordId) {
                record = database.transcriptionDao().getById(recordId)
            }
            record?.let {
                HistoryDetailScreen(record = it, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
fun MainTabScreen(
    engine: WhisperEngine,
    database: AppDatabase
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Live" to Icons.Filled.Mic, "Archive" to Icons.Filled.History)

    // Shared navigation for history detail
    val navController = rememberNavController()

    Scaffold(
        containerColor = YueCapColors.Ink,
        bottomBar = {
            Surface(color = YueCapColors.InkRaised) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    tabs.forEachIndexed { index, (title, icon) ->
                        val selected = selectedTab == index
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { selectedTab = index }
                                .padding(horizontal = 22.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                icon,
                                contentDescription = title,
                                tint = if (selected) YueCapColors.Gold else YueCapColors.Mist,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                title,
                                color = if (selected) YueCapColors.Paper else YueCapColors.Mist,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        val context = LocalContext.current
        when (selectedTab) {
            0 -> {
                val viewModel = remember { TranscriptionViewModel(engine, database, context.filesDir) }
                Box(modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding())) {
                    TranscriptionScreen(viewModel = viewModel)
                }
            }
            1 -> {
                val viewModel = remember { HistoryViewModel(database, context.filesDir) }
                NavHost(
                    navController = navController,
                    startDestination = "history_list",
                    modifier = Modifier.padding(paddingValues)
                ) {
                    composable("history_list") {
                        HistoryScreen(
                            viewModel = viewModel,
                            onRecordClick = { id ->
                                navController.navigate("history_detail/$id")
                            }
                        )
                    }
                    composable(
                        "history_detail/{recordId}",
                        arguments = listOf(navArgument("recordId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val recordId = backStackEntry.arguments?.getString("recordId") ?: return@composable
                        var record by remember { mutableStateOf<TranscriptionEntity?>(null) }
                        LaunchedEffect(recordId) {
                            record = database.transcriptionDao().getById(recordId)
                        }
                        record?.let {
                            HistoryDetailScreen(record = it, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
