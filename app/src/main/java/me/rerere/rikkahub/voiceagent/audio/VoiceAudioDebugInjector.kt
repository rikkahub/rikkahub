package me.rerere.rikkahub.voiceagent.audio

object VoiceAudioDebugInjector {
    const val ACTION_INJECT_PCM = "me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
    const val EXTRA_PATH = "path"
    const val EXTRA_CHUNK_BYTES = "chunk_bytes"
    const val EXTRA_CHUNK_DELAY_MS = "chunk_delay_ms"
    const val EXTRA_LEADING_SILENCE_MS = "leading_silence_ms"
    const val EXTRA_TRAILING_SILENCE_MS = "trailing_silence_ms"
    const val DEFAULT_CHUNK_BYTES = 3_200
    const val DEFAULT_CHUNK_DELAY_MS = 100L
    const val DEFAULT_LEADING_SILENCE_MS = 0L
    const val DEFAULT_TRAILING_SILENCE_MS = 0L

    private val lock = Any()
    private var activeCapture: CaptureRegistration? = null
    private var nextRegistrationId = 0L

    data class Result(
        val delivered: Boolean,
        val bytes: Int,
        val chunkCount: Int,
        val message: String,
    )

    fun registerCapture(onPcm16: (ByteArray) -> Unit): Registration {
        val registration = synchronized(lock) {
            nextRegistrationId += 1
            CaptureRegistration(id = nextRegistrationId, onPcm16 = onPcm16).also {
                activeCapture = it
            }
        }
        return Registration {
            synchronized(lock) {
                if (activeCapture === registration) {
                    activeCapture = null
                }
            }
        }
    }

    fun injectPcm16(
        pcm16: ByteArray,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
        chunkDelayMs: Long = DEFAULT_CHUNK_DELAY_MS,
        leadingSilenceMs: Long = DEFAULT_LEADING_SILENCE_MS,
        trailingSilenceMs: Long = DEFAULT_TRAILING_SILENCE_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        val capture = synchronized(lock) { activeCapture }
            ?: return Result(
                delivered = false,
                bytes = pcm16.size,
                chunkCount = 0,
                message = "No active Voice Agent capture session",
            )
        if (pcm16.isEmpty()) {
            return Result(
                delivered = false,
                bytes = 0,
                chunkCount = 0,
                message = "PCM file is empty",
            )
        }

        val normalizedPcm16 = pcm16.withPcm16Alignment()
        val preparedPcm16 = ByteArray(
            silenceBytes(leadingSilenceMs) + normalizedPcm16.size + silenceBytes(trailingSilenceMs),
        ).also { output ->
            normalizedPcm16.copyInto(output, destinationOffset = silenceBytes(leadingSilenceMs))
        }
        val safeChunkBytes = alignPcm16ByteCount(chunkBytes.takeIf { it > 0 } ?: DEFAULT_CHUNK_BYTES)
        var offset = 0
        var chunkCount = 0
        while (offset < preparedPcm16.size && synchronized(lock) { activeCapture === capture }) {
            val end = (offset + safeChunkBytes).coerceAtMost(preparedPcm16.size)
            capture.onPcm16(preparedPcm16.copyOfRange(offset, end))
            chunkCount += 1
            offset = end
            if (offset < preparedPcm16.size && chunkDelayMs > 0) {
                sleep(chunkDelayMs)
            }
        }

        return Result(
            delivered = chunkCount > 0,
            bytes = preparedPcm16.size,
            chunkCount = chunkCount,
            message = if (chunkCount > 0) "Injected PCM into active Voice Agent capture" else "Capture session ended before injection",
        )
    }

    internal fun clearForTest() {
        synchronized(lock) {
            activeCapture = null
            nextRegistrationId = 0L
        }
    }

    fun interface Registration {
        fun close()
    }

    private data class CaptureRegistration(
        val id: Long,
        val onPcm16: (ByteArray) -> Unit,
    )

    private fun ByteArray.withPcm16Alignment(): ByteArray =
        if (size % BYTES_PER_PCM16_SAMPLE == 0) {
            this
        } else {
            copyOf(size + 1)
        }

    private fun silenceBytes(durationMs: Long): Int {
        val clampedMs = durationMs.coerceIn(0L, MAX_SILENCE_MS)
        return (clampedMs * CAPTURE_SAMPLE_RATE * BYTES_PER_PCM16_SAMPLE / MILLIS_PER_SECOND).toInt()
    }

    private fun alignPcm16ByteCount(bytes: Int): Int {
        val minimum = bytes.coerceAtLeast(BYTES_PER_PCM16_SAMPLE)
        return if (minimum % BYTES_PER_PCM16_SAMPLE == 0) minimum else minimum - 1
    }

    private const val CAPTURE_SAMPLE_RATE = 16_000L
    private const val BYTES_PER_PCM16_SAMPLE = 2
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_SILENCE_MS = 10_000L
}
