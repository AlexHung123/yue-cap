package com.csc.yuecap.service

import com.csc.yuecap.model.AsrMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AsrModeSelectionTest {

    @Test
    fun fromStored_defaultsToRemoteSpark() {
        assertEquals(AsrMode.REMOTE_SPARK_A, AsrMode.fromStored(null))
        assertEquals(AsrMode.REMOTE_SPARK_A, AsrMode.fromStored(" "))
        assertEquals(AsrMode.LOCAL_SHERPA, AsrMode.fromStored("local_sherpa"))
    }

    @Test
    fun selectAsrEngine_usesRemoteClientForSparkMode() {
        val local = FakeAsrEngine()
        val remote = FakeAsrEngine()
        assertSame(remote, selectAsrEngine(AsrMode.REMOTE_SPARK_A, local, remote))
        assertSame(local, selectAsrEngine(AsrMode.LOCAL_SHERPA, local, remote))
    }

    private class FakeAsrEngine : AsrEngine {
        override suspend fun loadModel(modelPath: String): Boolean = true
        override suspend fun transcribe(
            audioSamples: FloatArray,
            numThreads: Int,
            language: String
        ): List<TranscriptionSegment> = emptyList()
        override val isLoaded: Boolean = true
        override fun release() = Unit
    }
}
