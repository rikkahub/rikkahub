package me.rerere.rikkahub.voiceagent.telemetry

class VoiceLatencyTelemetryCoordinator(
    private val traceContext: VoiceTraceContext,
    private val transport: String,
    private val observability: VoiceObservability,
    private val speechDetector: VoiceTelemetrySpeechDetector = VoiceTelemetrySpeechDetector(),
    private val playbackDetector: VoiceTelemetryPlaybackDetector = VoiceTelemetryPlaybackDetector(),
) {
    private val lock = Any()
    private var currentTurnEpoch = 0L
    private var speechEndOnsetNanos: Long? = null
    private var speechEndEpoch: Long? = null
    private var pendingPlaybackStartOnsetNanos: Long? = null
    private var pendingPlaybackEpoch: Long? = null
    private var interruptionOnsetNanos: Long? = null

    fun onCapturePcm16(pcm16: ByteArray, sampleRateHz: Int = 16000, channelCount: Int = 1) {
        synchronized(lock) {
            speechDetector.onPcm16Chunk(pcm16, sampleRateHz, channelCount) { event ->
                when (event) {
                    is SpeechDetectionEvent.SpeechStart -> onSpeechStart(event.onsetNanos)
                    is SpeechDetectionEvent.SpeechEnd -> onSpeechEnd(event.onsetNanos)
                    is SpeechDetectionEvent.SilenceCandidateCancelled -> onSilenceCandidateCancelled(event.resumedNanos)
                }
            }
        }
    }

    fun onPlaybackPcm16(pcm16: ByteArray, sampleRateHz: Int = 16000, channelCount: Int = 1) {
        synchronized(lock) {
            playbackDetector.onDecodedChunk(pcm16, sampleRateHz, channelCount) { event ->
                when (event) {
                    is PlaybackDetectionEvent.PlaybackStart -> onPlaybackStart(event.onsetNanos)
                    is PlaybackDetectionEvent.PlaybackStop -> onPlaybackStop(event.onsetNanos)
                }
            }
        }
    }

    fun onExplicitPlaybackDrain() {
        synchronized(lock) {
            playbackDetector.onExplicitDrain { event ->
                onPlaybackStop(event.onsetNanos)
            }
        }
    }

    fun onSpeechStart(onsetNanos: Long) {
        synchronized(lock) {
            currentTurnEpoch++
            speechEndOnsetNanos = null
            speechEndEpoch = null
            pendingPlaybackStartOnsetNanos = null
            pendingPlaybackEpoch = null
            interruptionOnsetNanos = onsetNanos
        }
    }

    fun onSilenceCandidateCancelled(resumedNanos: Long) {
        synchronized(lock) {
            pendingPlaybackStartOnsetNanos = null
            pendingPlaybackEpoch = null
        }
    }

    fun onSpeechEnd(onsetNanos: Long) {
        synchronized(lock) {
            speechEndOnsetNanos = onsetNanos
            speechEndEpoch = currentTurnEpoch
            checkFlushTtfa()
        }
    }

    fun onPlaybackStart(onsetNanos: Long) {
        synchronized(lock) {
            pendingPlaybackStartOnsetNanos = onsetNanos
            pendingPlaybackEpoch = currentTurnEpoch
            interruptionOnsetNanos = null
            checkFlushTtfa()
        }
    }

    fun onPlaybackStop(onsetNanos: Long) {
        synchronized(lock) {
            val interruptionOnset = interruptionOnsetNanos ?: return
            val cutoffMs = (onsetNanos - interruptionOnset) / 1_000_000
            interruptionOnsetNanos = null
            if (cutoffMs >= 0) {
                observability.recordEvent(
                    name = "voice.latency.cutoff",
                    trace = traceContext,
                    attributes = mapOf("cutoff_client_ms" to cutoffMs, "transport" to transport),
                )
            }
        }
    }

    private fun checkFlushTtfa() {
        val speechEnd = speechEndOnsetNanos ?: return
        val playbackStart = pendingPlaybackStartOnsetNanos ?: return
        if (speechEndEpoch != pendingPlaybackEpoch) return

        val ttfaMs = (playbackStart - speechEnd) / 1_000_000
        speechEndOnsetNanos = null
        pendingPlaybackStartOnsetNanos = null
        if (ttfaMs >= 0) {
            observability.recordEvent(
                name = "voice.latency.ttfa",
                trace = traceContext,
                attributes = mapOf("ttfa_client_ms" to ttfaMs, "transport" to transport),
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            currentTurnEpoch++
            speechEndOnsetNanos = null
            pendingPlaybackStartOnsetNanos = null
            interruptionOnsetNanos = null
            speechDetector.reset()
            playbackDetector.reset()
        }
    }
}
