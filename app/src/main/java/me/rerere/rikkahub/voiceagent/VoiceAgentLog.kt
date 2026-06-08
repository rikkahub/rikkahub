package me.rerere.rikkahub.voiceagent

import android.util.Log

internal object VoiceAgentLog {
    fun d(tag: String, message: String) {
        write { Log.d(tag, message) }
    }

    fun w(tag: String, message: String) {
        write { Log.w(tag, message) }
    }

    private inline fun write(log: () -> Unit) {
        runCatching(log)
    }
}
