package com.csc.yuecap.service

import kotlin.math.sqrt

/**
 * Cuts a microphone buffer into SenseVoice utterances.
 *
 * SenseVoice is an offline model. Fixed-length windows split syllables and
 * short scraps are where Cantonese collapses. This segmenter waits for a
 * pause, keeps a short pad, and only force-cuts when speech runs past the cap.
 *
 * The segmenter is stateless. The caller owns the cursor.
 */
class UtteranceSegmenter(
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val frameSamples: Int = AudioConstants.SAMPLE_RATE / 10,
    private val maxUtteranceSeconds: Float = MAX_UTTERANCE_SECONDS,
    private val minUtteranceSeconds: Float = MIN_UTTERANCE_SECONDS,
    private val endpointSilenceSeconds: Float = ENDPOINT_SILENCE_SECONDS,
    private val padSeconds: Float = PAD_SECONDS,
    private val voiceRms: Float = VOICE_RMS,
    private val minFlushSeconds: Float = MIN_FLUSH_SECONDS,
) {
    fun decide(
        cursorSample: Int,
        bufferEndSample: Int,
        samples: FloatArray,
        samplesOrigin: Int,
        flush: Boolean = false,
    ): UtteranceDecision {
        if (bufferEndSample <= cursorSample || samples.isEmpty()) {
            return UtteranceDecision.wait(cursorSample)
        }

        val maxSamples = secondsToSamples(maxUtteranceSeconds)
        val minSamples = secondsToSamples(minUtteranceSeconds)
        val silenceSamples = secondsToSamples(endpointSilenceSeconds)
        val padSamples = secondsToSamples(padSeconds)
        val flushSamples = secondsToSamples(minFlushSeconds)

        var position = cursorSample
        var speechStart = -1
        var lastVoiceEnd = -1
        var skippedUntil = cursorSample
        val levels = ArrayList<Float>()

        while (position < bufferEndSample) {
            val frameEnd = minOf(position + frameSamples, bufferEndSample)
            val frameRms = rms(samples, samplesOrigin, position, frameEnd)
            if (frameRms >= voiceRms) {
                if (speechStart < 0) {
                    speechStart = position
                    levels.clear()
                }
                levels.add(frameRms)
                lastVoiceEnd = frameEnd
                if (lastVoiceEnd - speechStart >= maxSamples) {
                    val cut = speechStart + maxSamples
                    return cutDecision(
                        action = speechOrSteady(
                            UtteranceDecision.Action.ForceCut,
                            levels,
                        ),
                        speechStart = speechStart,
                        sliceEnd = cut,
                        cursorSample = cursorSample,
                        bufferEndSample = bufferEndSample,
                        samplesOrigin = samplesOrigin,
                        padSamples = padSamples,
                    )
                }
            } else if (speechStart >= 0 && frameEnd - lastVoiceEnd >= silenceSamples) {
                val speechSamples = lastVoiceEnd - speechStart
                if (speechSamples >= minSamples) {
                    return cutDecision(
                        action = speechOrSteady(
                            UtteranceDecision.Action.Endpoint,
                            levels,
                        ),
                        speechStart = speechStart,
                        sliceEnd = lastVoiceEnd + padSamples,
                        cursorSample = cursorSample,
                        bufferEndSample = bufferEndSample,
                        samplesOrigin = samplesOrigin,
                        padSamples = padSamples,
                    )
                }
                // Keep the blip's end as the earliest legal cursor, but leave the
                // trailing silence in place so the next utterance still has pad.
                skippedUntil = maxOf(skippedUntil, lastVoiceEnd)
                speechStart = -1
                lastVoiceEnd = -1
                levels.clear()
                position = frameEnd
                continue
            } else if (speechStart >= 0) {
                // Short dip inside the run. A monotone bed has none of these.
                levels.add(frameRms)
            }
            if (frameEnd == bufferEndSample) break
            position = frameEnd
        }

        if (flush && speechStart >= 0 && lastVoiceEnd - speechStart >= flushSamples) {
            return cutDecision(
                action = speechOrSteady(
                    UtteranceDecision.Action.Endpoint,
                    levels,
                ),
                speechStart = speechStart,
                sliceEnd = lastVoiceEnd + padSamples,
                cursorSample = cursorSample,
                bufferEndSample = bufferEndSample,
                samplesOrigin = samplesOrigin,
                padSamples = padSamples,
            )
        }

        if (speechStart >= 0 && lastVoiceEnd - speechStart >= minSamples) {
            if (isSteadyNonSpeech(levels)) {
                val next = (bufferEndSample - padSamples).coerceAtLeast(cursorSample)
                if (next > cursorSample) {
                    return UtteranceDecision(
                        action = UtteranceDecision.Action.SkipNonSpeech,
                        sliceStartSample = next,
                        sliceEndSample = next,
                        nextCursorSample = next,
                    )
                }
                return UtteranceDecision.wait(cursorSample)
            }
            val sliceStart = paddedStart(speechStart, padSamples, samplesOrigin)
            val nextCursor = sliceStart.coerceAtLeast(cursorSample)
            return UtteranceDecision(
                action = UtteranceDecision.Action.Hypothesis,
                sliceStartSample = sliceStart,
                sliceEndSample = bufferEndSample,
                nextCursorSample = nextCursor,
            )
        }

        if (speechStart >= 0) {
            val preroll = (speechStart - padSamples).coerceIn(cursorSample, speechStart)
            if (preroll > cursorSample) {
                return UtteranceDecision(
                    action = UtteranceDecision.Action.AdvanceSilence,
                    sliceStartSample = preroll,
                    sliceEndSample = preroll,
                    nextCursorSample = preroll,
                )
            }
            return UtteranceDecision.wait(cursorSample)
        }

        val prerollCursor = (bufferEndSample - padSamples).coerceAtLeast(cursorSample)
        val next = maxOf(skippedUntil, prerollCursor)
        if (next > cursorSample) {
            val action = if (skippedUntil > cursorSample) {
                UtteranceDecision.Action.SkipShort
            } else {
                UtteranceDecision.Action.AdvanceSilence
            }
            return UtteranceDecision(
                action = action,
                sliceStartSample = next,
                sliceEndSample = next,
                nextCursorSample = next,
            )
        }
        return UtteranceDecision.wait(cursorSample)
    }

    private fun cutDecision(
        action: UtteranceDecision.Action,
        speechStart: Int,
        sliceEnd: Int,
        cursorSample: Int,
        bufferEndSample: Int,
        samplesOrigin: Int,
        padSamples: Int,
    ): UtteranceDecision {
        val start = paddedStart(speechStart, padSamples, samplesOrigin).coerceAtLeast(cursorSample.coerceAtMost(speechStart))
        val end = sliceEnd.coerceIn(start, bufferEndSample)
        return UtteranceDecision(
            action = action,
            sliceStartSample = start,
            sliceEndSample = end,
            nextCursorSample = end,
        )
    }

    /**
     * Monotone music and a boosted room tone stay in a narrow level band.
     * Conversational speech dips between syllables, so its min/max ratio is low.
     * Trailing silence is ignored; dips inside the run still count.
     */
    private fun isSteadyNonSpeech(levels: List<Float>): Boolean {
        val body = levels.dropLastWhile { it < voiceRms }
        if (body.size < 4) return false
        var minLevel = Float.POSITIVE_INFINITY
        var maxLevel = 0f
        for (level in body) {
            if (level < minLevel) minLevel = level
            if (level > maxLevel) maxLevel = level
        }
        if (maxLevel < voiceRms) return false
        return minLevel / maxLevel >= STEADY_LEVEL_RATIO
    }

    private fun speechOrSteady(
        speechAction: UtteranceDecision.Action,
        levels: List<Float>,
    ): UtteranceDecision.Action {
        return if (isSteadyNonSpeech(levels)) {
            UtteranceDecision.Action.SkipNonSpeech
        } else {
            speechAction
        }
    }

    private fun paddedStart(speechStart: Int, padSamples: Int, samplesOrigin: Int): Int {
        return (speechStart - padSamples).coerceAtLeast(samplesOrigin).coerceAtLeast(0)
    }

    private fun secondsToSamples(seconds: Float): Int = (seconds * sampleRate).toInt()

    private fun rms(samples: FloatArray, origin: Int, start: Int, end: Int): Float {
        val from = (start - origin).coerceIn(0, samples.size)
        val to = (end - origin).coerceIn(from, samples.size)
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) {
            val sample = samples[i]
            sum += sample * sample
        }
        return sqrt(sum / (to - from)).toFloat()
    }

    companion object {
        const val MAX_UTTERANCE_SECONDS = 5.0f
        const val MIN_UTTERANCE_SECONDS = 0.8f
        const val ENDPOINT_SILENCE_SECONDS = 0.5f
        const val PAD_SECONDS = 0.2f
        const val VOICE_RMS = 0.012f
        const val MIN_FLUSH_SECONDS = 0.4f
        /** min/max frame level above this means the run is a drone, not speech. */
        const val STEADY_LEVEL_RATIO = 0.62f
    }
}

data class UtteranceDecision(
    val action: Action,
    val sliceStartSample: Int,
    val sliceEndSample: Int,
    val nextCursorSample: Int,
) {
    enum class Action {
        Wait,
        Hypothesis,
        Endpoint,
        ForceCut,
        SkipShort,
        SkipNonSpeech,
        AdvanceSilence,
    }

    companion object {
        fun wait(cursor: Int) = UtteranceDecision(Action.Wait, cursor, cursor, cursor)
    }
}
