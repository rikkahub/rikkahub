package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

sealed class BiometricResult {
    data class Success(val method: String) : BiometricResult()
    data class Error(val code: String) : BiometricResult()
}

class BiometricResultBuffer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<BiometricResult>>()

    fun register(requestId: String): CompletableDeferred<BiometricResult> {
        val deferred = CompletableDeferred<BiometricResult>()
        pending[requestId] = deferred
        return deferred
    }

    fun complete(requestId: String, result: BiometricResult) {
        pending.remove(requestId)?.complete(result)
    }
}
