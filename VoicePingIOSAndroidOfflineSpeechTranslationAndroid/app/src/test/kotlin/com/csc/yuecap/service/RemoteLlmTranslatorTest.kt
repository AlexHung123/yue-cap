package com.csc.yuecap.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLlmTranslatorTest {

    @Test
    fun sparkChatCompletionsUrl_appendsOpenAiPath() {
        assertEquals(
            "http://10.0.0.9:8000/v1/chat/completions",
            sparkChatCompletionsUrl("http://10.0.0.9:8000")
        )
        assertEquals(
            "http://10.0.0.9:8000/v1/chat/completions",
            sparkChatCompletionsUrl("http://10.0.0.9:8000/v1/chat/completions")
        )
    }

    @Test
    fun sparkChatCompletionsUrl_rejectsBlankAndNonHttp() {
        assertNull(sparkChatCompletionsUrl("  "))
        assertNull(sparkChatCompletionsUrl("ftp://10.0.0.9/v1/chat/completions"))
    }

    @Test
    fun buildChatCompletionsBody_keepsEnglishWordsAndEscapesQuotes() {
        val body = buildChatCompletionsBody(
            model = "local",
            systemPrompt = translationSystemPrompt("en"),
            userText = "我哋下個 sprint 用 \"API\""
        )
        assertTrue(body.contains("我哋下個 sprint 用 \\\"API\\\""))
        assertTrue(body.contains("Keep existing English terms as-is"))
        assertTrue(body.contains("\"model\":\"local\""))
    }

    @Test
    fun polishAndTranslateSystemPrompt_asksForJsonAndKeepsColloquial() {
        val prompt = polishAndTranslateSystemPrompt("en")
        assertTrue(prompt.contains("Keep colloquial Cantonese"))
        assertTrue(prompt.contains("\"yue\""))
        assertTrue(prompt.contains("\"en\""))
        assertTrue(prompt.contains("Do NOT rewrite into written Chinese or Mandarin"))
        assertTrue(prompt.contains("FIRST revise the ASR text"))
        assertTrue(prompt.contains("THEN finally translate"))
    }

    @Test
    fun parsePolishAndTranslateContent_readsJsonObject() {
        val result = parsePolishAndTranslateContent(
            content = """{"yue":"我哋聽日開會","en":"We have a meeting tomorrow."}""",
            fallbackYue = "我地聽日開會"
        )
        assertEquals("我哋聽日開會", result.polishedYue)
        assertEquals("We have a meeting tomorrow.", result.english)
    }

    @Test
    fun parsePolishAndTranslateContent_stripsMarkdownFence() {
        val result = parsePolishAndTranslateContent(
            content = """
                ```json
                {"yue":"你好","en":"Hello"}
                ```
            """.trimIndent(),
            fallbackYue = "你好啊"
        )
        assertEquals("你好", result.polishedYue)
        assertEquals("Hello", result.english)
    }

    @Test
    fun parsePolishAndTranslateContent_fallsBackWhenNotJson() {
        val result = parsePolishAndTranslateContent(
            content = "We will ship the API next sprint.",
            fallbackYue = "我哋下個 sprint 出 API"
        )
        assertEquals("我哋下個 sprint 出 API", result.polishedYue)
        assertEquals("We will ship the API next sprint.", result.english)
    }

    @Test
    fun parseChatCompletionContent_keepsEnglishWords() {
        val raw = """
            {"choices":[{"message":{"role":"assistant","content":"We'll use the API next sprint."}}]}
        """.trimIndent()
        assertEquals(
            "We'll use the API next sprint.",
            parseChatCompletionContent(raw)
        )
    }

    @Test
    fun parseChatCompletionContent_emptyReplyIsEmptyString() {
        assertEquals("", parseChatCompletionContent("""{"choices":[{"message":{"content":""}}]}"""))
        assertEquals("", parseChatCompletionContent("""{"error":{"message":"busy"}}"""))
    }
}
