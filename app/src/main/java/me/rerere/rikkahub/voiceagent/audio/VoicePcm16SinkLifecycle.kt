package me.rerere.rikkahub.voiceagent.audio

internal object VoicePcm16SinkLifecycle {
    sealed interface StartOutcome {
        data class Started(val sink: VoicePcm16Sink) : StartOutcome
        data class Failed(
            val message: String,
            val sinkRequiringRetirement: VoicePcm16Sink? = null,
        ) : StartOutcome
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
                    retireAfterStartFailure(sink, result.message)
                }
            }
        } catch (e: Exception) {
            retireAfterStartFailure(sink, e.failureMessage())
        }
    }

    fun writeFully(sink: VoicePcm16Sink, pcm16: ByteArray): VoicePcm16Sink.WriteResult {
        return try {
            sink.writeFully(pcm16)
        } catch (e: Exception) {
            VoicePcm16Sink.WriteResult.Failed(e.failureMessage())
        }
    }

    fun awaitDrained(sink: VoicePcm16Sink): VoicePcm16Sink.DrainResult {
        return try {
            sink.awaitDrained()
        } catch (e: Exception) {
            VoicePcm16Sink.DrainResult.Failed(e.failureMessage())
        }
    }

    fun retireForSafety(sink: VoicePcm16Sink?): Boolean {
        if (sink == null) return true
        return try {
            sink.pauseAndFlush()
            true
        } catch (_: Exception) {
            try {
                sink.stopAndRelease()
                true
            } catch (_: Exception) {
                false
            }
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

    private fun retireAfterStartFailure(sink: VoicePcm16Sink, message: String): StartOutcome.Failed {
        return if (retireForSafety(sink)) {
            stopAndReleaseSafely(sink)
            StartOutcome.Failed(message)
        } else {
            StartOutcome.Failed(message, sinkRequiringRetirement = sink)
        }
    }

    private fun Exception.failureMessage(): String {
        return message ?: javaClass.simpleName
    }
}
