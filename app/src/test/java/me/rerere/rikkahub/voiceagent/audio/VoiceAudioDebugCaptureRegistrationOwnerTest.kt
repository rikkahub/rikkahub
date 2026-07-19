package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioDebugCaptureRegistrationOwnerTest {
    @Test
    fun `delayed stale second stage publish closes stale registration without displacing current`() {
        val owner = VoiceAudioDebugCaptureRegistrationOwner<Any, Any, FakeRegistration>(
            closeRegistration = FakeRegistration::close,
        )
        val currentToken = Any()
        val currentRecorder = Any()
        val currentRegistration = FakeRegistration()
        val staleRegistration = FakeRegistration()
        assertTrue(owner.publish(currentToken, currentRecorder, currentRegistration) { true })

        val stalePublished = owner.publish(Any(), Any(), staleRegistration) { false }

        assertFalse(stalePublished)
        assertEquals(1, staleRegistration.closeCalls)
        assertEquals(0, currentRegistration.closeCalls)
        assertTrue(owner.unregister(currentToken, currentRecorder))
        assertEquals(1, currentRegistration.closeCalls)
    }

    @Test
    fun `termination failure escapes while exact stale registration closes and newer stays active`() {
        val owner = VoiceAudioDebugCaptureRegistrationOwner<Any, Any, FakeRegistration>(
            closeRegistration = FakeRegistration::close,
        )
        val staleToken = Any()
        val staleRecorder = Any()
        val staleRegistration = FakeRegistration()
        val currentToken = Any()
        val currentRecorder = Any()
        val currentRegistration = FakeRegistration()
        val terminationFailure = IllegalStateException("termination failed")
        assertTrue(owner.publish(staleToken, staleRecorder, staleRegistration) { true })

        val thrown = runCatching {
            owner.deliver(
                token = staleToken,
                recorder = staleRecorder,
                buffer = byteArrayOf(1, 2),
                isCurrent = { true },
                onPcm16 = { throw IllegalArgumentException("callback failed") },
                terminate = {
                    assertTrue(owner.publish(currentToken, currentRecorder, currentRegistration) { true })
                    throw terminationFailure
                },
                onFailure = {},
            )
        }.exceptionOrNull()

        assertSame(terminationFailure, thrown)
        assertEquals(1, staleRegistration.closeCalls)
        assertEquals(0, currentRegistration.closeCalls)
        assertFalse(owner.unregister(staleToken, staleRecorder))
        assertTrue(owner.unregister(currentToken, currentRecorder))
        assertEquals(1, currentRegistration.closeCalls)
    }

    @Test
    fun `callback failure terminates and unregisters exact capture`() {
        val owner = VoiceAudioDebugCaptureRegistrationOwner<Any, Any, FakeRegistration>(
            closeRegistration = FakeRegistration::close,
        )
        val token = Any()
        val recorder = Any()
        val registration = FakeRegistration()
        val callbackFailure = IllegalStateException("callback failed")
        var terminateCalls = 0
        var observedFailure: Exception? = null
        owner.publish(token, recorder, registration) { true }

        owner.deliver(
            token = token,
            recorder = recorder,
            buffer = byteArrayOf(1, 2),
            isCurrent = { true },
            onPcm16 = { throw callbackFailure },
            terminate = {
                terminateCalls += 1
                true
            },
            onFailure = { observedFailure = it },
        )

        assertEquals(1, terminateCalls)
        assertSame(callbackFailure, observedFailure)
        assertEquals(1, registration.closeCalls)
        assertFalse(owner.unregister(token, recorder))
    }

    @Test
    fun `stale owner unregister cannot close newer registration`() {
        VoiceAudioDebugInjector.clearForTest()
        val owner = VoiceAudioDebugCaptureRegistrationOwner<
            Any,
            Any,
            VoiceAudioDebugInjector.Registration,
        >(
            closeRegistration = VoiceAudioDebugInjector.Registration::close,
        )
        val staleToken = Any()
        val staleRecorder = Any()
        val currentToken = Any()
        val currentRecorder = Any()
        val staleChunks = mutableListOf<ByteArray>()
        val currentChunks = mutableListOf<ByteArray>()
        val staleRegistration = VoiceAudioDebugInjector.registerCapture(staleChunks::add)
        assertTrue(owner.publish(staleToken, staleRecorder, staleRegistration) { true })
        val currentRegistration = VoiceAudioDebugInjector.registerCapture(currentChunks::add)
        assertTrue(owner.publish(currentToken, currentRecorder, currentRegistration) { true })

        assertFalse(owner.unregister(staleToken, staleRecorder))

        val injection = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )
        assertTrue(injection.delivered)
        assertEquals(emptyList<ByteArray>(), staleChunks)
        assertEquals(listOf(byteArrayOf(1, 2).toList()), currentChunks.map(ByteArray::toList))
        assertTrue(owner.unregister(currentToken, currentRecorder))
        assertFalse(
            VoiceAudioDebugInjector.injectPcm16(
                pcm16 = byteArrayOf(3, 4),
                chunkBytes = 2,
                chunkDelayMs = 0L,
            ).delivered,
        )
        VoiceAudioDebugInjector.clearForTest()
    }

    private class FakeRegistration {
        var closeCalls = 0
            private set

        fun close() {
            closeCalls += 1
        }
    }
}
