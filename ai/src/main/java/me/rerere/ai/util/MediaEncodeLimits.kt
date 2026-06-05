package me.rerere.ai.util

/**
 * Thrown when a media file exceeds the configured base64-inline size limit.
 * Distinct from generic [Exception] so callers can surface a clear message and
 * tests can assert on the limit being enforced.
 */
internal class MediaTooLargeException(message: String) : Exception(message)

/**
 * Size caps for the base64-inline media paths in [FileEncoder].
 *
 * Root cause this guards: [UIMessagePart.Video.encodeBase64] and
 * [UIMessagePart.Audio.encodeBase64] stream a raw, arbitrary-size source file fully
 * into an in-memory base64 [String] (`File.encodeToBase64Streaming`, despite the name,
 * keeps the whole expanded payload in memory). With no upper bound, a large video/audio
 * file inflates ~1.37x in memory and can OOM the app. The JPEG/PNG/WebP image path bounds
 * itself via dimension/pixel caps in `compressAndEncode`, but the GIF branch there is encoded
 * as-is (to preserve animation) and bypasses those caps, so it — like video/audio — needs an
 * explicit byte cap ([MAX_IMAGE_BYTES]) before the full in-memory base64.
 *
 * The cap mirrors the in-repo upload precedent (MAX_UPLOAD_FILE_SIZE_BYTES = 20 MB) and
 * is at or below the inline-media limits of the providers (Gemini/Anthropic/OpenAI),
 * so anything larger would be non-functional inline regardless.
 *
 * [checkMediaSizeWithinLimit] is pure (operates on a [Long] size, no File/Android types)
 * so the size invariant is unit-testable under `testDebugUnitTest` without a device.
 */
internal object MediaEncodeLimits {
    const val MAX_VIDEO_BYTES = 20L * 1024 * 1024
    const val MAX_AUDIO_BYTES = 20L * 1024 * 1024

    // GIF is encoded as-is (to preserve animation) and bypasses the dimension/pixel caps that
    // bound the JPEG/PNG/WebP path, so it needs its own byte cap before the full in-memory base64.
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    /**
     * Rejects [sizeBytes] above [maxBytes] BEFORE the caller allocates the base64 string.
     * [label] names the media kind for the error message.
     */
    fun checkMediaSizeWithinLimit(sizeBytes: Long, maxBytes: Long, label: String) {
        if (sizeBytes > maxBytes) {
            throw MediaTooLargeException(
                "$label too large: $sizeBytes bytes exceeds limit ${maxBytes / (1024 * 1024)} MB"
            )
        }
    }
}

/**
 * Re-throws a [MediaTooLargeException] so the oversized-media rejection reaches the send path
 * (and the user) instead of being silently dropped by a subsequent `getOrNull()`. Any other
 * encode failure is left in the [Result] for the caller to handle as before.
 *
 * Without this, a provider that consumes `encodeBase64(...).getOrNull()` would omit the oversized
 * part and proceed with a request that is missing the media and surfaces no error — turning the
 * size guard into an invisible no-op.
 */
internal fun <T> Result<T>.rethrowIfMediaTooLarge(): Result<T> =
    onFailure { if (it is MediaTooLargeException) throw it }
