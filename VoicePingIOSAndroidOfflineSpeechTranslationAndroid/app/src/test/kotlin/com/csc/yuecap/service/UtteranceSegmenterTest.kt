package com.csc.yuecap.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UtteranceSegmenterTest {
    private val segmenter = UtteranceSegmenter()
    private val sampleRate = AudioConstants.SAMPLE_RATE

    @Test
    fun silence_keepsPadAndAdvancesCursor() {
        val samples = silence(2.0f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.AdvanceSilence, decision.action)
        assertEquals(samples.size - seconds(0.2f), decision.nextCursorSample)
    }

    @Test
    fun shortBlipThenSilence_isSkipped() {
        val samples = tone(0.3f) + silence(0.6f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.SkipShort, decision.action)
        assertTrue(decision.nextCursorSample > seconds(0.3f))
        assertEquals(samples.size - seconds(0.2f), decision.nextCursorSample)
    }

    @Test
    fun speechWithoutPause_staysHypothesis() {
        val samples = speech(1.2f) + silence(0.2f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.Hypothesis, decision.action)
        assertEquals(0, decision.sliceStartSample)
        assertEquals(samples.size, decision.sliceEndSample)
        assertEquals(0, decision.nextCursorSample)
    }

    @Test
    fun pauseAfterSpeech_endpointsWithPad() {
        val leading = silence(0.3f)
        val spoken = speech(1.2f)
        val trailing = silence(0.6f)
        val samples = leading + spoken + trailing
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.Endpoint, decision.action)
        assertEquals(seconds(0.3f) - seconds(0.2f), decision.sliceStartSample)
        assertEquals(seconds(0.3f) + seconds(1.2f) + seconds(0.2f), decision.sliceEndSample)
        assertEquals(decision.sliceEndSample, decision.nextCursorSample)
        assertTrue(decision.sliceEndSample < samples.size)
    }

    @Test
    fun uninterruptedSpeech_forceCutsAtFiveSeconds() {
        val samples = speech(5.2f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.ForceCut, decision.action)
        assertEquals(0, decision.sliceStartSample)
        assertEquals(seconds(5.0f), decision.sliceEndSample)
        assertEquals(seconds(5.0f), decision.nextCursorSample)
    }

    @Test
    fun monotoneBed_isNotSentAsSpeech() {
        val samples = tone(1.2f) + silence(0.2f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.SkipNonSpeech, decision.action)
        assertTrue(decision.nextCursorSample > seconds(0.8f))
    }

    @Test
    fun monotoneBedThenPause_skipsTheWholeRun() {
        val samples = tone(1.2f) + silence(0.6f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.SkipNonSpeech, decision.action)
        assertTrue(decision.nextCursorSample > seconds(1.2f))
    }

    @Test
    fun longMonotoneBed_skipsAtTheFiveSecondCap() {
        val samples = tone(5.2f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.SkipNonSpeech, decision.action)
        assertEquals(seconds(5.0f), decision.nextCursorSample)
    }

    @Test
    fun shortBlipBeforeOpenUtterance_hypothesisStartsAfterBlip() {
        val samples = tone(0.3f) + silence(0.6f) + speech(1.0f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.Hypothesis, decision.action)
        val speechStart = seconds(0.3f) + seconds(0.6f)
        assertEquals(speechStart - seconds(0.2f), decision.sliceStartSample)
        assertEquals(speechStart - seconds(0.2f), decision.nextCursorSample)
        assertEquals(samples.size, decision.sliceEndSample)
    }

    @Test
    fun twoSentencesInOneBuffer_returnsOnlyTheFirstEndpoint() {
        val samples = speech(1.0f) + silence(0.6f) + speech(1.0f)
        val decision = decide(samples)

        assertEquals(UtteranceDecision.Action.Endpoint, decision.action)
        assertEquals(seconds(1.0f) + seconds(0.2f), decision.sliceEndSample)
        assertTrue(decision.sliceEndSample < seconds(1.0f) + seconds(0.6f))
    }

    @Test
    fun flush_commitsTrailingSpeechWithoutAPause() {
        val samples = speech(0.5f)
        val decision = decide(samples, flush = true)

        assertEquals(UtteranceDecision.Action.Endpoint, decision.action)
        assertEquals(samples.size, decision.sliceEndSample)
    }

    @Test
    fun flush_dropsAMonotoneBed() {
        val samples = tone(1.0f)
        val decision = decide(samples, flush = true)

        assertEquals(UtteranceDecision.Action.SkipNonSpeech, decision.action)
    }

    @Test
    fun flush_dropsAClickShorterThanTheFlushMinimum() {
        val samples = tone(0.2f)
        val decision = decide(samples, flush = true)

        assertEquals(UtteranceDecision.Action.Wait, decision.action)
    }

    @Test
    fun commitUtterance_foldsHypothesisAndMovesCursor() {
        val manager = StreamingChunkManager(chunkSeconds = 5f, sampleRate = sampleRate)
        manager.processTranscriptionResult(listOf(TranscriptionSegment("我哋聽日", 0, 0)))
        assertEquals("我哋聽日", manager.hypothesisText)

        manager.commitUtterance(1500)

        assertEquals("我哋聽日", manager.confirmedText)
        assertEquals("", manager.hypothesisText)
        assertEquals(1500L, manager.lastConfirmedSegmentEndMs)
    }

    @Test
    fun discardUtterance_dropsHypothesisAndMovesCursor() {
        val manager = StreamingChunkManager(chunkSeconds = 5f, sampleRate = sampleRate)
        manager.processTranscriptionResult(listOf(TranscriptionSegment("背景音樂", 0, 0)))

        manager.discardUtterance(2000)

        assertEquals("", manager.hypothesisText)
        assertEquals("", manager.confirmedText)
        assertEquals(2000L, manager.lastConfirmedSegmentEndMs)
    }

    @Test
    fun advanceCursor_doesNotCommitHypothesis() {
        val manager = StreamingChunkManager(chunkSeconds = 5f, sampleRate = sampleRate)
        manager.processTranscriptionResult(listOf(TranscriptionSegment("我哋聽日", 0, 0)))

        manager.advanceCursor(200)

        assertEquals("我哋聽日", manager.hypothesisText)
        assertEquals("", manager.confirmedText)
        assertEquals(200L, manager.lastConfirmedSegmentEndMs)
    }

    private fun decide(samples: FloatArray, flush: Boolean = false): UtteranceDecision {
        return segmenter.decide(
            cursorSample = 0,
            bufferEndSample = samples.size,
            samples = samples,
            samplesOrigin = 0,
            flush = flush,
        )
    }

    private fun seconds(value: Float): Int = (value * sampleRate).toInt()

    private fun silence(durationSeconds: Float): FloatArray = FloatArray(seconds(durationSeconds))

    private fun tone(durationSeconds: Float, amplitude: Float = 0.05f): FloatArray {
        return FloatArray(seconds(durationSeconds)) { amplitude }
    }

    /** Loud syllable, then a near-silent gap. The last frame stays loud. */
    private fun speech(durationSeconds: Float): FloatArray {
        val total = seconds(durationSeconds)
        val frame = sampleRate / 10
        return FloatArray(total) { index ->
            val lastFrame = index >= total - frame
            if (lastFrame || (index / frame) % 2 == 0) 0.08f else 0f
        }
    }
}
