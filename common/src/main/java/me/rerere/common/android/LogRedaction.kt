package me.rerere.common.android

// Issue #99: TTS/search/sync clients previously logged raw request/response/error
// bodies, leaking TTS input text, search queries/results, and S3/WebDAV bucket
// names, object keys, and paths into the in-app log buffer and logcat.
//
// This is the single source of truth for the "redact-and-truncate" policy applied
// to retained body logs outside the chat path. It mirrors bodyMetadataForLog in the
// app module's RequestLoggingInterceptor: emit length-only metadata, never body
// content. Remote error bodies can embed bucket names/object keys/paths, so even a
// truncated prefix is unsafe; we surface only the character count.
//
// Pure and framework-free (no android.util.Log, no I/O) so it is JVM-unit-testable.
fun redactAndTruncate(body: String?): String {
    if (body.isNullOrBlank()) return "<no body>"
    return "<redacted body: ${body.length} chars>"
}

// Issue #99: a decode failure's throwable message is NOT safe to log. kotlinx-
// serialization's JsonDecodingException embeds a snippet of the offending JSON input
// ("... JSON input: <snippet>"), so interpolating throwable.message re-leaks the very
// response body the redaction policy exists to keep out of logs. Surface the exception
// class only — enough to distinguish a parse failure from other errors, with no input.
fun redactDecodeError(throwable: Throwable): String {
    return throwable::class.simpleName ?: "decode failed"
}
