package me.rerere.ai.util

import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

internal const val PROVIDER_CALLBACK_BUFFER_CAPACITY = 64

internal fun <T> losslessProviderCallbackFlow(
    block: suspend ProviderCallbackScope<T>.() -> Unit,
): Flow<T> = callbackFlow {
    val callbackScope = ProviderCallbackScope(this)
    try {
        callbackScope.block()
    } finally {
        callbackScope.cancelBlockedSends()
    }
}.buffer(PROVIDER_CALLBACK_BUFFER_CAPACITY)

internal class ProviderCallbackScope<T>(
    private val producerScope: ProducerScope<T>,
) : ProducerScope<T> by producerScope {
    private val callbackSenders = ProviderCallbackSenderRegistry()

    fun sendFromProviderCallback(value: T): ChannelResult<Unit> {
        return callbackSenders.withRegisteredSender {
            try {
                producerScope.trySendBlocking(value)
            } catch (_: InterruptedException) {
                // awaitProviderClose interrupts only registered callback sends to
                // break the trySendBlocking/callbackFlow-close cycle.
                producerScope.trySend(value)
            }
        }
    }

    suspend fun awaitProviderClose(cleanup: () -> Unit = {}) {
        producerScope.awaitClose {
            cancelBlockedSends()
            cleanup()
        }
    }

    fun cancelBlockedSends() {
        callbackSenders.interruptRegisteredSenders()
    }
}

internal class ProviderCallbackSenderRegistry {
    private val lock = Any()
    private val senders = mutableSetOf<Thread>()
    // Normal deregistration must not clear an interrupt unless cleanup issued one.
    private val interruptedSenders = mutableSetOf<Thread>()

    fun <T> withRegisteredSender(block: () -> T): T {
        val sender = Thread.currentThread()
        synchronized(lock) {
            senders += sender
        }
        try {
            return block()
        } finally {
            val clearCleanupInterrupt = synchronized(lock) {
                senders -= sender
                interruptedSenders.remove(sender)
            }
            if (clearCleanupInterrupt) {
                Thread.interrupted()
            }
        }
    }

    fun interruptRegisteredSenders() {
        synchronized(lock) {
            senders.forEach { sender ->
                interruptedSenders += sender
                sender.interrupt()
            }
        }
    }
}
