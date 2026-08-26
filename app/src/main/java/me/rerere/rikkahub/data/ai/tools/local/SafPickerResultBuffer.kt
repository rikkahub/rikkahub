package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

sealed class SafPickerResult {
    data class Granted(val contentUri: String) : SafPickerResult()
    data object Cancelled : SafPickerResult()
    data class Error(val message: String) : SafPickerResult()
}

class SafPickerResultBuffer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SafPickerResult>>()

    fun register(id: String): CompletableDeferred<SafPickerResult> =
        CompletableDeferred<SafPickerResult>().also { pending[id] = it }

    fun complete(id: String, result: SafPickerResult) {
        pending.remove(id)?.complete(result)
    }
}
