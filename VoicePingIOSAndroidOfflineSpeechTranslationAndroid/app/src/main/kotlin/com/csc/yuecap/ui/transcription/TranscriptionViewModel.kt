package com.csc.yuecap.ui.transcription

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csc.yuecap.data.AppDatabase
import com.csc.yuecap.data.TranscriptionEntity
import com.csc.yuecap.model.AudioInputMode
import com.csc.yuecap.model.ModelInfo
import com.csc.yuecap.model.TranslationProvider
import com.csc.yuecap.service.AudioConstants
import com.csc.yuecap.service.ConnectionTestResult
import com.csc.yuecap.service.WhisperEngine
import com.csc.yuecap.util.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionViewModel(
    val engine: WhisperEngine,
    private val database: AppDatabase,
    private val filesDir: File
) : ViewModel() {

    val isRecording = engine.isRecording
    val confirmedText = engine.confirmedText
    val hypothesisText = engine.hypothesisText
    val bufferEnergy = engine.bufferEnergy
    val bufferSeconds = engine.bufferSeconds
    val tokensPerSecond = engine.tokensPerSecond
    val lastError = engine.lastError
    val selectedModel = engine.selectedModel
    val modelState = engine.modelState
    val useVAD = engine.useVAD
    val enableTimestamps = engine.enableTimestamps
    val audioInputMode = engine.audioInputMode
    val systemAudioCaptureReady = engine.systemAudioCaptureReady
    val isSystemAudioCaptureSupported: Boolean
        get() = engine.isSystemAudioCaptureSupported
    val translationEnabled = engine.translationEnabled
    val cantonesePolishEnabled = engine.cantonesePolishEnabled
    val speakTranslatedAudio = engine.speakTranslatedAudio
    val translationSourceLanguageCode = engine.translationSourceLanguageCode
    val translationTargetLanguageCode = engine.translationTargetLanguageCode
    val ttsRate = engine.ttsRate
    val captionFontSizeSp = engine.captionFontSizeSp
    val translatedConfirmedText = engine.translatedConfirmedText
    val translatedHypothesisText = engine.translatedHypothesisText
    val translationWarning = engine.translationWarning
    val translationProvider = engine.translationProvider
    val translationModelReady = engine.translationModelReady
    val translationDownloadStatus = engine.translationDownloadStatus
    val isAndroidSystemTranslationAvailable = engine.isAndroidSystemTranslationAvailable
    val cpuPercent = engine.cpuPercent
    val memoryMB = engine.memoryMB
    val e2eResult = engine.e2eResult
    val asrBaseUrl = engine.asrBaseUrl
    val llmBaseUrl = engine.llmBaseUrl
    val llmModel = engine.llmModel
    val llmApiKey = engine.llmApiKey

    private val _sparkATest = MutableStateFlow<ConnectionTestResult?>(null)
    val sparkATest: StateFlow<ConnectionTestResult?> = _sparkATest.asStateFlow()
    private val _sparkATesting = MutableStateFlow(false)
    val sparkATesting: StateFlow<Boolean> = _sparkATesting.asStateFlow()
    private val _sparkBTest = MutableStateFlow<ConnectionTestResult?>(null)
    val sparkBTest: StateFlow<ConnectionTestResult?> = _sparkBTest.asStateFlow()
    private val _sparkBTesting = MutableStateFlow(false)
    val sparkBTesting: StateFlow<Boolean> = _sparkBTesting.asStateFlow()

    private val _showSaveConfirmation = MutableStateFlow(false)
    val showSaveConfirmation: StateFlow<Boolean> = _showSaveConfirmation.asStateFlow()

    val fullText: String
        get() = engine.fullTranscriptionText

    private inline fun launchEngineAction(crossinline block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            block()
        }
    }

    private fun copyAssetIfMissing(context: Context, assetName: String): File {
        val cached = File(context.cacheDir, assetName)
        if (cached.exists()) return cached
        context.assets.open(assetName).use { input ->
            cached.outputStream().use { output -> input.copyTo(output) }
        }
        return cached
    }

    private fun sessionDirFor(sessionId: String): File = File(filesDir, "sessions/$sessionId")

    fun toggleRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        } else {
            startRecordingWithPreparation()
        }
    }

    fun startRecordingWithPreparation() {
        launchEngineAction {
            engine.prewarmRealtimePath()
            engine.startRecording()
        }
    }

    fun prewarmOnScreenOpen() {
        launchEngineAction {
            engine.prewarmRealtimePath()
        }
    }

    fun clearTranscription() {
        engine.clearTranscription()
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        engine.setAudioInputMode(mode)
    }

    fun setSystemAudioCapturePermission(resultCode: Int, data: Intent?) {
        engine.setSystemAudioCapturePermission(resultCode, data)
    }

    /** Dismiss error without clearing transcription text. */
    fun dismissError() {
        engine.clearError()
    }

    fun saveTranscription() {
        val text = fullText
        if (text.isBlank()) {
            Log.w("TranscriptionVM", "saveTranscription: fullText is blank, nothing to save")
            return
        }

        // Use actual audio buffer duration, not wall clock
        val duration = engine.recordingDurationSeconds
        val language = engine.detectedLanguage.value
        val modelName = engine.selectedModel.value.displayName
        Log.i("TranscriptionVM", "saveTranscription: text=${text.length} chars, duration=${"%.1f".format(duration)}s, model=$modelName, lang=$language")

        viewModelScope.launch {
            val entity = TranscriptionEntity(
                text = text,
                durationSeconds = duration,
                modelUsed = modelName,
                language = language
            )

            // Write audio WAV if samples are available
            val samples = engine.audioRecorder.samples
            var audioRelPath: String? = null
            if (samples.isNotEmpty()) {
                if (engine.audioRecorder.hasDroppedSamples) {
                    Log.w("TranscriptionVM", "Audio buffer was trimmed during long recording — saved WAV will be incomplete")
                }
                try {
                    val sessionDir = sessionDirFor(entity.id)
                    val wavFile = File(sessionDir, "audio.wav")
                    withContext(Dispatchers.IO) {
                        WavWriter.write(samples, outputFile = wavFile)
                    }
                    audioRelPath = "sessions/${entity.id}/audio.wav"
                    Log.i("TranscriptionVM", "Saved audio WAV: ${wavFile.length()} bytes (${samples.size} samples, ${"%.1f".format(samples.size / AudioConstants.SAMPLE_RATE.toDouble())}s)")
                } catch (e: Exception) {
                    Log.w("TranscriptionVM", "Failed to write audio WAV", e)
                }
            } else {
                Log.w("TranscriptionVM", "No audio samples available for WAV save")
            }

            try {
                database.transcriptionDao().insert(
                    entity.copy(audioFileName = audioRelPath)
                )
                Log.i("TranscriptionVM", "Session saved to history: id=${entity.id}")
                _showSaveConfirmation.value = true
            } catch (e: Exception) {
                Log.e("TranscriptionVM", "Failed to save session to database", e)
                // Clean up audio file on DB failure
                if (audioRelPath != null) {
                    sessionDirFor(entity.id).deleteRecursively()
                }
                engine.setLastError(
                    com.csc.yuecap.model.AppError.TranscriptionFailed(e)
                )
            }
        }
    }

    fun dismissSaveConfirmation() {
        _showSaveConfirmation.value = false
    }

    fun transcribeTestFile(filePath: String) {
        engine.transcribeFile(filePath)
    }

    fun transcribeTestAsset(context: Context) {
        val cached = copyAssetIfMissing(context, "test_speech.wav")
        engine.transcribeFile(cached.absolutePath)
    }

    fun stopIfRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        }
    }

    fun switchModel(model: ModelInfo) {
        launchEngineAction {
            engine.switchModel(model)
        }
    }

    fun setUseVAD(enabled: Boolean) {
        launchEngineAction {
            engine.setUseVAD(enabled)
        }
    }

    fun setEnableTimestamps(enabled: Boolean) {
        launchEngineAction {
            engine.setEnableTimestamps(enabled)
        }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        launchEngineAction {
            engine.setTranslationEnabled(enabled)
        }
    }

    fun setCantonesePolishEnabled(enabled: Boolean) {
        launchEngineAction {
            engine.setCantonesePolishEnabled(enabled)
        }
    }

    fun setSpeakTranslatedAudio(enabled: Boolean) {
        launchEngineAction {
            engine.setSpeakTranslatedAudio(enabled)
        }
    }

    fun setTranslationSourceLanguageCode(languageCode: String) {
        launchEngineAction {
            engine.setTranslationSourceLanguageCode(languageCode)
        }
    }

    fun setTranslationTargetLanguageCode(languageCode: String) {
        launchEngineAction {
            engine.setTranslationTargetLanguageCode(languageCode)
        }
    }

    fun setTtsRate(rate: Float) {
        launchEngineAction {
            engine.setTtsRate(rate)
        }
    }

    fun setCaptionFontSizeSp(sizeSp: Int) {
        launchEngineAction {
            engine.setCaptionFontSizeSp(sizeSp)
        }
    }

    fun setTranslationProvider(provider: TranslationProvider) {
        launchEngineAction {
            engine.setTranslationProvider(provider)
        }
    }

    fun saveServerEndpoints(asrUrl: String, llmUrl: String, model: String, apiKey: String) {
        launchEngineAction {
            engine.setAsrBaseUrl(asrUrl)
            engine.setLlmBaseUrl(llmUrl)
            engine.setLlmModel(model)
            engine.setLlmApiKey(apiKey)
        }
    }

    fun testSparkA(url: String) {
        viewModelScope.launch {
            _sparkATesting.value = true
            _sparkATest.value = engine.testSparkAConnection(url)
            _sparkATesting.value = false
        }
    }

    fun testSparkB(url: String, model: String, apiKey: String) {
        viewModelScope.launch {
            _sparkBTesting.value = true
            _sparkBTest.value = engine.testSparkBConnection(url, model, apiKey)
            _sparkBTesting.value = false
        }
    }
}
