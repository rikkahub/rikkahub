package me.rerere.rikkahub.voiceagent.audio

internal object VoicePcm16SinkLifecycle {
    sealed interface StartOutcome {
        data class Started(val sink: VoicePcm16Sink) : StartOutcome
        data class Failed(val message: String) : StartOutcome
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
                    stopAndReleaseSafely(sink)
                    StartOutcome.Failed(result.message)
                }
            }
        } catch (e: Exception) {
            stopAndReleaseSafely(sink)
            StartOutcome.Failed(e.failureMessage())
        }
    }

    fun writeFully(sink: VoicePcm16Sink, pcm16: ByteArray): VoicePcm16Sink.WriteResult {
        return try {
            sink.writeFully(pcm16)
        } catch (e: Exception) {
            VoicePcm16Sink.WriteResult.Failed(e.failureMessage())
        }
    }

    fun pauseAndFlushSafely(sink: VoicePcm16Sink?) {
        try {
            sink?.pauseAndFlush()
        } catch (_: Exception) {
        }
    }

    fun stopAndReleaseSafely(sink: VoicePcm16Sink?) {
        try {
            sink?.stopAndRelease()
        } catch (_: Exception) {
        }
    }

    fun stopAndReleaseDistinct(first: VoicePcm16Sink?, second: VoicePcm16Sink?) {
        stopAndReleaseSafely(first)
        if (first !== second) {
            stopAndReleaseSafely(second)
        }
    }

    private fun Exception.failureMessage(): String {
        return message ?: javaClass.simpleName
    }
}
