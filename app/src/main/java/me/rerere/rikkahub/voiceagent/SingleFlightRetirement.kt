package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch

internal class SingleFlightRetirement {
    private val lock = Any()
    private val completed = CountDownLatch(1)
    private var ownerThread: Thread? = null

    fun retire(block: () -> Unit) {
        val ownsRetirement = synchronized(lock) {
            if (ownerThread != null) {
                false
            } else {
                ownerThread = Thread.currentThread()
                true
            }
        }
        if (!ownsRetirement) {
            if (ownerThread !== Thread.currentThread()) {
                awaitCompletionUninterruptibly()
            }
            return
        }

        try {
            block()
        } finally {
            completed.countDown()
        }
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
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}
