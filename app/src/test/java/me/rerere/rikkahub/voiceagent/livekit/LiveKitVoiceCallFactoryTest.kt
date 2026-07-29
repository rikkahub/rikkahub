package me.rerere.rikkahub.voiceagent.livekit

import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.OrchestratorFakeRoute
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupMode
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupResult
import me.rerere.rikkahub.voiceagent.VoiceAgentSessionCreationResult
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.orchestratorRequest
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitVoiceCallFactoryTest {
    @Test
    fun `session request timeout retires the exact route and returns clean failure`() = runTest {
        val route = OrchestratorFakeRoute()
        var roomFactoryCalls = 0
        val factory = factory(
            sessionDetailsFactory = { _, _ -> CompletableDeferred<LiveKitSessionDetails>().await() },
            roomFactory = {
                roomFactoryCalls += 1
                InertLiveKitRoomFacade()
            },
            timeoutMillis = 100,
        )
        val result = async {
            factory.createOwned(request(), route.lease, backgroundScope)
        }

        advanceTimeBy(100)
        runCurrent()

        val failure = result.await() as VoiceAgentSessionCreationResult.FailedClean
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertTrue(failure.error.message.orEmpty().contains("timed out"))
        assertEquals(1, route.retirementCalls)
        assertEquals(0, roomFactoryCalls)
    }

    @Test
    fun `session request error is wrapped and retires the exact route`() = runTest {
        val route = OrchestratorFakeRoute()
        val requestError = IllegalStateException("request failed")
        val factory = factory(sessionDetailsFactory = { _, _ -> throw requestError })

        val result = factory.createOwned(request(), route.lease, backgroundScope)

        val failure = result as VoiceAgentSessionCreationResult.FailedClean
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertCausalChainContains(failure.error, requestError)
        assertEquals(1, route.retirementCalls)
    }

    @Test
    fun `session request cancellation stays exact after owned route retirement`() = runTest {
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("caller cancelled")
        val factory = factory(sessionDetailsFactory = { _, _ -> throw cancellation })

        val thrown = runCatching {
            factory.createOwned(request(), route.lease, backgroundScope)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, route.retirementCalls)
    }

    @Test
    fun `route retirement failure returns dirty ownership and retries only that route`() = runTest {
        val retirementError = IllegalArgumentException("route retirement failed")
        var currentRetirementError: Throwable? = retirementError
        val route = OrchestratorFakeRoute { currentRetirementError?.let { throw it } }
        val requestError = IllegalStateException("request failed")
        val factory = factory(sessionDetailsFactory = { _, _ -> throw requestError })

        val result = factory.createOwned(request(), route.lease, backgroundScope)

        val failure = result as VoiceAgentSessionCreationResult.FailedDirty
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertCausalChainContains(failure.error, requestError)
        assertEquals(listOf(retirementError), failure.error.suppressed.toList())
        assertEquals(1, route.retirementCalls)

        currentRetirementError = null
        assertSame(
            VoiceAgentCleanupResult.Completed,
            failure.cleanup.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(2, route.retirementCalls)
    }

    @Test
    fun `room factory failure after details transfers no session and retires the route`() = runTest {
        val route = OrchestratorFakeRoute()
        val roomError = IllegalStateException("room construction failed")
        var detailsCalls = 0
        val factory = factory(
            sessionDetailsFactory = { _, _ ->
                detailsCalls += 1
                factoryDetails()
            },
            roomFactory = { throw roomError },
        )

        val result = factory.createOwned(request(), route.lease, backgroundScope)

        val failure = result as VoiceAgentSessionCreationResult.FailedClean
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertSame(roomError, failure.error.cause)
        assertEquals(1, detailsCalls)
        assertEquals(1, route.retirementCalls)
    }

    private fun factory(
        sessionDetailsFactory: suspend (
            me.rerere.rikkahub.voiceagent.VoiceAgentCallRequest,
            VoiceTraceContext,
        ) -> LiveKitSessionDetails,
        roomFactory: () -> LiveKitRoomFacade = { InertLiveKitRoomFacade() },
        timeoutMillis: Long = 1_000,
    ) = LiveKitVoiceCallFactory(
        context = ContextWrapper(null),
        traceContextFactory = {
            VoiceTraceContext(
                traceId = "VA123456-0000000000000001",
                voiceSessionId = "voice-session",
            )
        },
        sessionDetailsFactory = sessionDetailsFactory,
        roomFactory = roomFactory,
        sessionCreationTimeoutMillis = timeoutMillis,
    )

    private fun request() = orchestratorRequest("livekit-factory").copy(
        transport = VoiceAgentTransport.LiveKitExperimental,
    )
}

private class InertLiveKitRoomFacade : LiveKitRoomFacade {
    override val events: Flow<LiveKitRoomEvent> = emptyFlow()
    override suspend fun connect(url: String, token: String) = Unit
    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean = true
    override suspend fun performRpc(destination: String, method: String, payload: String): String = ""
    override fun registerRpcMethod(method: String, handler: suspend (LiveKitRpcInvocation) -> String) = Unit
    override fun unregisterRpcMethod(method: String) = Unit
    override fun disconnect() = Unit
    override fun close() = Unit
}

private fun factoryDetails() = LiveKitSessionDetails(
    livekitUrl = "wss://project.livekit.cloud",
    participantToken = "participant-token",
    roomName = "rikka_1",
    voiceSessionId = "lvs_1",
    mobileParticipantIdentity = "mobile_lvs_1",
    agentParticipantIdentity = "agent_lvs_1",
    dispatchId = "AD_1",
    expiresAt = "2026-07-20T02:00:00Z",
)

private fun assertCausalChainContains(error: Throwable, expected: Throwable) {
    assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it === expected })
}
