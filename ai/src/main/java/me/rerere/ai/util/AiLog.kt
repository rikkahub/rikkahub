package me.rerere.ai.util

import android.util.Log

/**
 * Central AI logging policy: metadata only, never payloads.
 *
 * Provider request/response bodies and SSE event data carry system prompts, memories,
 * user content, MCP tool names/arguments, and base64 media. logcat is frequently copied
 * during troubleshooting, so any of that reaching the log is a data-leak (issue #96).
 *
 * NON-GOAL: this object must never accept or interpolate a request/response body, an SSE
 * `data` frame, message/instruction text, tool arguments, or base64 image/audio. It logs
 * only safe metadata (provider name, model id, event type/id, error type/code). If you
 * find yourself wanting to pass a body string here, the answer is: don't.
 */
object AiLog {
    /** Logs that a request is being sent. No body, no headers, no auth. */
    fun request(tag: String, provider: String, model: String?, streaming: Boolean) {
        Log.d(tag, "request provider=$provider model=$model streaming=$streaming")
    }

    /** Logs a streaming event's shape only — never its `data` payload. */
    fun event(tag: String, type: String?, id: String?) {
        Log.d(tag, "event type=$type id=$id")
    }

    /**
     * Logs a provider failure by type and HTTP code — never the response body and never the
     * exception message. okhttp-sse funnels a throwable raised inside onEvent (e.g. the
     * kotlinx-serialization parse of the raw SSE `data`) into onFailure as `t`; its `.message`
     * embeds a snippet of the offending JSON, which is exactly the payload #96 forbids. The
     * exception class (type=) is the only safe identity to log here.
     */
    fun failure(tag: String, error: Throwable?, responseCode: Int?) {
        Log.w(
            tag,
            "provider failure code=$responseCode type=${error?.javaClass?.simpleName}",
        )
    }

    /** Logs that an error body failed to parse — never the raw body that failed. */
    fun parseFailure(tag: String, error: Throwable?) {
        Log.w(tag, "failed to parse error body type=${error?.javaClass?.simpleName}")
    }
}
