package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceAgentTelecomRetirementTest {
    @Test
    fun `retirement is one shot and callback follows framework retirement`() {
        val events = mutableListOf<String>()
        val retirement = VoiceAgentTelecomRetirement<String>(
            onRetiring = { events += "retiring" },
            setDisconnected = { events += "setDisconnected:$it" },
            destroy = { events += "destroy" },
            onRetired = { events += "callback" },
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
            onRetired = { events += "callback" },
        )

        runCatching { retirement.retire(Unit) }
        runCatching { retirement.retire(Unit) }

        assertEquals(listOf("retiring", "setDisconnected", "destroy", "callback"), events)
    }

    @Test
    fun `external retirement during activation completes failure after one framework retirement`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
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
            onRetired = {
                events += "callback"
                registry.clear(call)
            },
        )
        call = object : VoiceAgentTelecomCall {
            override fun disconnectFromApp() {
                appRetirementRequests.incrementAndGet()
                appRetirementRequested.countDown()
                retirement.retire("app")
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
