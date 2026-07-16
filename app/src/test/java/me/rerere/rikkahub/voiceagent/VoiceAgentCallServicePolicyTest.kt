package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallServicePolicyTest {
    @Test
    fun `failed start preserves requested usable exact route without global retirement`() = runPolicyTest {
        val fixture = PolicyTelecomRouteFixture()
        val manager = VoiceAgentCallManager(PolicyVoiceAgentCallFactory())
        val conversationId = Uuid.random()
        manager.start(conversationId, fakePolicyLaunchConfig(), fixture.lease, this)

        val plan = voiceAgentFailedStartCleanupPlan(
            preserveSessionRequested = true,
            canPreserveActiveSession = manager.canPreserveActiveSession(conversationId),
        )

        assertEquals(VoiceAgentFailedStartCleanupPlan(preserveSession = true, retireTelecomCall = false), plan)
        assertEquals(0, fixture.oldDisconnectCalls)
        manager.closeNow()
    }

    @Test
    fun `failed start closes non-preserved exact route without global retirement`() = runPolicyTest {
        val fixture = PolicyTelecomRouteFixture()
        val manager = VoiceAgentCallManager(PolicyVoiceAgentCallFactory())
        val conversationId = Uuid.random()
        manager.start(conversationId, fakePolicyLaunchConfig(), fixture.lease, this)

        val plan = voiceAgentFailedStartCleanupPlan(
            preserveSessionRequested = false,
            canPreserveActiveSession = manager.canPreserveActiveSession(conversationId),
        )
        if (!plan.preserveSession) manager.closeNow()

        assertEquals(VoiceAgentFailedStartCleanupPlan(preserveSession = false, retireTelecomCall = false), plan)
        assertEquals(1, fixture.oldDisconnectCalls)
    }

    @Test
    fun `failed start closes unusable exact route without touching newer attempt`() = runPolicyTest {
        val fixture = PolicyTelecomRouteFixture()
        val manager = VoiceAgentCallManager(PolicyVoiceAgentCallFactory())
        val conversationId = Uuid.random()
        manager.start(conversationId, fakePolicyLaunchConfig(), fixture.lease, this)
        val newer = fixture.activateNewerAttempt()

        val plan = voiceAgentFailedStartCleanupPlan(
            preserveSessionRequested = true,
            canPreserveActiveSession = manager.canPreserveActiveSession(conversationId),
        )
        if (!plan.preserveSession) manager.closeNow()

        assertEquals(VoiceAgentFailedStartCleanupPlan(preserveSession = false, retireTelecomCall = false), plan)
        assertEquals(1, fixture.oldDisconnectCalls)
        assertEquals(0, newer.call.disconnectCalls)
        assertTrue(fixture.registry.isOwnedAttemptActive(newer.attempt))
        fixture.registry.retireOwnedAttempt(newer.attempt)
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
    private val oldAttempt = registry.beginAttempt()
    val lease: VoiceAgentRouteLease
    val oldDisconnectCalls: Int get() = oldCall.disconnectCalls

    init {
        check(registry.activate(oldAttempt, oldCall))
        registry.acknowledgeOutcome(oldAttempt)
        lease = TelecomVoiceAgentRouteLease(oldAttempt, registry)
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

private class PolicyVoiceAgentCallFactory : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession = RouteOwnedVoiceCallSession(PolicyManagedSession(), routeLease)
}

private class PolicyManagedSession : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
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
