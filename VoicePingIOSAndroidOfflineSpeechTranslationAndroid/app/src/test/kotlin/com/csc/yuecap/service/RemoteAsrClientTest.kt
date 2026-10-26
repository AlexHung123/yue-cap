package com.csc.yuecap.service

import com.csc.yuecap.util.WavWriter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteAsrClientTest {

    @Test
    fun sparkAsrUrl_appendsAsrPathAndCantoneseLanguage() {
        assertEquals(
            "http://10.0.0.8:8000/v1/asr?language=yue",
            sparkAsrUrl("http://10.0.0.8:8000", "yue")
        )
        assertEquals(
            "http://10.0.0.8:8000/v1/asr?language=yue",
            sparkAsrUrl("http://10.0.0.8:8000/", "yue")
        )
        assertEquals(
            "http://10.0.0.8:8000/v1/asr?language=yue",
            sparkAsrUrl("http://10.0.0.8:8000/v1/asr", "")
        )
    }

    @Test
    fun sparkAsrUrl_rejectsBlankAndNonHttp() {
        assertNull(sparkAsrUrl("  ", "yue"))
        assertNull(sparkAsrUrl("ftp://10.0.0.8/v1/asr", "yue"))
    }

    @Test
    fun parseSparkAsrPayload_keepsEnglishWords() {
        val payload = parseSparkAsrPayload(
            """{"text":"我哋下個 sprint 用 API","lang":"yue"}"""
        )
        assertEquals("我哋下個 sprint 用 API", payload.text)
        assertEquals("yue", payload.lang)
    }

    @Test
    fun parseSparkAsrPayload_unescapesQuotedEnglish() {
        val payload = parseSparkAsrPayload(
            """{"lang":"yue","text":"say \"API\""}"""
        )
        assertEquals("say \"API\"", payload.text)
    }

    @Test
    fun wavBody_isPcmWav() {
        val wav = WavWriter.toByteArray(floatArrayOf(0f, 0.5f, -0.5f))
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertTrue(wav.size == 44 + 6)
    }
}
