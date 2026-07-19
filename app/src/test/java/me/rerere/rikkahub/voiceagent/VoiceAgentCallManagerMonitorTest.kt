package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerMonitorTest {
    @Test
    fun `blocked set muted does not block status update or another command snapshot`() = runMonitorTest {
        val releaseMuted = CountDownLatch(1)
        val session = BlockingMutedManagedVoiceCallSession(releaseMuted)
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), CountingTelecomLease().lease, this)

        val muted = async(Dispatchers.Default) { manager.setMuted(true) }
        assertTrue(session.setMutedEntered.await(1, TimeUnit.SECONDS))
        try {
            manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
            manager.reconnect()

            assertEquals(VoiceCallStatus.BackgroundCapable, manager.state.value.call)
            assertEquals(1, session.reconnectCalls.get())
            assertFalse(muted.isCompleted)
        } finally {
            releaseMuted.countDown()
        }
        muted.await()
    }

    @Test
    fun `reentrant session start callback can update manager and join before publication`() = runMonitorTest {
        val session = ReentrantStartManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
        session.manager = manager
        val conversationId = Uuid.random()
        val lease = CountingTelecomLease()

        val result = manager.start(conversationId, fakeManagerLaunchConfig(), lease.lease, this)

        assertEquals(VoiceAgentManagerStartResult.Started(lease.lease.metadata), result)
        assertTrue(session.callbackJoined)
        assertEquals(VoiceCallStatus.BackgroundCapable, manager.state.value.call)
        assertEquals(conversationId, manager.activeConversationId.value)
        assertEquals(0, lease.retireCalls)
    }
}

private class BlockingMutedManagedVoiceCallSession(
    private val releaseMuted: CountDownLatch,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    val setMutedEntered = CountDownLatch(1)
    val reconnectCalls = AtomicInteger()

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) {
        setMutedEntered.countDown()
        check(releaseMuted.await(1, TimeUnit.SECONDS)) { "timed out waiting to release setMuted" }
    }
    override fun reconnect() {
        reconnectCalls.incrementAndGet()
    }
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
}

private class ReentrantStartManagedVoiceCallSession : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    lateinit var manager: VoiceAgentCallManager
    var callbackJoined = false
        private set

    override fun start() {
        val callbackCompleted = CountDownLatch(1)
        val callback = Thread {
            manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
            callbackCompleted.countDown()
        }
        callback.start()
        check(callbackCompleted.await(1, TimeUnit.SECONDS)) { "manager callback did not complete" }
        callback.join(1_000)
        check(!callback.isAlive) { "manager callback worker did not join" }
        callbackJoined = true
    }

    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
}

private fun runMonitorTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
        testScope.block()
    } finally {
        testScope.cancel()
    }
}
