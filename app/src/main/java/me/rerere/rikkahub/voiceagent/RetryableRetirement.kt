package me.rerere.rikkahub.voiceagent

internal class RetryableRetirement {
    private val lock = Any()
    private var activeAttempt: Attempt? = null
    private var retired = false

    fun retire(block: () -> Unit) {
        val currentThread = Thread.currentThread()
        var ownsAttempt = false
        val attempt = synchronized(lock) {
            if (retired) return
            activeAttempt?.also { currentAttempt ->
                if (currentAttempt.ownerThread === currentThread) return
            } ?: Attempt(currentThread).also { newAttempt ->
                activeAttempt = newAttempt
                ownsAttempt = true
            }
        }

        val result = if (ownsAttempt) {
            runCatching(block).also { attemptResult ->
                synchronized(lock) {
                    attempt.result.publish(attemptResult)
                    if (attemptResult.isSuccess) retired = true
                    if (activeAttempt === attempt) activeAttempt = null
                }
            }
        } else {
            attempt.result.awaitResult()
        }
        result.getOrThrow()
    }

    private class Attempt(
        val ownerThread: Thread,
    ) {
        val result = SynchronousAttemptResult()
    }
}
