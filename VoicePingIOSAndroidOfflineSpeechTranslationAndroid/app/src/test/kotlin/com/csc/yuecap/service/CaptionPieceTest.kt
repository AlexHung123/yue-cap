package com.csc.yuecap.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionPieceTest {

    @Test
    fun nextCaptionPiece_returnsOnlyTheNewSuffix() {
        val piece = nextCaptionPiece("我哋用 API", "我哋用 API 做 auth")
        assertEquals("做 auth", piece?.text)
        assertEquals(false, piece?.replacesAll)
    }

    @Test
    fun nextCaptionPiece_skipsWhenNothingNew() {
        assertNull(nextCaptionPiece("sprint", "sprint"))
        assertNull(nextCaptionPiece("sprint", "  "))
    }

    @Test
    fun nextCaptionPiece_replacesWhenTranscriptIsRewritten() {
        val piece = nextCaptionPiece("舊句", "全新一句 API")
        assertEquals("全新一句 API", piece?.text)
        assertEquals(true, piece?.replacesAll)
    }
}
