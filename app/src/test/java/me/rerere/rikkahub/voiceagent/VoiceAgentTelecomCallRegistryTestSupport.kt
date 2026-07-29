package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

internal fun VoiceAgentTelecomAttemptStartResult.requireAllocatedAttemptId(): VoiceAgentTelecomAttemptId = when (this) {
    is VoiceAgentTelecomAttemptStartResult.Allocated -> attemptId
    is VoiceAgentTelecomAttemptStartResult.CleanupFailed -> throw error
}

internal fun VoiceAgentRouteResolution.requireResolvedLease(): VoiceAgentRouteLease = when (this) {
    is VoiceAgentRouteResolution.Resolved -> lease
    is VoiceAgentRouteResolution.Superseded -> error("Voice Agent route was superseded")
    is VoiceAgentRouteResolution.CleanupFailed -> throw error
}

internal suspend fun assertAttemptWasConsumed(
    registry: VoiceAgentTelecomCallRegistry,
    attemptId: VoiceAgentTelecomAttemptId,
) {
    val error = runCatching { registry.awaitOutcome(attemptId) }.exceptionOrNull()
    assertTrue(error is IllegalArgumentException)
}

internal fun finishWorker(
    worker: Thread,
    workerFailure: AtomicReference<Throwable>,
    description: String,
    primaryFailure: Throwable? = null,
) {
    var cleanupFailure = runCatching { worker.join(1_000) }.exceptionOrNull()
    if (worker.isAlive) {
        cleanupFailure = cleanupFailure.append(
            AssertionError("$description did not finish"),
        )
    }
    workerFailure.get()?.let { failure ->
        cleanupFailure = cleanupFailure.append(
            AssertionError("$description failed", failure),
        )
    }
    if (primaryFailure != null) {
        cleanupFailure?.let(primaryFailure::addSuppressed)
    } else {
        cleanupFailure?.let { throw it }
    }
}

internal fun throwWorkerFailure(
    workerFailure: AtomicReference<Throwable>,
    description: String,
) {
    workerFailure.get()?.let { failure ->
        throw AssertionError("$description failed", failure)
    }
}

internal fun awaitWaiting(thread: AtomicReference<Thread>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (thread.get()?.state != Thread.State.WAITING) {
        check(System.nanoTime() < deadline) {
            "begin caller did not wait for pre-lease retirement"
        }
        Thread.yield()
    }
}

internal fun awaitBlocked(thread: AtomicReference<Thread>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (thread.get()?.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) {
        check(System.nanoTime() < deadline) {
            "begin caller did not block on pre-lease retirement publication"
        }
        Thread.yield()
    }
}

internal fun assertAwakenedJoinerImmediatelyRetries(callbackCompletion: Boolean) {
    val firstFailure = IllegalStateException("first retirement failed")
    val cleanupFailure = AtomicReference<Throwable?>(firstFailure)
    val publicationEntered = CountDownLatch(1)
    val releasePublication = CountDownLatch(1)
    val immediateRetryStarted = CountDownLatch(1)
    val registry = VoiceAgentTelecomCallRegistry(
        probe = VoiceAgentTelecomRegistryProbe { event ->
            when (event) {
                VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishing -> {
                    publicationEntered.countDown()
                    check(releasePublication.await(5, TimeUnit.SECONDS)) {
                        "failed retirement publication was not released"
                    }
                }
                VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublished -> {
                    check(immediateRetryStarted.await(1, TimeUnit.SECONDS)) {
                        "awakened joiner did not start its immediate retry"
                    }
                }
                else -> Unit
            }
        },
    )
    val previous = registry.beginAttempt().requireAllocatedAttemptId()
    val call = FakeTelecomCall {
        cleanupFailure.get()?.let { throw it }
    }
    assertTrue(registry.activate(previous, call))
    if (callbackCompletion) registry.retiring(call)
    val executor = Executors.newFixedThreadPool(2)

    try {
        val owner = executor.submit<Throwable?> {
            runCatching {
                if (callbackCompletion) {
                    registry.retired(call, Result.failure(firstFailure))
                } else {
                    registry.beginAttempt().requireAllocatedAttemptId()
                }
            }.exceptionOrNull()
        }
        check(publicationEntered.await(1, TimeUnit.SECONDS)) {
            "failed retirement publication did not start"
        }
        val joinerThread = AtomicReference<Thread>()
        val joiner = executor.submit<ImmediateRetryObservation> {
            joinerThread.set(Thread.currentThread())
            val joinedFailure = runCatching {
                registry.beginAttempt().requireAllocatedAttemptId()
            }.exceptionOrNull()
            cleanupFailure.set(null)
            immediateRetryStarted.countDown()
            val retryResult = runCatching { registry.beginAttempt().requireAllocatedAttemptId() }
            ImmediateRetryObservation(joinedFailure, retryResult)
        }

        awaitBlocked(joinerThread)
        releasePublication.countDown()

        val observation = joiner.get(1, TimeUnit.SECONDS)
        assertSame(firstFailure, observation.joinedFailure)
        assertEquals(previous.value + 1, observation.retryResult.getOrThrow().value)
        assertEquals(if (callbackCompletion) 1 else 2, call.disconnectCalls)
        assertEquals(if (callbackCompletion) null else firstFailure, owner.get(1, TimeUnit.SECONDS))
    } finally {
        releasePublication.countDown()
        immediateRetryStarted.countDown()
        executor.shutdownNow()
    }
}

internal data class ImmediateRetryObservation(
    val joinedFailure: Throwable?,
    val retryResult: Result<VoiceAgentTelecomAttemptId>,
)

internal fun Throwable?.append(additional: Throwable): Throwable =
    this?.also { it.addSuppressed(additional) } ?: additional

internal class FakeTelecomCall(
    private val onDisconnect: (FakeTelecomCall) -> Unit = {},
) : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        onDisconnect(this)
    }
}

internal class CallbackFaithfulTelecomCall(
    private val registry: VoiceAgentTelecomCallRegistry,
) : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        registry.retiring(this)
        registry.retired(this, Result.success(Unit))
    }
}
