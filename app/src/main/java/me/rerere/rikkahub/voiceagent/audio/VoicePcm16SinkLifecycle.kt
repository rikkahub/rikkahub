package me.rerere.rikkahub.voiceagent.audio

internal object VoicePcm16SinkLifecycle {
    sealed interface StartOutcome {
        data class Started(val sink: VoicePcm16Sink) : StartOutcome
        data class Failed(val message: String) : StartOutcome
    }

    sealed interface WriteOutcome {
        data class Written(val bytes: Int) : WriteOutcome
        data class Failed(val message: String) : WriteOutcome
        data object Interrupted : WriteOutcome
    }

    fun createStarted(
        createSink: () -> VoicePcm16Sink?,
        nullSinkMessage: String,
    ): StartOutcome {
        val sink = try {
            createSink()
        } catch (e: Exception) {
            return StartOutcome.Failed(e.failureMessage())
        } ?: return StartOutcome.Failed(nullSinkMessage)

        return try {
            when (val result = sink.start()) {
                VoicePcm16Sink.StartResult.Started -> StartOutcome.Started(sink)
                is VoicePcm16Sink.StartResult.Failed -> {
                    stopAndRelease(sink)
                    StartOutcome.Failed(result.message)
                }
            }
        } catch (e: Exception) {
            stopAndRelease(sink)
            StartOutcome.Failed(e.failureMessage())
        }
    }

    fun writeFully(sink: VoicePcm16Sink, pcm16: ByteArray): WriteOutcome {
        return try {
            when (val result = sink.writeFully(pcm16)) {
                is VoicePcm16Sink.WriteResult.Written -> WriteOutcome.Written(result.bytes)
                is VoicePcm16Sink.WriteResult.Failed -> WriteOutcome.Failed(result.message)
                VoicePcm16Sink.WriteResult.Interrupted -> WriteOutcome.Interrupted
            }
        } catch (e: Exception) {
            WriteOutcome.Failed(e.failureMessage())
        }
    }

    fun pauseAndFlush(sink: VoicePcm16Sink?) {
        try {
            sink?.pauseAndFlush()
        } catch (_: Exception) {
        }
    }

    fun stopAndRelease(sink: VoicePcm16Sink?) {
        try {
            sink?.stopAndRelease()
        } catch (_: Exception) {
        }
    }

    fun stopAndReleaseDistinct(first: VoicePcm16Sink?, second: VoicePcm16Sink?) {
        stopAndRelease(first)
        if (first !== second) {
            stopAndRelease(second)
        }
    }

    private fun Exception.failureMessage(): String {
        return message ?: javaClass.simpleName
    }
}
