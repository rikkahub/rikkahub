package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallServicePolicyTest {
    @Test
    fun `failed start preserves requested active exact Telecom session`() = runPolicyTest {
        val fixture = PolicyTelecomRouteFixture()
        val session = PolicyManagedSession()
        val manager = VoiceAgentCallManager(PolicyVoiceAgentCallFactory(session))
        val conversationId = Uuid.random()
        manager.start(conversationId, fakePolicyLaunchConfig(), fixture.lease, this)

        val preserveSessionRequested = true
        val preserveSession = preserveSessionRequested && manager.canPreserveActiveSession(conversationId)
        if (!preserveSession) manager.closeNow()

        assertTrue(preserveSession)
        assertTrue(fixture.registry.isOwnedAttemptActive(fixture.attempt))
        assertEquals(0, session.closeNowCalls)
        manager.closeNow()
    }

    @Test
    fun `failed start closes a retired Telecom session without touching newer attempt`() = runPolicyTest {
        val fixture = PolicyTelecomRouteFixture()
        val session = PolicyManagedSession()
        val manager = VoiceAgentCallManager(PolicyVoiceAgentCallFactory(session))
        val conversationId = Uuid.random()
        manager.start(conversationId, fakePolicyLaunchConfig(), fixture.lease, this)
        val newer = fixture.activateNewerAttempt()

        val preserveSessionRequested = true
        val preserveSession = preserveSessionRequested && manager.canPreserveActiveSession(conversationId)
        if (!preserveSession) manager.closeNow()

        assertFalse(preserveSession)
        assertEquals(1, session.closeNowCalls)
        assertEquals(0, newer.call.disconnectCalls)
        assertTrue(fixture.registry.isOwnedAttemptActive(newer.attempt))
        fixture.registry.retireOwnedAttempt(newer.attempt)
    }

    @Test
    fun `failed start preserves requested direct fallback session`() = runPolicyTest {
        val session = PolicyManagedSession()
        val manager = VoiceAgentCallManager(PolicyVoiceAgentCallFactory(session))
        val conversationId = Uuid.random()
        manager.start(
            conversationId,
            fakePolicyLaunchConfig(),
            DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable"),
            ),
            this,
        )

        val preserveSessionRequested = true
        val preserveSession = preserveSessionRequested && manager.canPreserveActiveSession(conversationId)
        if (!preserveSession) manager.closeNow()

        assertTrue(preserveSession)
        assertEquals(0, session.closeNowCalls)
        manager.closeNow()
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

private class PolicyTelecomRouteFixture {
    val registry = VoiceAgentTelecomCallRegistry()
    private val oldCall = PolicyTelecomCall()
    val attempt = registry.beginAttempt()
    val lease: VoiceAgentRouteLease

    init {
        check(registry.activate(attempt, oldCall))
        registry.acknowledgeOutcome(attempt)
        lease = TelecomVoiceAgentRouteLease(attempt, registry)
    }

    fun activateNewerAttempt(): PolicyAttempt {
        val attempt = registry.beginAttempt()
        val call = PolicyTelecomCall()
        check(registry.activate(attempt, call))
        registry.acknowledgeOutcome(attempt)
        return PolicyAttempt(attempt, call)
    }
}

private data class PolicyAttempt(
    val attempt: VoiceAgentTelecomAttemptId,
    val call: PolicyTelecomCall,
)

private class PolicyTelecomCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0
    override fun disconnectFromApp() { disconnectCalls += 1 }
}

private class PolicyVoiceAgentCallFactory(
    private val session: PolicyManagedSession,
) : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession = RouteOwnedVoiceCallSession(session, routeLease)
}

private class PolicyManagedSession : ManagedVoiceCallSession {
    var closeNowCalls = 0
    override val state = MutableStateFlow(VoiceAgentUiState())
    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() {
        closeNowCalls += 1
    }
}

private fun fakePolicyLaunchConfig() = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = "gemini-flash",
    assistantName = "Hermes",
    assistantPrompt = "system",
)

private fun runPolicyTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    try { scope.block() } finally { scope.cancel() }
}
