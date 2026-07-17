package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallServicePolicyTest {
    @Test
    fun `Error through production failed-start boundary preserves live exact Telecom session`() = runPolicyTest {
        val route = LifecycleTelecomRoute()
        val session = LifecycleManagedSession()
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val conversationId = Uuid.random()
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, this, host)
        lifecycle.beginStart()
        manager.start(conversationId, lifecycleLaunchConfig(), route.lease, this)

        val handled = lifecycle.handleStartupTerminal(
            conversationId,
            VoiceSessionStatus.Error("startup failed"),
        )

        assertTrue(handled)
        assertEquals(conversationId, manager.activeConversationId.value)
        assertEquals(0, route.retireCalls)
        assertEquals(0, session.closeNowCalls)
        manager.closeNow()
    }

    @Test
    fun `Error through production failed-start boundary closes retired Telecom without replacement`() = runPolicyTest {
        val route = LifecycleTelecomRoute()
        val session = LifecycleManagedSession()
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val conversationId = Uuid.random()
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, this, host)
        lifecycle.beginStart()
        manager.start(conversationId, lifecycleLaunchConfig(), route.lease, this)
        val replacement = route.activateReplacement()

        val handled = lifecycle.handleStartupTerminal(
            conversationId,
            VoiceSessionStatus.Error("startup failed"),
        )

        assertTrue(handled)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(1, session.closeNowCalls)
        assertEquals(0, replacement.disconnectCalls)
    }

    @Test
    fun `Error through production failed-start boundary preserves live direct session`() = runPolicyTest {
        val session = LifecycleManagedSession()
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val conversationId = Uuid.random()
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, this, host)
        lifecycle.beginStart()
        manager.start(
            conversationId,
            lifecycleLaunchConfig(),
            DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable"),
            ),
            this,
        )

        val handled = lifecycle.handleStartupTerminal(
            conversationId,
            VoiceSessionStatus.Error("startup failed"),
        )

        assertTrue(handled)
        assertEquals(conversationId, manager.activeConversationId.value)
        assertEquals(0, session.closeNowCalls)
        manager.closeNow()
    }

    @Test
    fun `Ended through production failed-start boundary closes non-preserved session`() = runPolicyTest {
        val session = LifecycleManagedSession()
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val conversationId = Uuid.random()
        val host = RecordingLifecycleHost()
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, this, host)
        lifecycle.beginStart()
        manager.start(
            conversationId,
            lifecycleLaunchConfig(),
            DirectFallbackVoiceAgentRouteLease(VoiceAgentTelecomFailure("fallback", "fallback")),
            this,
        )

        val handled = lifecycle.handleStartupTerminal(conversationId, VoiceSessionStatus.Ended)

        assertTrue(handled)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(1, session.closeNowCalls)
        assertEquals(1, host.stopForegroundCalls)
        assertEquals(1, host.stopSelfCalls)
    }

    @Test
    fun `close failure still runs diagnostic status foreground and stop stages in order`() = runPolicyTest {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("close failed")
        val session = LifecycleManagedSession(events = events, closeNowFailure = closeFailure)
        val manager = VoiceAgentCallManager(LifecycleCallFactory(session))
        val conversationId = Uuid.random()
        val host = RecordingLifecycleHost(events = events)
        val lifecycle = VoiceAgentCallServiceLifecycle(manager, this, host)
        lifecycle.beginStart()
        manager.start(
            conversationId,
            lifecycleLaunchConfig(),
            DirectFallbackVoiceAgentRouteLease(VoiceAgentTelecomFailure("fallback", "fallback")),
            this,
        )

        lifecycle.handleStartupTerminal(conversationId, VoiceSessionStatus.Ended)

        assertEquals(
            listOf(
                "cancelNotification",
                "cancelNotification",
                "diagnostic:voice_call_start_failed:Voice call ended before startup completed",
                "close",
                "startForeground",
                "stopForeground",
                "stopSelf",
                "reportFailure",
            ),
            events,
        )
        assertTrue(host.foregroundStates.single().call is VoiceCallStatus.Degraded)
        assertSame(closeFailure, host.reportedFailures.single())
    }

    @Test
    fun `retained Telecom session with errored no-progress reconnect preserves Degraded status`() {
        assertFalse(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.Telecom,
                current = VoiceCallStatus.Degraded("Existing connection failed"),
            ),
        )
    }

    @Test
    fun `non-degraded Telecom start publishes BackgroundCapable status`() {
        assertTrue(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.Telecom,
                current = VoiceCallStatus.ForegroundStarting,
            ),
        )
    }

    @Test
    fun `DirectFallback never publishes BackgroundCapable status`() {
        assertFalse(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.DirectFallback,
                current = VoiceCallStatus.ForegroundStarting,
            ),
        )
        assertFalse(
            shouldPublishVoiceCallBackgroundCapable(
                owner = VoiceAudioRouteOwner.DirectFallback,
                current = VoiceCallStatus.Degraded("Telecom unavailable"),
            ),
        )
    }
}

private fun runPolicyTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    try { scope.block() } finally { scope.cancel() }
}
