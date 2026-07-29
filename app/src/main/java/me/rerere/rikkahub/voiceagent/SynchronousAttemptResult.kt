package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch

internal class SynchronousAttemptResult {
    private val completed = CountDownLatch(1)
    private var result: Result<Unit>? = null

    fun publish(value: Result<Unit>) {
        check(result == null) { "Synchronous attempt result already completed" }
        result = value
        completed.countDown()
    }

    fun awaitResult(): Result<Unit> {
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return requireNotNull(result)
    }
}
