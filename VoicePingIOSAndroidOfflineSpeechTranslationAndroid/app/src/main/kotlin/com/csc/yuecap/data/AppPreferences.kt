package com.csc.yuecap.data

import android.content.Context
import com.csc.yuecap.model.AsrMode
import com.csc.yuecap.model.TranslationProvider
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        /** Meeting captions: Cantonese speech translated into English. */
        const val DEFAULT_SOURCE_LANGUAGE = "yue"
        const val DEFAULT_TARGET_LANGUAGE = "en"

        private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        private val LAST_MODEL_PATH = stringPreferencesKey("last_model_path")
        private val USE_VAD = booleanPreferencesKey("use_vad")
        private val ENABLE_TIMESTAMPS = booleanPreferencesKey("enable_timestamps")
        private val TRANSLATION_ENABLED = booleanPreferencesKey("translation_enabled")
        private val SPEAK_TRANSLATED_AUDIO = booleanPreferencesKey("speak_translated_audio")
        private val TRANSLATION_SOURCE_LANGUAGE = stringPreferencesKey("translation_source_language")
        private val TRANSLATION_TARGET_LANGUAGE = stringPreferencesKey("translation_target_language")
        private val TTS_RATE = stringPreferencesKey("tts_rate")
        private val CAPTION_FONT_SIZE_SP = stringPreferencesKey("caption_font_size_sp")
        private val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")

        /**
         * Discrete caption sizes (5 stops): Extra Small / Small / Medium / Large / Extra Large.
         * Default Medium = 22. Older 4-step values (18/22/28/34) map onto the nearest stop.
         */
        const val DEFAULT_CAPTION_FONT_SIZE_SP = 22
        val CAPTION_FONT_SIZE_OPTIONS = listOf(16, 18, 22, 28, 34)

        fun normalizeCaptionFontSizeSp(requested: Int?): Int {
            if (requested == null) return DEFAULT_CAPTION_FONT_SIZE_SP
            if (requested in CAPTION_FONT_SIZE_OPTIONS) return requested
            // Migrate any legacy / out-of-range value onto the nearest of the 5 stops.
            return CAPTION_FONT_SIZE_OPTIONS.minBy { kotlin.math.abs(it - requested) }
        }
        private val ASR_BASE_URL = stringPreferencesKey("asr_base_url")
        private val ASR_MODE = stringPreferencesKey("asr_mode")
        private val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val LLM_MODEL = stringPreferencesKey("llm_model")
        private val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val CANTONESE_POLISH_ENABLED = booleanPreferencesKey("cantonese_polish_enabled")
    }

    private val preferencesData = context.dataStore.data

    private fun <T> preferenceFlow(reader: (Preferences) -> T): Flow<T> {
        return preferencesData
            .map(reader)
            .distinctUntilChanged()
    }

    val selectedModelId: Flow<String?> = preferenceFlow { it[SELECTED_MODEL_ID] }
    val lastModelPath: Flow<String?> = preferenceFlow { it[LAST_MODEL_PATH] }
    val useVAD: Flow<Boolean> = preferenceFlow { it[USE_VAD] ?: true }
    val enableTimestamps: Flow<Boolean> = preferenceFlow { it[ENABLE_TIMESTAMPS] ?: true }
    val translationEnabled: Flow<Boolean> = preferenceFlow { it[TRANSLATION_ENABLED] ?: true }
    /** TTS / Read aloud removed from Settings; always off. */
    val speakTranslatedAudio: Flow<Boolean> = preferenceFlow { false }
    val translationSourceLanguage: Flow<String> = preferenceFlow {
        it[TRANSLATION_SOURCE_LANGUAGE] ?: DEFAULT_SOURCE_LANGUAGE
    }
    val translationTargetLanguage: Flow<String> = preferenceFlow { DEFAULT_TARGET_LANGUAGE }
    val ttsRate: Flow<Float> = preferenceFlow { prefs ->
        prefs[TTS_RATE]?.toFloatOrNull() ?: 1.0f
    }
    val captionFontSizeSp: Flow<Int> = preferenceFlow { prefs ->
        normalizeCaptionFontSizeSp(prefs[CAPTION_FONT_SIZE_SP]?.toIntOrNull())
    }
    /** Always Spark B — ML Kit / System no longer selectable. */
    val translationProvider: Flow<String> = preferenceFlow {
        TranslationProvider.REMOTE_SPARK_B.name
    }
    /** Spark A origin, for example `http://192.168.1.20:8000`. Empty until set. */
    val asrBaseUrl: Flow<String> = preferenceFlow { it[ASR_BASE_URL]?.trim().orEmpty() }
    val asrMode: Flow<AsrMode> = preferenceFlow { AsrMode.fromStored(it[ASR_MODE]) }
    /** Spark B origin, for example `http://192.168.1.21:8000`. Empty until set. */
    val llmBaseUrl: Flow<String> = preferenceFlow { it[LLM_BASE_URL]?.trim().orEmpty() }
    /** Model name the Spark B server expects in `model`. Empty until set. */
    val llmModel: Flow<String> = preferenceFlow { it[LLM_MODEL]?.trim().orEmpty() }
    /** Optional Bearer token. LAN servers can leave this empty. */
    val llmApiKey: Flow<String> = preferenceFlow { it[LLM_API_KEY]?.trim().orEmpty() }
    /** Light ASR polish of Cantonese before Spark B English translation. Default ON. */
    val cantonesePolishEnabled: Flow<Boolean> = preferenceFlow { it[CANTONESE_POLISH_ENABLED] ?: true }

    suspend fun setSelectedModelId(id: String) {
        context.dataStore.edit { it[SELECTED_MODEL_ID] = id }
    }

    suspend fun setLastModelPath(path: String) {
        context.dataStore.edit { it[LAST_MODEL_PATH] = path }
    }

    suspend fun setUseVAD(enabled: Boolean) {
        context.dataStore.edit { it[USE_VAD] = enabled }
    }

    suspend fun setEnableTimestamps(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_TIMESTAMPS] = enabled }
    }

    suspend fun setTranslationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TRANSLATION_ENABLED] = enabled }
    }

    suspend fun setSpeakTranslatedAudio(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Read aloud UI removed; keep preference forced off so TTS never runs.
        context.dataStore.edit { it[SPEAK_TRANSLATED_AUDIO] = false }
    }

    suspend fun setTranslationSourceLanguage(languageCode: String) {
        val normalized = languageCode.trim().lowercase(Locale.ROOT).ifEmpty { DEFAULT_SOURCE_LANGUAGE }
        context.dataStore.edit { it[TRANSLATION_SOURCE_LANGUAGE] = normalized }
    }

    suspend fun setTranslationTargetLanguage(languageCode: String) {
        val requested = languageCode.trim().lowercase(Locale.ROOT)
        if (requested.isNotEmpty() && requested != DEFAULT_TARGET_LANGUAGE) {
            Log.i("AppPreferences", "Ignoring target language '$requested'; YueCap target is English")
        }
        context.dataStore.edit { it[TRANSLATION_TARGET_LANGUAGE] = DEFAULT_TARGET_LANGUAGE }
    }

    suspend fun setTtsRate(rate: Float) {
        val normalized = rate.coerceIn(0.1f, 2.0f)
        context.dataStore.edit { it[TTS_RATE] = normalized.toString() }
    }

    suspend fun setCaptionFontSizeSp(sizeSp: Int) {
        val normalized = normalizeCaptionFontSizeSp(sizeSp)
        context.dataStore.edit { it[CAPTION_FONT_SIZE_SP] = normalized.toString() }
    }

    suspend fun setTranslationProvider(@Suppress("UNUSED_PARAMETER") provider: String) {
        // Hard-wired: ignore requested provider and always persist Spark B.
        context.dataStore.edit { it[TRANSLATION_PROVIDER] = TranslationProvider.REMOTE_SPARK_B.name }
    }

    suspend fun setAsrBaseUrl(url: String) {
        val normalized = url.trim()
        context.dataStore.edit { prefs ->
            if (normalized.isEmpty()) prefs.remove(ASR_BASE_URL)
            else prefs[ASR_BASE_URL] = normalized
        }
    }

    suspend fun setAsrMode(mode: AsrMode) {
        context.dataStore.edit { it[ASR_MODE] = mode.name }
    }

    suspend fun setLlmBaseUrl(url: String) {
        storeOrRemove(LLM_BASE_URL, url.trim())
    }

    suspend fun setLlmModel(model: String) {
        storeOrRemove(LLM_MODEL, model.trim())
    }

    suspend fun setLlmApiKey(apiKey: String) {
        storeOrRemove(LLM_API_KEY, apiKey.trim())
    }

    suspend fun setCantonesePolishEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CANTONESE_POLISH_ENABLED] = enabled }
    }

    private suspend fun storeOrRemove(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs ->
            if (value.isEmpty()) prefs.remove(key)
            else prefs[key] = value
        }
    }
}
