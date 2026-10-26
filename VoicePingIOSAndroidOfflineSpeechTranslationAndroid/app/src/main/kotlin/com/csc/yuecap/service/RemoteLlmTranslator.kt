package com.csc.yuecap.service

import android.util.Log
import com.csc.yuecap.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Translates Cantonese ASR text into English captions by calling a
 * self-hosted OpenAI-compatible chat server on Spark B.
 *
 * The HTTP shape is `POST {baseUrl}/v1/chat/completions`. The server is a
 * LAN runtime such as vLLM, Ollama, or llama.cpp, not the OpenAI cloud.
 * English words already in the source stay in the caption.
 */
class RemoteLlmTranslator(
    initialBaseUrl: String = "",
    initialModel: String = "",
    initialApiKey: String = "",
    private val client: OkHttpClient = defaultClient(),
) {
    @Volatile
    private var baseUrl: String = initialBaseUrl.trim()

    @Volatile
    private var model: String = initialModel.trim()

    @Volatile
    private var apiKey: String = initialApiKey.trim()

    fun setBaseUrl(url: String) {
        baseUrl = url.trim()
    }

    fun setModel(model: String) {
        this.model = model.trim()
    }

    fun setApiKey(apiKey: String) {
        this.apiKey = apiKey.trim()
    }

    /**
     * Translate [sourceText] into [targetLang].
     * An empty model reply returns an empty string and does not retry.
     * HTTP errors and timeouts throw [IOException] immediately.
     */
    suspend fun translate(
        sourceText: String,
        sourceLang: String,
        targetLang: String = AppPreferences.DEFAULT_TARGET_LANGUAGE,
    ): String = withContext(Dispatchers.IO) {
        val text = sourceText.trim()
        if (text.isEmpty()) return@withContext ""
        val source = sourceLang.trim().ifEmpty { AppPreferences.DEFAULT_SOURCE_LANGUAGE }
        val target = targetLang.trim().ifEmpty { AppPreferences.DEFAULT_TARGET_LANGUAGE }
        if (source.equals(target, ignoreCase = true)) return@withContext text

        val url = sparkChatCompletionsUrl(baseUrl)
            ?: throw IOException("Spark B URL is not set")
        val body = buildChatCompletionsBody(
            model = model.ifEmpty { DEFAULT_MODEL_NAME },
            systemPrompt = translationSystemPrompt(target),
            userText = text
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Spark B translation failed: HTTP ${response.code}")
                }
                val translated = parseChatCompletionContent(raw)
                Log.i(TAG, "Spark B textLen=${text.length} outLen=${translated.length}")
                translated
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("Spark B translation timed out", e)
        }
    }

    companion object {
        private const val TAG = "RemoteLlmTranslator"
        private const val DEFAULT_MODEL_NAME = "local"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

internal fun sparkChatCompletionsUrl(baseUrl: String): String? {
    val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    val path = parsed.encodedPath.removeSuffix("/")
    val builder = parsed.newBuilder()
    if (!path.endsWith("/v1/chat/completions")) {
        builder.addPathSegments("v1/chat/completions")
    }
    return builder.build().toString()
}

internal fun translationSystemPrompt(targetLang: String): String {
    val target = if (targetLang.equals("en", ignoreCase = true)) "English" else targetLang.trim()
    return "The input is mostly Cantonese meeting speech and may include occasional English words " +
        "(code-switching). Translate into clear concise $target. Keep existing English terms as-is " +
        "(product names, acronyms, jargon). Output $target only."
}

internal fun buildChatCompletionsBody(model: String, systemPrompt: String, userText: String): String {
    return buildString {
        append("{\"model\":")
        append(jsonString(model))
        append(",\"temperature\":0,\"max_tokens\":256,\"messages\":[")
        append("{\"role\":\"system\",\"content\":")
        append(jsonString(systemPrompt))
        append("},{\"role\":\"user\",\"content\":")
        append(jsonString(userText))
        append("}]}")
    }
}

internal fun parseChatCompletionContent(body: String): String {
    val choicesAt = body.indexOf("\"choices\"")
    val region = if (choicesAt >= 0) body.substring(choicesAt) else return ""
    return jsonStringField(region, "content")?.trim().orEmpty()
}

internal fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
    }
    out.append('"')
    return out.toString()
}
