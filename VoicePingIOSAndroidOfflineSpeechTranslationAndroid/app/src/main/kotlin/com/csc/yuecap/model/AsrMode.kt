package com.csc.yuecap.model

/** Where live microphone audio is transcribed. */
enum class AsrMode {
    /** On-device SenseVoice via sherpa-onnx. */
    LOCAL_SHERPA,

    /** Spark A SenseVoice over HTTP. */
    REMOTE_SPARK_A;

    companion object {
        fun fromStored(raw: String?): AsrMode {
            return when (raw?.trim()?.uppercase()) {
                LOCAL_SHERPA.name -> LOCAL_SHERPA
                else -> REMOTE_SPARK_A
            }
        }
    }
}
