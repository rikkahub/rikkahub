package me.rerere.ai.runtime

import me.rerere.ai.runtime.contract.RuntimeLogSink

/**
 * Recording [RuntimeLogSink] for runtime unit tests — captures info/warn/error calls instead of
 * touching the platform logger, so the pure turn-loop tests run on the JVM. No-op-safe: tests that do
 * not assert on logs simply ignore the recorded lines.
 */
class RecordingLogSink : RuntimeLogSink {
    data class Line(val level: String, val tag: String, val msg: String, val throwable: Throwable?)

    val lines = mutableListOf<Line>()

    override fun info(tag: String, msg: String) {
        lines += Line("info", tag, msg, null)
    }

    override fun warn(tag: String, msg: String, throwable: Throwable?) {
        lines += Line("warn", tag, msg, throwable)
    }

    override fun error(tag: String, msg: String, throwable: Throwable?) {
        lines += Line("error", tag, msg, throwable)
    }
}
