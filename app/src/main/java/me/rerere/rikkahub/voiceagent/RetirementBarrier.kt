package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch

internal class RetirementBarrier {
    private val lock = Any()
    private val completed = CountDownLatch(1)
    private var started = false
    private var ownerThread: Thread? = null
    private var result: Result<Unit>? = null

    fun retire(block: () -> Unit) = retire(afterResultPublished = {}, block = block)

    fun retire(
        afterResultPublished: (Result<Unit>) -> Unit,
        block: () -> Unit,
    ) {
        val current = Thread.currentThread()
        val owns = synchronized(lock) {
            started = true
            result?.let { it.getOrThrow(); return }
            if (ownerThread === current) return
            if (ownerThread == null) {
                ownerThread = current
                true
            } else {
                false
            }
        }
        if (!owns) {
            awaitCompletionUninterruptibly()
            synchronized(lock) { requireNotNull(result) }.getOrThrow()
            return
        }
        val cleanupResult = runCatching(block)
        val completedResult = synchronized(lock) {
            result = cleanupResult
            val hookFailure = runCatching { afterResultPublished(cleanupResult) }.exceptionOrNull()
            cleanupResult.withLaterFailure(hookFailure).also { result = it }
        }
        completed.countDown()
        completedResult.getOrThrow()
    }

    fun begin() {
        synchronized(lock) {
            started = true
        }
    }

    fun replayIfStarted(): Boolean {
        val current = Thread.currentThread()
        synchronized(lock) {
            result?.let { it.getOrThrow(); return true }
            if (!started) return false
            if (ownerThread === current) return true
        }
        awaitCompletionUninterruptibly()
        synchronized(lock) { requireNotNull(result) }.getOrThrow()
        return true
    }

    private fun awaitCompletionUninterruptibly() {
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
    }

    private fun Result<Unit>.withLaterFailure(failure: Throwable?): Result<Unit> {
        if (failure == null) return this
        val primary = exceptionOrNull() ?: return Result.failure(failure)
        if (primary !== failure) primary.addSuppressed(failure)
        return this
    }
}
