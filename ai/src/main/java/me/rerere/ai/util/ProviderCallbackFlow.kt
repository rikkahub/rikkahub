package me.rerere.ai.util

import java.util.concurrent.ConcurrentHashMap
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
    private val callbackSenders = ConcurrentHashMap.newKeySet<Thread>()

    fun sendFromProviderCallback(value: T): ChannelResult<Unit> {
        val callbackThread = Thread.currentThread()
        callbackSenders += callbackThread
        return try {
            producerScope.trySendBlocking(value)
        } catch (_: InterruptedException) {
            // awaitProviderClose interrupts only active callback sends to break the
            // trySendBlocking/callbackFlow-close cycle. InterruptedException clears
            // the flag, so pooled HTTP callback threads are returned unpoisoned.
            producerScope.trySend(value)
        } finally {
            callbackSenders -= callbackThread
        }
    }

    suspend fun awaitProviderClose(cleanup: () -> Unit = {}) {
        producerScope.awaitClose {
            cancelBlockedSends()
            cleanup()
        }
    }

    fun cancelBlockedSends() {
        callbackSenders.forEach(Thread::interrupt)
    }
}
