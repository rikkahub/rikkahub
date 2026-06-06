package me.rerere.common.android

// Issue #100: consolidate the scattered length-only / type-only redaction helpers
// into one central policy object so logging/diagnostic paths share a single source
// of truth. The two free functions below predate this object (issues #96-#99) and
// have many call sites (WebDav/S3 clients, search services); they are kept as thin
// delegating aliases so consolidation is behavior-preserving with zero churn at
// those call sites.
//
// DIVERGENCE FROM ISSUE #100's suggested API: the issue sketched redactText/truncate
// helpers that KEEP a masked/truncated copy of the body. We deliberately do NOT add
// those. The established invariant (see redactAndTruncate / redactDecodeError below)
// is stronger: emit length-only metadata or class-only identity, never body or
// message content. A field-name masker is fail-open — it cannot catch a secret nested
// in an array/number/unexpected key — so retaining a masked body is a regression
// against that invariant. The central policy consolidates the existing safe helpers
// and adds only safeExceptionMessage, the one named helper with a real un-migrated
// call site (the crash handler's persisted stack trace).
//
// Pure and framework-free (no android.util.Log, no I/O) so it is JVM-unit-testable.
object SensitiveLogPolicy {
    // Mirrors bodyMetadataForLog in the app module's RequestLoggingInterceptor: emit
    // length-only metadata, never body content. Remote error bodies can embed bucket
    // names/object keys/paths, so even a truncated prefix is unsafe; we surface only
    // the character count.
    fun redactAndTruncate(body: String?): String {
        if (body.isNullOrBlank()) return "<no body>"
        return "<redacted body: ${body.length} chars>"
    }

    // A decode failure's throwable message is NOT safe to log. kotlinx-serialization's
    // JsonDecodingException embeds a snippet of the offending JSON input ("... JSON
    // input: <snippet>"), so interpolating throwable.message re-leaks the very response
    // body the redaction policy exists to keep out of logs. Surface the exception class
    // only — enough to distinguish a parse failure from other errors, with no input.
    fun redactDecodeError(throwable: Throwable): String {
        return throwable::class.simpleName ?: "decode failed"
    }

    // An arbitrary throwable's message is unsafe for the same reason redactDecodeError
    // exists: a JsonDecodingException embeds the offending JSON input, an IOException can
    // embed a file path/config value, and any wrapped exception can carry a secret in its
    // message. The safe identity of an exception is its CLASS, never its message text.
    // Returns the fully-qualified class name (falling back to simple name) for triage, or
    // null when there is no throwable.
    fun safeExceptionMessage(t: Throwable?): String? {
        if (t == null) return null
        return t::class.qualifiedName ?: t::class.simpleName ?: "Throwable"
    }
}

// Delegating aliases — keep existing call sites (WebDav/S3 clients, search services)
// compiling unchanged. Behavior is identical to the consolidated policy members.
fun redactAndTruncate(body: String?): String = SensitiveLogPolicy.redactAndTruncate(body)

fun redactDecodeError(throwable: Throwable): String =
    SensitiveLogPolicy.redactDecodeError(throwable)
