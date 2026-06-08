package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentTelecomCallRegistryTest {
    @Test
    fun `replace disconnects previous active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        assertEquals(false, registry.hasActiveConnection())
        registry.replace(first)
        assertEquals(true, registry.hasActiveConnection())
        registry.replace(second)

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertEquals(true, registry.hasActiveConnection())
    }

    @Test
    fun `clear only removes the matching active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        registry.replace(first)
        registry.clear(second)
        registry.disconnectActive()

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertEquals(false, registry.hasActiveConnection())
    }

    @Test
    fun `disconnect active clears call before disconnect callback can reenter`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = FakeTelecomCall {
            registry.clear(it)
        }

        registry.replace(call)
        registry.disconnectActive()
        registry.disconnectActive()

        assertEquals(1, call.disconnectCalls)
        assertEquals(false, registry.hasActiveConnection())
    }

    private class FakeTelecomCall(
        private val onDisconnect: (FakeTelecomCall) -> Unit = {},
    ) : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
            onDisconnect(this)
        }
    }
}
