package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch

internal class RetirementBarrier {
    private val lock = Any()
    private val completed = CountDownLatch(1)
    private var ownerThread: Thread? = null
    private var result: Result<Unit>? = null

    fun retire(block: () -> Unit) {
        val current = Thread.currentThread()
        val owns = synchronized(lock) {
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
        val completedResult = runCatching(block)
        synchronized(lock) { result = completedResult }
        completed.countDown()
        completedResult.getOrThrow()
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
}
