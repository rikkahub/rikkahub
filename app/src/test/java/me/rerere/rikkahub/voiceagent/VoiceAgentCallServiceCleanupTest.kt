package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceAgentCallServiceCleanupTest {
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
