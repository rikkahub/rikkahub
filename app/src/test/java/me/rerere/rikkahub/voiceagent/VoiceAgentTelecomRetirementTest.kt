package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceAgentTelecomRetirementTest {
    @Test
    fun `framework onDisconnect joins failed exact lease retry and later route retry succeeds`() = runBlocking {
        val firstFailure = IllegalStateException("framework disconnect failed")
        val secondFailure = IllegalArgumentException("route retry failed")
        val frameworkFailure = AtomicReference<Throwable?>(firstFailure)
        val callEndRequests = AtomicInteger()
        val setDisconnectedCalls = AtomicInteger()
        val destroyCalls = AtomicInteger()
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val frameworkRetryJoinStarted = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        lateinit var registeredCall: VoiceAgentTelecomCall
        val connection = VoiceAgentTelecomConnection(
            onCallEndRequested = { callEndRequests.incrementAndGet() },
            onRetiring = { registry.retiring(registeredCall) },
            setDisconnected = {
                val call = setDisconnectedCalls.incrementAndGet()
                val failure = frameworkFailure.get()
                if (call == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(1, TimeUnit.SECONDS)) {
                        "exact route retry was not released"
                    }
                }
                failure?.let { throw it }
            },
            destroy = { destroyCalls.incrementAndGet() },
            onRetired = { result -> registry.retired(registeredCall, result) },
        )
        val appDisconnectCalls = AtomicInteger()
        registeredCall = object : VoiceAgentTelecomCall {
            override fun disconnectFromApp() {
                appDisconnectCalls.incrementAndGet()
                connection.disconnectFromApp()
            }
        }
        assertEquals(true, registry.activate(attempt, registeredCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        assertSame(firstFailure, runCatching(connection::onDisconnect).exceptionOrNull())
        assertEquals(1, callEndRequests.get())
        assertEquals(1, setDisconnectedCalls.get())
        assertEquals(1, destroyCalls.get())
        assertFalse(lease.isUsable)

        assertSame(firstFailure, runCatching(connection::onDisconnect).exceptionOrNull())
        assertEquals(2, callEndRequests.get())
        assertEquals(1, setDisconnectedCalls.get())
        assertEquals(1, destroyCalls.get())

        assertSame(
            firstFailure,
            runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
        )
        frameworkFailure.set(secondFailure)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val retry = executor.submit<Throwable?> {
                runCatching(lease::retire).exceptionOrNull()
            }
            check(retryEntered.await(1, TimeUnit.SECONDS)) {
                "exact route retry did not start"
            }
            val frameworkJoin = executor.submit<Throwable?> {
                frameworkRetryJoinStarted.countDown()
                runCatching(connection::onDisconnect).exceptionOrNull()
            }
            check(frameworkRetryJoinStarted.await(1, TimeUnit.SECONDS)) {
                "framework retry join did not start"
            }
            assertEquals(
                true,
                runCatching { frameworkJoin.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            assertEquals(3, callEndRequests.get())
            assertEquals(2, setDisconnectedCalls.get())
            assertEquals(1, destroyCalls.get())
            assertFalse(lease.isUsable)

            releaseRetry.countDown()
            assertSame(secondFailure, retry.get(1, TimeUnit.SECONDS))
            assertSame(secondFailure, frameworkJoin.get(1, TimeUnit.SECONDS))
            assertEquals(1, appDisconnectCalls.get())
            assertEquals(2, setDisconnectedCalls.get())
            assertEquals(2, destroyCalls.get())
            assertFalse(lease.isUsable)

            assertSame(
                secondFailure,
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
            )
            frameworkFailure.set(null)
            lease.retire()
            assertEquals(2, appDisconnectCalls.get())
            assertEquals(3, setDisconnectedCalls.get())
            assertEquals(3, destroyCalls.get())
            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            val replacementDisconnects = AtomicInteger()
            val replacement = object : VoiceAgentTelecomCall {
                override fun disconnectFromApp() {
                    replacementDisconnects.incrementAndGet()
                }
            }
            assertEquals(true, registry.activate(replacementAttempt, replacement))
            assertEquals(0, replacementDisconnects.get())

            lease.retire()
            assertEquals(2, appDisconnectCalls.get())
            assertEquals(3, setDisconnectedCalls.get())
            assertEquals(3, destroyCalls.get())
            assertEquals(0, replacementDisconnects.get())
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `production callback ordering retains synchronous disconnect failure for exact retry`() = runBlocking {
        val firstFailure = IllegalStateException("framework disconnect failed")
        val disconnectFailure = AtomicReference<Throwable?>(firstFailure)
        val cleanupReached = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val joiningLeaseStarted = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls += 1
                val cleanupResult = runCatching {
                    runVoiceAgentCleanupStages(
                        { registry.retiring(this) },
                        { disconnectFailure.get()?.let { throw it } },
                        {
                            cleanupReached.countDown()
                            check(releaseCleanup.await(1, TimeUnit.SECONDS)) {
                                "production cleanup callback was not released"
                            }
                        },
                    )
                }
                runVoiceAgentCleanupStages(
                    { cleanupResult.getOrThrow() },
                    { registry.retired(this, cleanupResult) },
                )
            }
        }
        assertEquals(true, registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val ownerRetirement = executor.submit<Throwable?> {
                runCatching(lease::retire).exceptionOrNull()
            }
            check(cleanupReached.await(1, TimeUnit.SECONDS)) {
                "production cleanup did not reach the pre-clear stage"
            }
            val joinedRetirement = executor.submit<Throwable?> {
                joiningLeaseStarted.countDown()
                runCatching(lease::retire).exceptionOrNull()
            }
            check(joiningLeaseStarted.await(1, TimeUnit.SECONDS)) {
                "joining lease retirement did not start"
            }
            assertEquals(
                true,
                runCatching { joinedRetirement.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            releaseCleanup.countDown()

            assertSame(firstFailure, ownerRetirement.get(1, TimeUnit.SECONDS))
            assertSame(firstFailure, joinedRetirement.get(1, TimeUnit.SECONDS))
            assertEquals(1, call.disconnectCalls)
            assertFalse(lease.isUsable)

            assertSame(
                firstFailure,
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
            )
            disconnectFailure.set(null)

            lease.retire()
            assertEquals(2, call.disconnectCalls)
            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            val replacementCall = object : VoiceAgentTelecomCall {
                var disconnectCalls = 0

                override fun disconnectFromApp() {
                    disconnectCalls += 1
                }
            }
            assertEquals(true, registry.activate(replacementAttempt, replacementCall))
            assertEquals(0, replacementCall.disconnectCalls)

            lease.retire()
            assertEquals(2, call.disconnectCalls)
            assertEquals(0, replacementCall.disconnectCalls)
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `retirement is one shot and callback follows framework retirement`() {
        val events = mutableListOf<String>()
        val retirement = VoiceAgentTelecomRetirement<String>(
            onRetiring = { events += "retiring" },
            setDisconnected = { events += "setDisconnected:$it" },
            destroy = { events += "destroy" },
            onRetired = { _ -> events += "callback" },
        )

        retirement.retire("external")
        retirement.retire("app")

        assertEquals(listOf("retiring", "setDisconnected:external", "destroy", "callback"), events)
    }

    @Test
    fun `retirement callback runs after destroy when framework cleanup throws`() {
        val events = mutableListOf<String>()
        val retirement = VoiceAgentTelecomRetirement<Unit>(
            onRetiring = { events += "retiring" },
            setDisconnected = {
                events += "setDisconnected"
                error("framework failure")
            },
            destroy = { events += "destroy" },
            onRetired = { _ -> events += "callback" },
        )

        runCatching { retirement.retire(Unit) }

        assertEquals(listOf("retiring", "setDisconnected", "destroy", "callback"), events)
    }

    @Test
    fun `retirement preserves first cleanup failure and aggregates stages`() {
        val events = mutableListOf<String>()
        val retiringFailure = IllegalStateException("retiring failed")
        val disconnectFailure = IllegalArgumentException("disconnect failed")
        val destroyFailure = UnsupportedOperationException("destroy failed")
        val retiredFailure = AssertionError("retired failed")
        val retirement = VoiceAgentTelecomRetirement<Unit>(
            onRetiring = {
                events += "retiring"
                throw retiringFailure
            },
            setDisconnected = {
                events += "setDisconnected"
                throw disconnectFailure
            },
            destroy = {
                events += "destroy"
                throw destroyFailure
            },
            onRetired = { _ ->
                events += "retired"
                throw retiredFailure
            },
        )

        val first = runCatching { retirement.retire(Unit) }.exceptionOrNull()

        assertEquals(listOf("retiring", "setDisconnected", "destroy", "retired"), events)
        assertSame(retiringFailure, first)
        assertEquals(listOf(disconnectFailure, destroyFailure, retiredFailure), first?.suppressed?.toList())
    }

    @Test
    fun `external retirement during activation completes failure after one framework retirement`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val appRetirementRequests = AtomicInteger()
        val appRetirementRequested = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val frameworkRetirementEntered = CountDownLatch(1)
        val releaseFrameworkRetirement = CountDownLatch(1)
        lateinit var call: VoiceAgentTelecomCall
        val retirement = VoiceAgentTelecomRetirement<String>(
            onRetiring = {
                events += "retiring"
                registry.retiring(call)
            },
            setDisconnected = {
                events += "setDisconnected"
                frameworkRetirementEntered.countDown()
                releaseFrameworkRetirement.await()
            },
            destroy = { events += "destroy" },
            onRetired = { result ->
                events += "callback"
                registry.retired(call, result)
            },
        )
        call = object : VoiceAgentTelecomCall {
            override fun disconnectFromApp() {
                appRetirementRequests.incrementAndGet()
                appRetirementRequested.countDown()
                retirement.retryFromRoute("app")
            }
        }
        val outcome = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            registry.awaitOutcome(attempt).also { events += "outcome" }
        }
        val activation = thread {
            registry.activate(attempt, call) {
                callbackEntered.countDown()
                releaseCallback.await()
                events += "setActive"
            }
        }

        callbackEntered.await()
        val externalRetirement = thread {
            retirement.retire("external")
        }
        frameworkRetirementEntered.await()

        assertEquals(listOf("retiring", "setDisconnected"), events)
        assertFalse(outcome.isCompleted)

        releaseCallback.countDown()
        appRetirementRequested.await()
        assertFalse(outcome.isCompleted)
        assertEquals(1, appRetirementRequests.get())

        releaseFrameworkRetirement.countDown()
        externalRetirement.join()
        activation.join()
        val failed = outcome.await()

        assertEquals(VoiceAgentTelecomOutcome.Failed::class.java, failed.javaClass)
        assertEquals(1, appRetirementRequests.get())
        assertEquals(
            listOf("retiring", "setDisconnected", "setActive", "destroy", "callback", "outcome"),
            events,
        )
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }
}
