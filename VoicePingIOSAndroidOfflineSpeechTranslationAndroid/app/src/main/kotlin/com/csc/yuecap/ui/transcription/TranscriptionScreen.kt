package com.csc.yuecap.ui.transcription

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.csc.yuecap.service.MediaProjectionService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import com.csc.yuecap.service.ConnectionTestResult
import com.csc.yuecap.data.AppPreferences
import com.csc.yuecap.BuildConfig
import com.csc.yuecap.model.AudioInputMode
import com.csc.yuecap.model.ModelInfo
import com.csc.yuecap.model.ModelState
import com.csc.yuecap.model.TranslationProvider
import com.csc.yuecap.service.E2ETestResult
import com.csc.yuecap.ui.components.AppVersionLabel
import com.csc.yuecap.ui.components.AudioVisualizer
import com.csc.yuecap.ui.components.RecordButton
import com.csc.yuecap.ui.theme.GlassIconButton
import com.csc.yuecap.ui.theme.GlassPanel
import com.csc.yuecap.ui.theme.YueCapColors
import androidx.compose.foundation.BorderStroke
import com.csc.yuecap.util.FormatUtils
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionScreen(viewModel: TranscriptionViewModel) {
    val context = LocalContext.current
    var pendingPermissionStart by remember { mutableStateOf(false) }
    val projectionManager = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        } else {
            null
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.setSystemAudioCapturePermission(result.resultCode, result.data)
        if (pendingPermissionStart &&
            result.resultCode == Activity.RESULT_OK &&
            result.data != null
        ) {
            pendingPermissionStart = false
            viewModel.startRecordingWithPreparation()
        } else if (pendingPermissionStart) {
            pendingPermissionStart = false
        }
    }

    // Runtime mic permission request
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingPermissionStart) {
            if (viewModel.audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK &&
                !viewModel.systemAudioCaptureReady.value
            ) {
                projectionManager?.let { manager ->
                    // Android 14+ requires foreground service before getMediaProjection()
                    context.startForegroundService(
                        Intent(context, MediaProjectionService::class.java)
                    )
                    mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                } ?: run {
                    pendingPermissionStart = false
                }
            } else {
                pendingPermissionStart = false
                viewModel.startRecordingWithPreparation()
            }
        } else {
            pendingPermissionStart = false
        }
    }

    fun requestSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projectionManager == null) {
            viewModel.setSystemAudioCapturePermission(Activity.RESULT_CANCELED, null)
            return
        }
        // Android 14+ requires foreground service before getMediaProjection()
        context.startForegroundService(
            Intent(context, MediaProjectionService::class.java)
        )
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun onRecordClick() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val wantsSystemCapture = viewModel.audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK

        if (viewModel.isRecording.value) {
            pendingPermissionStart = false
            viewModel.toggleRecording()
        } else if (wantsSystemCapture && !viewModel.systemAudioCaptureReady.value) {
            if (hasPermission) {
                pendingPermissionStart = true
                requestSystemAudioCapture()
            } else {
                pendingPermissionStart = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else if (hasPermission) {
            pendingPermissionStart = false
            viewModel.startRecordingWithPreparation()
        } else {
            pendingPermissionStart = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val isRecording by viewModel.isRecording.collectAsState()
    val confirmedText by viewModel.confirmedText.collectAsState()
    val hypothesisText by viewModel.hypothesisText.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val showSaveConfirmation by viewModel.showSaveConfirmation.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val useVAD by viewModel.useVAD.collectAsState()
    val enableTimestamps by viewModel.enableTimestamps.collectAsState()
    val audioInputMode by viewModel.audioInputMode.collectAsState()
    val systemAudioCaptureReady by viewModel.systemAudioCaptureReady.collectAsState()
    val translationEnabled by viewModel.translationEnabled.collectAsState()
    val cantonesePolishEnabled by viewModel.cantonesePolishEnabled.collectAsState()
    val speakTranslatedAudio by viewModel.speakTranslatedAudio.collectAsState()
    val translationSourceLanguageCode by viewModel.translationSourceLanguageCode.collectAsState()
    val translationTargetLanguageCode by viewModel.translationTargetLanguageCode.collectAsState()
    val ttsRate by viewModel.ttsRate.collectAsState()
    val captionFontSizeSp by viewModel.captionFontSizeSp.collectAsState()
    val translationProviderState by viewModel.translationProvider.collectAsState()
    val translatedConfirmedText by viewModel.translatedConfirmedText.collectAsState()
    val translatedHypothesisText by viewModel.translatedHypothesisText.collectAsState()
    val translationWarning by viewModel.translationWarning.collectAsState()
    val translationModelReady by viewModel.translationModelReady.collectAsState()
    val translationDownloadStatus by viewModel.translationDownloadStatus.collectAsState()
    val asrBaseUrl by viewModel.asrBaseUrl.collectAsState()
    val llmBaseUrl by viewModel.llmBaseUrl.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val sparkATest by viewModel.sparkATest.collectAsState()
    val sparkATesting by viewModel.sparkATesting.collectAsState()
    val sparkBTest by viewModel.sparkBTest.collectAsState()
    val sparkBTesting by viewModel.sparkBTesting.collectAsState()
    val displayConfirmedText = remember(confirmedText) { confirmedText.trim() }
    val displayHypothesisText = remember(hypothesisText) { hypothesisText.trim() }
    val displayTranslatedConfirmedText = remember(translatedConfirmedText) { translatedConfirmedText.trim() }
    val displayTranslatedHypothesisText = remember(translatedHypothesisText) { translatedHypothesisText.trim() }
    val displayTranslationWarning = remember(translationWarning) { translationWarning?.trim().orEmpty() }

    var showSettings by remember { mutableStateOf(false) }
    var showCaptionPrefs by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var showCantonese by remember { mutableStateOf(false) }

    // Elapsed recording timer — based on wall clock, not accumulated delays
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                kotlinx.coroutines.delay(1000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    // Auto-scroll when either realtime hypothesis or confirmed text grows.
    LaunchedEffect(
        displayTranslatedConfirmedText,
        displayTranslatedHypothesisText,
        displayConfirmedText,
        isRecording
    ) {
        if (isRecording || displayTranslatedConfirmedText.isNotEmpty() || displayConfirmedText.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // Pre-initialize mic recorder setup on screen entry to avoid first-utterance clipping.
    LaunchedEffect(Unit) {
        viewModel.prewarmOnScreenOpen()
    }

    Scaffold(
        containerColor = YueCapColors.Ink,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("YueCap", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Cantonese to English",
                            style = MaterialTheme.typography.labelSmall,
                            color = YueCapColors.Mist
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TranscriptionTextDisplay(
                modifier = Modifier.weight(1f),
                scrollState = scrollState,
                confirmedText = displayConfirmedText,
                hypothesisText = displayHypothesisText,
                translationEnabled = translationEnabled,
                translatedConfirmedText = displayTranslatedConfirmedText,
                translatedHypothesisText = displayTranslatedHypothesisText,
                translationWarning = displayTranslationWarning,
                isRecording = isRecording,
                errorMessage = lastError?.message,
                captionFontSizeSp = captionFontSizeSp,
                onOpenCaptionPrefs = { showCaptionPrefs = true },
                showCantonese = showCantonese,
                onToggleCantonese = { showCantonese = !showCantonese }
            )

            // Live recording meters stay on the home screen; Setup controls live in Settings.
            if (isRecording) {
                RecordingStatsBar(viewModel)
                ResourceStatsBar(viewModel, isRecording, elapsedSeconds)
            }
            if (BuildConfig.DEBUG) {
                val e2eResult by viewModel.e2eResult.collectAsState()
                e2eResult?.let { result ->
                    E2EEvidenceOverlay(result)
                }
            }

            ControlButtonsRow(
                isRecording = isRecording,
                canSave = !isRecording && viewModel.fullText.isNotBlank(),
                onSave = { viewModel.saveTranscription() },
                onRecord = { onRecordClick() },
                onSettings = { showSettings = true }
            )
        }
    }

    // Save confirmation
    if (showSaveConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveConfirmation() },
            title = { Text("Saved") },
            text = { Text("Saved to Archive.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSaveConfirmation() }) {
                    Text("OK")
                }
            },
            containerColor = YueCapColors.GlassStrong,
            titleContentColor = YueCapColors.Paper,
            textContentColor = YueCapColors.Mist
        )
    }

    // Error dialog - dismiss without clearing transcription text
    lastError?.let { error ->
        val isTranslationError =
            error is com.csc.yuecap.model.AppError.TranslationUnavailable ||
                error is com.csc.yuecap.model.AppError.TranslationFailed
        if (isTranslationError) {
            LaunchedEffect(error) {
                viewModel.dismissError()
            }
            return@let
        }
        val isPermissionError = error is com.csc.yuecap.model.AppError.MicrophonePermissionDenied
        val isSystemCapturePermissionError =
            error is com.csc.yuecap.model.AppError.SystemAudioCapturePermissionDenied
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = {
                Text(
                    if (isPermissionError || isSystemCapturePermissionError) {
                        "Permission Required"
                    } else {
                        "Warning"
                    }
                )
            },
            text = { Text(error.message) },
            confirmButton = {
                if (isPermissionError) {
                    TextButton(onClick = {
                        viewModel.dismissError()
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Text("Grant Permission")
                    }
                } else if (isSystemCapturePermissionError) {
                    TextButton(onClick = {
                        viewModel.dismissError()
                        requestSystemAudioCapture()
                    }) {
                        Text("Enable System Capture")
                    }
                } else {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (isPermissionError || isSystemCapturePermissionError) {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("Cancel")
                    }
                }
            },
            containerColor = YueCapColors.GlassStrong,
            titleContentColor = YueCapColors.Paper,
            textContentColor = YueCapColors.Mist
        )
    }

    // Settings bottom sheet
    if (showCaptionPrefs) {
        CaptionPrefsBottomSheet(
            selectedSizeSp = captionFontSizeSp,
            onSizeSelected = { viewModel.setCaptionFontSizeSp(it) },
            onDismiss = { showCaptionPrefs = false }
        )
    }

        if (showSettings) {
        SettingsBottomSheet(
            selectedModel = selectedModel,
            useVAD = useVAD,
            enableTimestamps = enableTimestamps,
            fullText = viewModel.fullText,
            asrBaseUrl = asrBaseUrl,
            llmBaseUrl = llmBaseUrl,
            llmModel = llmModel,
            llmApiKey = llmApiKey,
            sparkATesting = sparkATesting,
            sparkATest = sparkATest,
            sparkBTesting = sparkBTesting,
            sparkBTest = sparkBTest,
            audioInputMode = audioInputMode,
            systemCaptureReady = systemAudioCaptureReady,
            systemCaptureSupported = viewModel.isSystemAudioCaptureSupported,
            onInputModeChange = { viewModel.setAudioInputMode(it) },
            onRequestSystemCapture = { requestSystemAudioCapture() },
            translationEnabled = translationEnabled,
            cantonesePolishEnabled = cantonesePolishEnabled,
            speakTranslatedAudio = speakTranslatedAudio,
            translationSourceLanguageCode = translationSourceLanguageCode,
            translationTargetLanguageCode = translationTargetLanguageCode,
            ttsRate = ttsRate,
            translationProvider = translationProviderState,
            isAndroidSystemTranslationAvailable = viewModel.isAndroidSystemTranslationAvailable,
            translationModelReady = translationModelReady,
            translationDownloadStatus = translationDownloadStatus,
            translatedConfirmedText = displayTranslatedConfirmedText,
            translatedHypothesisText = displayTranslatedHypothesisText,
            translationWarning = displayTranslationWarning,
            onTranslationEnabledChange = { viewModel.setTranslationEnabled(it) },
            onCantonesePolishEnabledChange = { viewModel.setCantonesePolishEnabled(it) },
            onSpeakTranslatedAudioChange = { viewModel.setSpeakTranslatedAudio(it) },
            onSourceLanguageChange = { viewModel.setTranslationSourceLanguageCode(it) },
            onTargetLanguageChange = { viewModel.setTranslationTargetLanguageCode(it) },
            onTtsRateChange = { viewModel.setTtsRate(it) },
            onTranslationProviderChange = { viewModel.setTranslationProvider(it) },
            onCopyText = { clipboardManager.setText(AnnotatedString(viewModel.fullText)) },
            onClearTranscription = { viewModel.clearTranscription() },
            onVADChange = { viewModel.setUseVAD(it) },
            onTimestampsChange = { viewModel.setEnableTimestamps(it) },
            onSaveServers = { asrUrl, llmUrl, model, apiKey ->
                viewModel.saveServerEndpoints(asrUrl, llmUrl, model, apiKey)
            },
            onTestSparkA = { viewModel.testSparkA(it) },
            onTestSparkB = { url, model, apiKey -> viewModel.testSparkB(url, model, apiKey) },
            onDismiss = { showSettings = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionTextDisplay(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    confirmedText: String,
    hypothesisText: String,
    translationEnabled: Boolean,
    translatedConfirmedText: String,
    translatedHypothesisText: String,
    translationWarning: String,
    isRecording: Boolean,
    errorMessage: String?,
    captionFontSizeSp: Int,
    onOpenCaptionPrefs: () -> Unit,
    showCantonese: Boolean,
    onToggleCantonese: () -> Unit
) {
    val status = captionStatus(
        isRecording = isRecording,
        hypothesisText = hypothesisText,
        confirmedText = confirmedText,
        translatedConfirmedText = translatedConfirmedText,
        translationEnabled = translationEnabled,
        errorMessage = errorMessage,
        translationWarning = translationWarning
    )
    // Prefer English (translated) as the main karaoke caption; fall back to source text.
    val useTranslatedPrimary =
        translationEnabled &&
            (translatedConfirmedText.isNotEmpty() || translatedHypothesisText.isNotEmpty())
    val (pastText, currentText) = if (useTranslatedPrimary) {
        splitPastAndCurrent(translatedConfirmedText, translatedHypothesisText)
    } else {
        splitPastAndCurrent(confirmedText, hypothesisText)
    }
    val hasAnyCaption = pastText.isNotEmpty() || currentText.isNotEmpty()
    val emptyHint = if (translationEnabled) {
        "English captions appear here"
    } else {
        "Captions appear here"
    }

    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        corner = 28.dp,
        glow = true,
        strong = true
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        CaptionStatusBar(
            status = status,
            detail = when (status) {
                CaptionStatus.Warning -> errorMessage?.takeIf { it.isNotBlank() } ?: translationWarning
                else -> null
            },
            onOpenCaptionPrefs = onOpenCaptionPrefs
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!hasAnyCaption) {
                Text(
                    text = emptyHint,
                    color = YueCapColors.CaptionPast,
                    fontSize = captionFontSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (captionFontSizeSp + 8).sp,
                    modifier = Modifier.semantics { contentDescription = "Caption placeholder" }
                )
            } else {
                KaraokeCaptionBlock(
                    pastText = pastText,
                    currentText = currentText,
                    sizeSp = captionFontSizeSp,
                    contentDescription = if (useTranslatedPrimary) "English caption" else "Source caption",
                    // Typewriter is mainly for English reading comfort; source-only uses it too
                    // so Live mic captions get the same past-gray / current-bright feel.
                    typewriter = true,
                    scrollState = scrollState
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                onClick = onToggleCantonese,
                shape = RoundedCornerShape(20.dp),
                color = YueCapColors.IconChrome,
                border = BorderStroke(1.dp, YueCapColors.Line)
            ) {
                Text(
                    text = if (showCantonese) "Hide Cantonese" else "Cantonese",
                    color = YueCapColors.Paper,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (showCantonese) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val (cantonesePast, cantoneseCurrent) =
                    splitPastAndCurrent(confirmedText, hypothesisText)
                // Secondary Cantonese line stays a step smaller than main captions,
                // but past and current still share one size.
                val cantoneseSize = (captionFontSizeSp - 6).coerceAtLeast(14)
                KaraokeCaptionBlock(
                    pastText = cantonesePast,
                    currentText = cantoneseCurrent,
                    sizeSp = cantoneseSize,
                    emptyFallback = "No Cantonese yet.",
                    contentDescription = "Cantonese caption",
                    typewriter = false
                )
            }
        }
        }
    }
}

/**
 * Progressive caption: already-confirmed / read text is dim; the live hypothesis
 * (or currently arriving segment) is bright — karaoke / floating-caption style.
 * Past and current share the same font size; contrast is color (and slight weight).
 * When [typewriter] is true, only the newest segment reveals character-by-character;
 * history stays put. Mid-type updates cancel and restart (or continue on a prefix extension).
 */
@Composable
private fun KaraokeCaptionBlock(
    pastText: String,
    currentText: String,
    sizeSp: Int = AppPreferences.DEFAULT_CAPTION_FONT_SIZE_SP,
    emptyFallback: String? = null,
    contentDescription: String,
    typewriter: Boolean = false,
    scrollState: ScrollState? = null
) {
    val past = pastText.trim()
    val current = currentText.trim()
    val a11yLabel = contentDescription
    val lineHeightSp = sizeSp + 10
    var displayedCurrent by remember { mutableStateOf("") }

    LaunchedEffect(current, typewriter) {
        if (!typewriter) {
            displayedCurrent = current
            return@LaunchedEffect
        }
        if (current.isEmpty()) {
            displayedCurrent = ""
            return@LaunchedEffect
        }
        // Continue typing if the target grew with the same prefix; otherwise retype cleanly.
        val startIndex = when {
            current.startsWith(displayedCurrent) -> displayedCurrent.length
            else -> {
                val overlap = displayedCurrent.commonPrefixWith(current).length
                // Keep a useful overlap only when most of what was shown still matches
                // (polish/replace of the same segment); otherwise start over to avoid flicker spam.
                if (overlap >= 8 || (displayedCurrent.isNotEmpty() && overlap * 2 >= displayedCurrent.length)) {
                    overlap
                } else {
                    0
                }
            }
        }
        if (startIndex == 0) {
            displayedCurrent = ""
        } else if (startIndex < displayedCurrent.length) {
            displayedCurrent = current.take(startIndex)
        }
        if (startIndex >= current.length) {
            displayedCurrent = current
            return@LaunchedEffect
        }
        val delayMs = 1000L / TYPEWRITER_CHARS_PER_SECOND
        for (i in (startIndex + 1)..current.length) {
            displayedCurrent = current.take(i)
            scrollState?.scrollTo(scrollState.maxValue)
            delay(delayMs)
        }
    }

    // Keep the typing end visible even when scrollState is null (parent scroll keys).
    LaunchedEffect(displayedCurrent, past) {
        scrollState?.scrollTo(scrollState.maxValue)
    }

    if (past.isEmpty() && current.isEmpty()) {
        if (emptyFallback != null) {
            Text(
                text = emptyFallback,
                color = YueCapColors.CaptionPast,
                fontSize = sizeSp.sp,
                lineHeight = (sizeSp + 8).sp,
                modifier = Modifier.semantics { this.contentDescription = a11yLabel }
            )
        }
        return
    }
    val shownCurrent = if (typewriter) displayedCurrent else current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { this.contentDescription = a11yLabel }
    ) {
        if (past.isNotEmpty()) {
            Text(
                text = past,
                color = YueCapColors.CaptionPast,
                fontSize = sizeSp.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = lineHeightSp.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (shownCurrent.isNotEmpty()) {
            if (past.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
            }
            Text(
                text = shownCurrent,
                color = YueCapColors.CaptionCurrent,
                fontSize = sizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = lineHeightSp.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Reveal speed for the live English caption segment (~40 chars/sec). */
private const val TYPEWRITER_CHARS_PER_SECOND = 40

private val captionFontSizeLabels = mapOf(
    16 to "Extra Small",
    18 to "Small",
    22 to "Medium",
    28 to "Large",
    34 to "Extra Large"
)

/** Oppo-style discrete slider: blue capsule track, small white stop dots, large white circle thumb. */
@Composable
private fun DiscreteStopsSlider(
    selectedIndex: Int,
    stopCount: Int,
    onIndexSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = "Discrete slider"
) {
    val trackBlue = if (enabled) Color(0xFF2F6BFF) else Color(0xFF2F6BFF).copy(alpha = 0.35f)
    val markColor = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
    val density = LocalDensity.current
    fun indexFromX(x: Float, width: Float): Int {
        if (width <= 0f || stopCount <= 1) return 0
        val fraction = (x / width).coerceIn(0f, 1f)
        return (fraction * (stopCount - 1)).roundToInt().coerceIn(0, stopCount - 1)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics { this.contentDescription = contentDescription }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(stopCount) {
                            detectTapGestures { offset ->
                                onIndexSelected(indexFromX(offset.x, size.width.toFloat()))
                            }
                        }
                        .pointerInput(stopCount) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                onIndexSelected(indexFromX(change.position.x, size.width.toFloat()))
                            }
                        }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val trackHeightPx = with(density) { 8.dp.toPx() }
        val stopDotRadiusPx = with(density) { 3.dp.toPx() }
        val thumbRadiusPx = with(density) { 11.dp.toPx() }
        val horizontalInset = thumbRadiusPx
        val usable = (widthPx - horizontalInset * 2f).coerceAtLeast(1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackTop = (heightPx - trackHeightPx) / 2f
            drawRoundRect(
                color = trackBlue,
                topLeft = Offset(horizontalInset, trackTop),
                size = Size(usable, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
            )
            for (i in 0 until stopCount) {
                val cx = horizontalInset + usable * (i.toFloat() / (stopCount - 1).toFloat())
                val cy = heightPx / 2f
                if (i == selectedIndex) {
                    drawCircle(
                        color = markColor,
                        radius = thumbRadiusPx,
                        center = Offset(cx, cy)
                    )
                } else {
                    drawCircle(
                        color = markColor,
                        radius = stopDotRadiusPx,
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptionPrefsBottomSheet(
    selectedSizeSp: Int,
    onSizeSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = AppPreferences.CAPTION_FONT_SIZE_OPTIONS
    val selectedIndex = options.indexOf(selectedSizeSp).let {
        if (it >= 0) it else options.indexOf(AppPreferences.DEFAULT_CAPTION_FONT_SIZE_SP)
    }.coerceIn(0, options.lastIndex)
    val previewSizeSp = options[selectedIndex]
    val sizeLabel = captionFontSizeLabels[previewSizeSp] ?: previewSizeSp.toString()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = YueCapColors.GlassStrong,
        contentColor = YueCapColors.Paper,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Caption Preferences",
                style = MaterialTheme.typography.titleMedium,
                color = YueCapColors.Paper,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 18.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(YueCapColors.CaptionCard)
                    .border(BorderStroke(1.dp, YueCapColors.Line), RoundedCornerShape(18.dp))
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Text(
                        text = "Caption appearance example",
                        color = YueCapColors.CaptionCurrent,
                        fontSize = previewSizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Font size",
                    color = YueCapColors.Paper,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = sizeLabel,
                    color = YueCapColors.Mist,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            DiscreteStopsSlider(
                contentDescription = "Caption font size slider",

                selectedIndex = selectedIndex,
                stopCount = options.size,
                onIndexSelected = { idx -> onSizeSelected(options[idx]) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


/**
 * Map ASR confirmed + hypothesis into karaoke past/current.
 * With a live hypothesis, confirmed is past and hypothesis is current.
 * With only confirmed text, the last sentence/paragraph stays bright as the focus;
 * older sentences render gray. English is usually space-joined (normalizeText collapses
 * newlines), so we also split on sentence-ending punctuation.
 */
private fun splitPastAndCurrent(confirmed: String, hypothesis: String): Pair<String, String> {
    val hyp = hypothesis.trim()
    val conf = confirmed.trim()
    if (hyp.isNotEmpty()) {
        return conf to hyp
    }
    if (conf.isEmpty()) {
        return "" to ""
    }
    val parts = splitCaptionSegments(conf)
    return if (parts.size <= 1) {
        "" to conf
    } else {
        // Keep past sentences on separate lines so gray history is easy to scan.
        parts.dropLast(1).joinToString("\n") to parts.last()
    }
}

/** Split caption text into past-able segments: paragraphs first, else sentences. */
private fun splitCaptionSegments(text: String): List<String> {
    val byPara = text.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    if (byPara.size > 1) return byPara
    // English chunks are space-joined after .!?…; CJK often has no space after 。！？…
    val sentenceRegex = Regex("""(?<=[.!?…])\s+|(?<=[。！？…])""")
    val sentences = text.split(sentenceRegex).map { it.trim() }.filter { it.isNotEmpty() }
    return if (sentences.size > 1) sentences else listOf(text.trim()).filter { it.isNotEmpty() }
}

private enum class CaptionStatus(val label: String) {
    Ready("Ready"),
    Listening("Listening"),
    Recognizing("Recognizing"),
    Translating("Translating"),
    Warning("Warning")
}

private fun captionStatus(
    isRecording: Boolean,
    hypothesisText: String,
    confirmedText: String,
    translatedConfirmedText: String,
    translationEnabled: Boolean,
    errorMessage: String?,
    translationWarning: String
): CaptionStatus {
    if (!errorMessage.isNullOrBlank() || translationWarning.isNotBlank()) return CaptionStatus.Warning
    if (!isRecording) return CaptionStatus.Ready
    if (hypothesisText.isNotBlank()) return CaptionStatus.Recognizing
    if (translationEnabled && confirmedText.isNotBlank() && translatedConfirmedText.isBlank()) {
        return CaptionStatus.Translating
    }
    return CaptionStatus.Listening
}

@Composable
private fun CaptionStatusBar(
    status: CaptionStatus,
    detail: String?,
    onOpenCaptionPrefs: () -> Unit
) {
    val dot = when (status) {
        CaptionStatus.Warning -> YueCapColors.Gold
        CaptionStatus.Translating -> YueCapColors.Gold
        CaptionStatus.Recognizing -> YueCapColors.Paper
        CaptionStatus.Listening -> YueCapColors.Ok
        CaptionStatus.Ready -> YueCapColors.Mist
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
                Text(
                    text = status.label,
                    color = YueCapColors.Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            // Circular serif "TT" control — large T + smaller T (typography icon).
            Surface(
                onClick = onOpenCaptionPrefs,
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Caption preferences" },
                shape = CircleShape,
                color = YueCapColors.IconChrome,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "T",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 18.sp
                        )
                        Text(
                            text = "T",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 12.sp,
                            modifier = Modifier.padding(start = 1.dp, bottom = 1.dp)
                        )
                    }
                }
            }
        }
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                color = if (status == CaptionStatus.Warning) YueCapColors.Gold else YueCapColors.Mist,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp, start = 6.dp)
            )
        }
    }
}

@Composable
private fun ControlButtonsRow(
    isRecording: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(YueCapColors.Ink)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(
            onClick = onSave,
            imageVector = Icons.Filled.Save,
            contentDescription = "Save",
            enabled = canSave,
            tint = if (canSave) YueCapColors.Paper else YueCapColors.Mist
        )

        Spacer(modifier = Modifier.width(28.dp))

        RecordButton(
            isRecording = isRecording,
            onClick = onRecord
        )

        Spacer(modifier = Modifier.width(28.dp))

        GlassIconButton(
            onClick = onSettings,
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings",
            tint = YueCapColors.Paper
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    selectedModel: ModelInfo,
    useVAD: Boolean,
    enableTimestamps: Boolean,
    fullText: String,
    asrBaseUrl: String,
    llmBaseUrl: String,
    llmModel: String,
    llmApiKey: String,
    sparkATesting: Boolean,
    sparkATest: ConnectionTestResult?,
    sparkBTesting: Boolean,
    sparkBTest: ConnectionTestResult?,
    audioInputMode: AudioInputMode,
    systemCaptureReady: Boolean,
    systemCaptureSupported: Boolean,
    onInputModeChange: (AudioInputMode) -> Unit,
    onRequestSystemCapture: () -> Unit,
    translationEnabled: Boolean,
    cantonesePolishEnabled: Boolean,
    speakTranslatedAudio: Boolean,
    translationSourceLanguageCode: String,
    translationTargetLanguageCode: String,
    ttsRate: Float,
    translationProvider: TranslationProvider,
    isAndroidSystemTranslationAvailable: Boolean,
    translationModelReady: Boolean,
    translationDownloadStatus: String?,
    translatedConfirmedText: String,
    translatedHypothesisText: String,
    translationWarning: String,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onCantonesePolishEnabledChange: (Boolean) -> Unit,
    onSpeakTranslatedAudioChange: (Boolean) -> Unit,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onTtsRateChange: (Float) -> Unit,
    onTranslationProviderChange: (TranslationProvider) -> Unit,
    onCopyText: () -> Unit,
    onClearTranscription: () -> Unit,
    onVADChange: (Boolean) -> Unit,
    onTimestampsChange: (Boolean) -> Unit,
    onSaveServers: (String, String, String, String) -> Unit,
    onTestSparkA: (String) -> Unit,
    onTestSparkB: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var asrUrl by remember { mutableStateOf(asrBaseUrl) }
    var sparkBUrl by remember { mutableStateOf(llmBaseUrl) }
    var modelName by remember { mutableStateOf(llmModel) }
    var apiKey by remember { mutableStateOf(llmApiKey) }
    var asrEdited by remember { mutableStateOf(false) }
    var sparkBEdited by remember { mutableStateOf(false) }
    var modelEdited by remember { mutableStateOf(false) }
    var apiKeyEdited by remember { mutableStateOf(false) }
    LaunchedEffect(asrBaseUrl) { if (!asrEdited) asrUrl = asrBaseUrl }
    LaunchedEffect(llmBaseUrl) { if (!sparkBEdited) sparkBUrl = llmBaseUrl }
    LaunchedEffect(llmModel) { if (!modelEdited) modelName = llmModel }
    LaunchedEffect(llmApiKey) { if (!apiKeyEdited) apiKey = llmApiKey }
    val saveAndDismiss = {
        onSaveServers(asrUrl, sparkBUrl, modelName, apiKey)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = saveAndDismiss,
        sheetState = sheetState,
        containerColor = YueCapColors.GlassStrong,
        contentColor = YueCapColors.Paper,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = YueCapColors.Paper,
                fontWeight = FontWeight.SemiBold
            )

            SettingsSection(title = "Input") {
                AudioInputModeContent(
                    audioInputMode = audioInputMode,
                    systemCaptureReady = systemCaptureReady,
                    systemCaptureSupported = systemCaptureSupported,
                    onInputModeChange = onInputModeChange,
                    onRequestSystemCapture = onRequestSystemCapture
                )
            }

            SettingsSection(title = "Caption") {
                CaptionSettingsContent(
                    translationEnabled = translationEnabled,
                    cantonesePolishEnabled = cantonesePolishEnabled,
                    speakTranslatedAudio = speakTranslatedAudio,
                    translationSourceLanguageCode = translationSourceLanguageCode,
                    translationTargetLanguageCode = translationTargetLanguageCode,
                    ttsRate = ttsRate,
                    translationProvider = translationProvider,
                    isAndroidSystemTranslationAvailable = isAndroidSystemTranslationAvailable,
                    translationModelReady = translationModelReady,
                    translationDownloadStatus = translationDownloadStatus,
                    translatedConfirmedText = translatedConfirmedText,
                    translatedHypothesisText = translatedHypothesisText,
                    translationWarning = translationWarning,
                    onTranslationEnabledChange = onTranslationEnabledChange,
                    onCantonesePolishEnabledChange = onCantonesePolishEnabledChange,
                    onSpeakTranslatedAudioChange = onSpeakTranslatedAudioChange,
                    onSourceLanguageChange = onSourceLanguageChange,
                    onTargetLanguageChange = onTargetLanguageChange,
                    onTtsRateChange = onTtsRateChange,
                    onTranslationProviderChange = onTranslationProviderChange
                )
            }

            SettingsSection(title = "Spark A") {
                OutlinedTextField(
                    value = asrUrl,
                    onValueChange = {
                        asrEdited = true
                        asrUrl = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.1.20:8000") }
                )
                Button(
                    onClick = { onTestSparkA(asrUrl.trim()) },
                    enabled = !sparkATesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (sparkATesting) "Testing Spark A..." else "Test Spark A")
                }
                sparkATest?.let { result ->
                    Text(
                        text = result.message,
                        color = if (result.ok) YueCapColors.Ok else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SettingsSection(title = "Spark B") {
                OutlinedTextField(
                    value = sparkBUrl,
                    onValueChange = {
                        sparkBEdited = true
                        sparkBUrl = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.1.21:8000") }
                )
                OutlinedTextField(
                    value = modelName,
                    onValueChange = {
                        modelEdited = true
                        modelName = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model") },
                    placeholder = { Text("local") }
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKeyEdited = true
                        apiKey = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API key (optional)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = { onTestSparkB(sparkBUrl.trim(), modelName.trim(), apiKey.trim()) },
                    enabled = !sparkBTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (sparkBTesting) "Testing Spark B..." else "Test Spark B")
                }
                sparkBTest?.let { result ->
                    Text(
                        text = result.message,
                        color = if (result.ok) YueCapColors.Ok else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SettingsSection(title = "Transcript") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopyText,
                        enabled = fullText.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "settings_copy_text" }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                    OutlinedButton(
                        onClick = onClearTranscription,
                        enabled = fullText.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "settings_clear_transcription" }
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }

            SettingsSection(title = "On-device model") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedModel.displayName, color = YueCapColors.Paper)
                    Text(
                        selectedModel.parameterCount,
                        color = YueCapColors.Mist
                    )
                }
            }

            SettingsSection(title = "Listening") {
                SettingsSwitchRow(
                    label = "Skip silence",
                    checked = useVAD,
                    onCheckedChange = onVADChange
                )
                SettingsSwitchRow(
                    label = "Timestamps",
                    checked = enableTimestamps,
                    onCheckedChange = onTimestampsChange
                )
            }

            AppVersionLabel(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        corner = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = YueCapColors.Paper,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = label,
                color = if (enabled) YueCapColors.Paper else YueCapColors.Mist,
                style = MaterialTheme.typography.bodyLarge
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = YueCapColors.Mist,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                style = MaterialTheme.typography.labelLarge
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = YueCapColors.Panel,
            labelColor = YueCapColors.Paper,
            selectedContainerColor = YueCapColors.Paper.copy(alpha = 0.20f),
            selectedLabelColor = YueCapColors.Paper,
            disabledContainerColor = YueCapColors.Panel.copy(alpha = 0.45f),
            disabledLabelColor = YueCapColors.Mist.copy(alpha = 0.7f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = YueCapColors.Line,
            selectedBorderColor = Color.White.copy(alpha = 0.28f),
            disabledBorderColor = YueCapColors.Line.copy(alpha = 0.4f),
            disabledSelectedBorderColor = YueCapColors.Line.copy(alpha = 0.4f)
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioInputModeContent(
    audioInputMode: AudioInputMode,
    systemCaptureReady: Boolean,
    systemCaptureSupported: Boolean,
    onInputModeChange: (AudioInputMode) -> Unit,
    onRequestSystemCapture: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsFilterChip(
            selected = audioInputMode == AudioInputMode.MICROPHONE,
            onClick = { onInputModeChange(AudioInputMode.MICROPHONE) },
            label = "Microphone"
        )
        SettingsFilterChip(
            selected = audioInputMode == AudioInputMode.SYSTEM_PLAYBACK,
            onClick = { onInputModeChange(AudioInputMode.SYSTEM_PLAYBACK) },
            enabled = systemCaptureSupported,
            label = "System"
        )
    }

    if (audioInputMode == AudioInputMode.SYSTEM_PLAYBACK) {
        val statusText = when {
            !systemCaptureSupported -> "System capture requires Android 10+."
            systemCaptureReady -> "System capture enabled. Other apps' media/game audio can be transcribed."
            else -> "Enable system capture to transcribe playback audio from other apps."
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = YueCapColors.Mist
        )

        if (systemCaptureSupported && !systemCaptureReady) {
            OutlinedButton(
                onClick = onRequestSystemCapture,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable System Capture")
            }
        }
    }
}

private val readingSpeedStops = listOf(0.50f, 0.75f, 1.00f, 1.25f, 1.50f)
private val readingSpeedLabels = listOf("Slow", "Steady", "Normal", "Fast", "Faster")

private fun nearestReadingSpeedIndex(rate: Float): Int {
    return readingSpeedStops.indices.minByOrNull { i ->
        kotlin.math.abs(readingSpeedStops[i] - rate)
    } ?: 2
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionSettingsContent(
    translationEnabled: Boolean,
    cantonesePolishEnabled: Boolean,
    speakTranslatedAudio: Boolean,
    translationSourceLanguageCode: String,
    translationTargetLanguageCode: String,
    ttsRate: Float,
    translationProvider: TranslationProvider,
    isAndroidSystemTranslationAvailable: Boolean,
    translationModelReady: Boolean,
    translationDownloadStatus: String?,
    translatedConfirmedText: String,
    translatedHypothesisText: String,
    translationWarning: String,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onCantonesePolishEnabledChange: (Boolean) -> Unit,
    onSpeakTranslatedAudioChange: (Boolean) -> Unit,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onTtsRateChange: (Float) -> Unit,
    onTranslationProviderChange: (TranslationProvider) -> Unit
) {
    val ttsText = remember(translatedConfirmedText, translatedHypothesisText) {
        listOf(translatedConfirmedText.trim(), translatedHypothesisText.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
    val speedIndex = nearestReadingSpeedIndex(ttsRate)

    SettingsSwitchRow(
        label = "Translate",
        checked = translationEnabled,
        onCheckedChange = onTranslationEnabledChange
    )

    SettingsSwitchRow(
        label = "Light polish Cantonese",
        checked = cantonesePolishEnabled,
        onCheckedChange = onCantonesePolishEnabledChange,
        enabled = translationEnabled && translationProvider == TranslationProvider.REMOTE_SPARK_B,
        subtitle = "Uses Spark B LLM · fixes ASR only, keeps 口語"
    )

    SettingsSwitchRow(
        label = "Read aloud",
        checked = speakTranslatedAudio,
        onCheckedChange = onSpeakTranslatedAudioChange,
        enabled = translationEnabled
    )

    Text(
        text = "Translator",
        style = MaterialTheme.typography.bodyMedium,
        color = YueCapColors.Mist
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TranslationProvider.entries.forEach { provider ->
            val enabled = translationEnabled && (
                provider != TranslationProvider.ANDROID_SYSTEM || isAndroidSystemTranslationAvailable
            )
            val label = when (provider) {
                TranslationProvider.REMOTE_SPARK_B -> "Spark B"
                TranslationProvider.ML_KIT -> "ML Kit"
                TranslationProvider.ANDROID_SYSTEM -> "System"
            }
            SettingsFilterChip(
                selected = translationProvider == provider,
                onClick = { if (enabled) onTranslationProviderChange(provider) },
                enabled = enabled,
                label = label
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LanguageDropdownField(
                title = "From",
                selectedCode = translationSourceLanguageCode,
                enabled = translationEnabled,
                onLanguageSelected = onSourceLanguageChange
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            LanguageDropdownField(
                title = "Into",
                selectedCode = translationTargetLanguageCode,
                enabled = false,
                onLanguageSelected = onTargetLanguageChange
            )
        }
    }

    if (translationEnabled) {
        val statusText = translationDownloadStatus
        if (!translationModelReady && statusText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (statusText.startsWith("Downloading") || statusText.startsWith("Preparing")) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusText.contains("failed", ignoreCase = true))
                        MaterialTheme.colorScheme.error
                    else YueCapColors.Mist
                )
            }
        } else if (!translationModelReady) {
            Text(
                text = "Getting the translator ready...",
                style = MaterialTheme.typography.bodySmall,
                color = YueCapColors.Mist
            )
        } else {
            Text(
                text = "Translator ready",
                style = MaterialTheme.typography.bodySmall,
                color = YueCapColors.Ok
            )
        }
    }

    Text(
        text = "Reading speed: ${readingSpeedLabels[speedIndex]} (${String.format("%.2f", readingSpeedStops[speedIndex])}×)",
        style = MaterialTheme.typography.bodySmall,
        color = YueCapColors.Mist
    )
    DiscreteStopsSlider(
        selectedIndex = speedIndex,
        stopCount = readingSpeedStops.size,
        onIndexSelected = { idx -> onTtsRateChange(readingSpeedStops[idx]) },
        enabled = translationEnabled && speakTranslatedAudio,
        contentDescription = "Reading speed slider",
        modifier = Modifier.fillMaxWidth()
    )

    Text(
        text = "Spoken English",
        style = MaterialTheme.typography.labelMedium,
        color = YueCapColors.Mist
    )
    Text(
        text = if (ttsText.isBlank()) "Nothing to read yet." else ttsText,
        style = MaterialTheme.typography.bodySmall,
        color = if (ttsText.isBlank()) {
            YueCapColors.Mist.copy(alpha = 0.7f)
        } else {
            YueCapColors.Paper
        },
        maxLines = 3
    )
    if (translationWarning.isNotEmpty()) {
        Text(
            text = translationWarning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

private data class TranslationLanguageOption(
    val code: String,
    val name: String
) {
    val displayName: String
        get() = "$name ($code)"
}

private val TRANSLATION_LANGUAGE_OPTIONS = listOf(
    TranslationLanguageOption("en", "English"),
    TranslationLanguageOption("zh", "Chinese"),
    TranslationLanguageOption("ja", "Japanese"),
    TranslationLanguageOption("ko", "Korean"),
    TranslationLanguageOption("yue", "Cantonese")
)

private fun translationLanguageDisplayName(code: String): String {
    val normalized = code.trim().lowercase()
    val option = TRANSLATION_LANGUAGE_OPTIONS.firstOrNull {
        it.code.equals(normalized, ignoreCase = true)
    }
    return option?.displayName ?: normalized.ifEmpty { "Select language" }
}

@Composable
private fun LanguageDropdownField(
    title: String,
    selectedCode: String,
    enabled: Boolean,
    onLanguageSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = remember(selectedCode) {
        translationLanguageDisplayName(selectedCode)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box {
            OutlinedButton(
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                TRANSLATION_LANGUAGE_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            onLanguageSelected(option.code)
                            expanded = false
                        },
                        trailingIcon = if (option.code.equals(selectedCode, ignoreCase = true)) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun E2EEvidenceOverlay(result: E2ETestResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "E2E EVIDENCE",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (result.pass) "PASS" else "FAIL",
                style = MaterialTheme.typography.labelSmall,
                color = if (result.pass) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (result.pass) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Model: ${result.modelId}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Engine: ${result.engine}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Duration: ${"%.0f".format(result.durationMs)} ms",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "TTS starts: ${result.ttsStartCount}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "TTS mic guard violations: ${result.ttsMicGuardViolations}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Mic stopped for TTS: ${result.micStoppedForTts}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (result.transcript.isNotEmpty()) {
            Text(
                text = result.transcript.take(120),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/** Isolated composable for high-frequency recording stats — limits recomposition scope. */
@Composable
private fun RecordingStatsBar(viewModel: TranscriptionViewModel) {
    val bufferEnergy by viewModel.bufferEnergy.collectAsState()
    val bufferSeconds by viewModel.bufferSeconds.collectAsState()
    val tokensPerSecond by viewModel.tokensPerSecond.collectAsState()
    val micPeakLevel = remember(bufferEnergy) { bufferEnergy.takeLast(12).maxOrNull() ?: 0f }

    AudioVisualizer(
        energyLevels = bufferEnergy,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = FormatUtils.formatDuration(bufferSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = String.format("mic %.3f", micPeakLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tokensPerSecond > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format("%.1f tok/s", tokensPerSecond),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Isolated composable for resource stats — limits recomposition scope from CPU/RAM polling. */
@Composable
private fun ResourceStatsBar(viewModel: TranscriptionViewModel, isRecording: Boolean, elapsedSeconds: Int) {
    val cpuPercent by viewModel.cpuPercent.collectAsState()
    val memoryMB by viewModel.memoryMB.collectAsState()
    val tokensPerSecond by viewModel.tokensPerSecond.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRecording) {
            Text(
                text = "${elapsedSeconds}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(
            text = String.format("CPU %.0f%%", cpuPercent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = String.format("RAM %.0f MB", memoryMB),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (tokensPerSecond > 0) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = String.format("%.1f tok/s", tokensPerSecond),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
