package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceAgentCallServiceCleanupTest {
    @Test
    fun `stale end completion cannot clean up a newer call generation`() = runBlocking {
        var currentGeneration = 1L
        var foregroundStops = 0
        var selfStops = 0
        var endJobClears = 0
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            runVoiceAgentEndCleanupForGeneration(
                isCurrent = { currentGeneration == 1L },
                retireTelecomCall = {},
                endAndDrain = {
                    drainStarted.complete(Unit)
                    releaseDrain.await()
                },
                onCompleted = {},
                stopForeground = { foregroundStops += 1 },
                stopSelf = { selfStops += 1 },
                clearEndJob = { endJobClears += 1 },
            )
        }
        drainStarted.await()

        currentGeneration = 2L
        releaseDrain.complete(Unit)
        cleanup.await()

        assertEquals(0, foregroundStops)
        assertEquals(0, selfStops)
        assertEquals(0, endJobClears)
    }

    @Test
    fun `explicit end drains and completes foreground cleanup after Telecom retirement throws`() = runBlocking {
        val events = mutableListOf<String>()
        val retirementError = IllegalStateException("Telecom retirement failed")
        val registry = registryWithActiveThrowingCall(retirementError, events)

        val thrown = runCatching {
            runVoiceAgentSuspendCleanupStages(
                { events += "retire"; registry.disconnectActive() },
                { events += "endAndDrain" },
                { events += "stopForeground" },
                { events += "stopSelf" },
                { events += "clearEndJob" },
            )
        }.exceptionOrNull()

        assertSame(retirementError, thrown)
        assertEquals(
            listOf("retire", "disconnect", "endAndDrain", "stopForeground", "stopSelf", "clearEndJob"),
            events,
        )
        assertFalse(registry.hasActiveConnection())
    }

    @Test
    fun `failed start runs manager status and foreground cleanup after Telecom retirement throws`() {
        val events = mutableListOf<String>()
        val retirementError = IllegalStateException("Telecom retirement failed")
        val managerError = IllegalArgumentException("manager close failed")
        val registry = registryWithActiveThrowingCall(retirementError, events)

        val thrown = runCatching {
            runVoiceAgentCleanupStages(
                { events += "retire"; registry.disconnectActive() },
                { events += "managerClose"; throw managerError },
                { events += "status" },
                { events += "foreground" },
                { events += "stopForeground" },
                { events += "stopSelf" },
            )
        }.exceptionOrNull()

        assertSame(retirementError, thrown)
        assertEquals(listOf(managerError), thrown?.suppressed?.toList())
        assertEquals(
            listOf(
                "retire",
                "disconnect",
                "managerClose",
                "status",
                "foreground",
                "stopForeground",
                "stopSelf",
            ),
            events,
        )
        assertFalse(registry.hasActiveConnection())
    }

    @Test
    fun `destruction runs manager scope and superclass exactly once after Telecom retirement throws`() {
        val events = mutableListOf<String>()
        val retirementError = IllegalStateException("Telecom retirement failed")
        val superclassError = IllegalStateException("super destroy failed")
        val registry = registryWithActiveThrowingCall(retirementError, events)

        val thrown = runCatching {
            runVoiceAgentCleanupStages(
                { events += "retire"; registry.disconnectActive() },
                { events += "managerClose" },
                { events += "scopeCancel" },
                { events += "superDestroy"; throw superclassError },
            )
        }.exceptionOrNull()

        assertSame(retirementError, thrown)
        assertEquals(listOf(superclassError), thrown?.suppressed?.toList())
        assertEquals(
            listOf("retire", "disconnect", "managerClose", "scopeCancel", "superDestroy"),
            events,
        )
        assertFalse(registry.hasActiveConnection())
    }

    private fun registryWithActiveThrowingCall(
        retirementError: Throwable,
        events: MutableList<String>,
    ): VoiceAgentTelecomCallRegistry = VoiceAgentTelecomCallRegistry().also { registry ->
        val attempt = registry.beginAttempt()
        registry.activate(
            attempt,
            object : VoiceAgentTelecomCall {
                override fun disconnectFromApp() {
                    events += "disconnect"
                    throw retirementError
                }
            },
        )
        runBlocking { registry.awaitOutcome(attempt) }
    }
}
