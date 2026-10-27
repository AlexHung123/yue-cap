package com.csc.yuecap.service

import android.util.Log
import com.csc.yuecap.data.AppPreferences
import com.csc.yuecap.util.TextNormalizationUtils
import com.csc.yuecap.util.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends 16 kHz mono WAV to Spark A.
 *
 * `POST {baseUrl}/v1/asr?language=yue` with `Content-Type: audio/wav`.
 * Response JSON is `{ "text": "...", "lang": "yue" }`. The text is returned
 * unchanged, including English words spoken inside Cantonese.
 */
class RemoteAsrClient(
    initialBaseUrl: String = "",
    private val client: OkHttpClient = defaultClient(),
) : AsrEngine {

    @Volatile
    private var baseUrl: String = initialBaseUrl.trim()

    fun setBaseUrl(url: String) {
        baseUrl = url.trim()
    }

    override val isLoaded: Boolean
        get() = sparkAsrUrl(baseUrl, AppPreferences.DEFAULT_SOURCE_LANGUAGE) != null

    override suspend fun loadModel(modelPath: String): Boolean = isLoaded

    override fun release() = Unit

    @Suppress("UNUSED_PARAMETER")
    override suspend fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int,
        language: String
    ): List<TranscriptionSegment> = withContext(Dispatchers.IO) {
        if (audioSamples.isEmpty()) return@withContext emptyList()
        // Always force SenseVoice language=yue (ignore caller / UI drift to ja/auto).
        val lang = AppPreferences.DEFAULT_SOURCE_LANGUAGE
        val url = sparkAsrUrl(baseUrl, lang)
            ?: throw IOException("Spark A URL is not set")
        val wav = WavWriter.toByteArray(audioSamples, AudioConstants.SAMPLE_RATE)
        val request = Request.Builder()
            .url(url)
            .post(wav.toRequestBody(WAV_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Spark A ASR failed: HTTP ${response.code}")
            }
            val payload = parseSparkAsrPayload(raw)
            val text = payload.text.trim()
            if (text.isEmpty()) return@withContext emptyList()
            Log.i(TAG, "Spark A textLen=${text.length} lang=${payload.lang}")
            val durationMs = audioSamples.size * 1000L / AudioConstants.SAMPLE_RATE
            listOf(
                TranscriptionSegment(
                    text = text,
                    startMs = 0L,
                    endMs = durationMs,
                    detectedLanguage = TextNormalizationUtils.normalizeLanguageCode(payload.lang)
                )
            )
        }
    }

    companion object {
        private const val TAG = "RemoteAsrClient"
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun sparkAsrUrl(baseUrl: String, language: String): String? {
    val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    // YueCap SenseVoice path is always Cantonese; never pass ja/auto/zh from UI.
    val lang = AppPreferences.DEFAULT_SOURCE_LANGUAGE
    val path = parsed.encodedPath.removeSuffix("/")
    val builder = parsed.newBuilder()
    if (!path.endsWith("/v1/asr")) {
        builder.addPathSegments("v1/asr")
    }
    return builder.setQueryParameter("language", lang).build().toString()
}

internal data class SparkAsrPayload(val text: String, val lang: String?)

internal fun parseSparkAsrPayload(body: String): SparkAsrPayload {
    val text = jsonStringField(body, "text")
        ?: throw IOException("Spark A response has no text field")
    return SparkAsrPayload(text = text, lang = jsonStringField(body, "lang"))
}

internal fun jsonStringField(json: String, key: String): String? {
    val keyMatch = Regex(""""${Regex.escape(key)}"\s*:""").find(json) ?: return null
    var index = keyMatch.range.last + 1
    while (index < json.length && json[index].isWhitespace()) index++
    if (index >= json.length || json[index] != '"') return null
    index++
    val raw = StringBuilder()
    while (index < json.length) {
        val char = json[index]
        if (char == '"') return unescapeJsonString(raw.toString())
        if (char == '\\') {
            if (index + 1 >= json.length) return null
            raw.append('\\')
            raw.append(json[index + 1])
            if (json[index + 1] == 'u') {
                if (index + 5 >= json.length) return null
                raw.append(json, index + 2, index + 6)
                index += 6
                continue
            }
            index += 2
            continue
        }
        raw.append(char)
        index++
    }
    return null
}

internal fun unescapeJsonString(raw: String): String {
    val out = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char != '\\' || index + 1 >= raw.length) {
            out.append(char)
            index++
            continue
        }
        when (val next = raw[index + 1]) {
            '"', '\\', '/' -> {
                out.append(next)
                index += 2
            }
            'b' -> {
                out.append('\b')
                index += 2
            }
            'f' -> {
                out.append('\u000C')
                index += 2
            }
            'n' -> {
                out.append('\n')
                index += 2
            }
            'r' -> {
                out.append('\r')
                index += 2
            }
            't' -> {
                out.append('\t')
                index += 2
            }
            'u' -> {
                if (index + 5 >= raw.length) {
                    out.append(char)
                    index++
                } else {
                    val code = raw.substring(index + 2, index + 6).toIntOrNull(16)
                    if (code == null) {
                        out.append(char)
                        index++
                    } else {
                        out.append(code.toChar())
                        index += 6
                    }
                }
            }
            else -> {
                out.append(next)
                index += 2
            }
        }
    }
    return out.toString()
}
