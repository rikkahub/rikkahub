package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentCallServiceLifecycleTest {
    @Test
    fun `foreground notification receives requested LiveKit transport`() = runTest {
        val conversationId = Uuid.random()
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(RecordingServiceController(), scope, host)
        try {
            lifecycle.beginStart(conversationId, VoiceAgentTransport.LiveKitExperimental)

            assertEquals(listOf(VoiceAgentTransport.LiveKitExperimental), host.foregroundTransports)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `matching degraded call keeps degraded notification during configuration`() = runTest {
        val conversationId = Uuid.random()
        val degraded = VoiceAgentUiState(call = VoiceCallStatus.Degraded("existing failure"))
        val controller = RecordingServiceController(
            activeIdentity = ActiveVoiceAgentIdentity(conversationId, VoiceAgentTransport.DirectGemini),
        )
        controller.state.value = degraded
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            lifecycle.beginStart(conversationId)

            assertEquals(degraded, host.foregroundStates.single())
            assertTrue(controller.statuses.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolved current request reaches controller exactly once`() = runTest {
        val controller = RecordingServiceController()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, RecordingLifecycleHost())
        val request = serviceRequest()
        try {
            val generation = lifecycle.beginStart(request.conversationId)

            lifecycle.launchStartConfiguration(generation, request.conversationId) { request }
            runCurrent()

            assertEquals(listOf(request), controller.requests)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale configuration result performs no controller mutation`() = runTest {
        val controller = RecordingServiceController()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, RecordingLifecycleHost())
        val staleRequest = serviceRequest()
        val releaseConfiguration = CompletableDeferred<Unit>()
        try {
            val staleGeneration = lifecycle.beginStart(staleRequest.conversationId)
            lifecycle.launchStartConfiguration(staleGeneration, staleRequest.conversationId) {
                releaseConfiguration.await()
                staleRequest
            }
            runCurrent()

            lifecycle.beginStart(Uuid.random())
            releaseConfiguration.complete(Unit)
            runCurrent()

            assertTrue(controller.requests.isEmpty())
            assertTrue(controller.statuses.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `configuration failure reports safely and performs no controller mutation`() = runTest {
        val controller = RecordingServiceController()
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val conversationId = Uuid.random()
        val failure = IllegalStateException("api_key=private")
        try {
            val generation = lifecycle.beginStart(conversationId)
            lifecycle.launchStartConfiguration(generation, conversationId) { throw failure }
            runCurrent()

            assertTrue(controller.requests.isEmpty())
            assertTrue(controller.statuses.isEmpty())
            assertSame(failure, host.reportedFailures.single())
            assertFalse(host.foregroundStates.last().call.toString().contains("private"))
            assertEquals(1, host.stopSelfCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `different resolved config for same conversation is submitted exactly`() = runTest {
        val controller = RecordingServiceController()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, RecordingLifecycleHost())
        val conversationId = Uuid.random()
        val first = serviceRequest(conversationId, voiceModelId = "first")
        val replacement = serviceRequest(conversationId, voiceModelId = "replacement")
        try {
            val firstGeneration = lifecycle.beginStart(conversationId)
            lifecycle.launchStartConfiguration(firstGeneration, conversationId) { first }
            runCurrent()
            val replacementGeneration = lifecycle.beginStart(conversationId)
            lifecycle.launchStartConfiguration(replacementGeneration, conversationId) { replacement }
            runCurrent()

            assertEquals(listOf(first, replacement), controller.requests)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `matching repeated intent does not cancel submitted controller start`() = runTest {
        val firstResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val secondResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(firstResult, secondResult)),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val request = serviceRequest()
        try {
            val firstGeneration = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(firstGeneration, request.conversationId) { request }
            runCurrent()

            val secondGeneration = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(secondGeneration, request.conversationId) { request }
            runCurrent()

            assertEquals(0, controller.startCancellations)
            assertEquals(listOf(request, request), controller.requests)
            controller.activeIdentity.value = ActiveVoiceAgentIdentity(request.conversationId, request.transport)
            controller.lifecycle.value = VoiceAgentCallLifecycle.Active(request.conversationId)
            secondResult.complete(activeServiceStartResult())
            firstResult.complete(VoiceAgentCallStartResult.Superseded)
            runCurrent()
            assertEquals(0, host.stopSelfCalls)
        } finally {
            firstResult.complete(VoiceAgentCallStartResult.Superseded)
            secondResult.complete(VoiceAgentCallStartResult.Superseded)
            scope.cancel()
        }
    }

    @Test
    fun `different repeated intent does not cancel submitted controller start`() = runTest {
        val firstResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val secondResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(firstResult, secondResult)),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val first = serviceRequest(voiceModelId = "first")
        val replacement = serviceRequest(voiceModelId = "replacement")
        try {
            val firstGeneration = lifecycle.beginStart(first.conversationId)
            lifecycle.launchStartConfiguration(firstGeneration, first.conversationId) { first }
            runCurrent()

            val secondGeneration = lifecycle.beginStart(replacement.conversationId)
            lifecycle.launchStartConfiguration(secondGeneration, replacement.conversationId) { replacement }
            runCurrent()

            assertEquals(0, controller.startCancellations)
            assertEquals(listOf(first, replacement), controller.requests)
            controller.activeIdentity.value = ActiveVoiceAgentIdentity(
                replacement.conversationId,
                replacement.transport,
            )
            controller.lifecycle.value = VoiceAgentCallLifecycle.Active(replacement.conversationId)
            secondResult.complete(activeServiceStartResult())
            firstResult.complete(VoiceAgentCallStartResult.Superseded)
            runCurrent()
            assertEquals(replacement.conversationId.toString(), host.foregroundConversationIds.last())
            assertEquals(0, host.stopSelfCalls)
        } finally {
            firstResult.complete(VoiceAgentCallStartResult.Superseded)
            secondResult.complete(VoiceAgentCallStartResult.Superseded)
            scope.cancel()
        }
    }

    @Test
    fun `stale active completion cannot replace hosted notification transport`() = runTest {
        val firstResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val secondResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(firstResult, secondResult)),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val conversationId = Uuid.random()
        val direct = serviceRequest(conversationId)
        val liveKit = direct.copy(transport = VoiceAgentTransport.LiveKitExperimental)
        try {
            val firstGeneration = lifecycle.beginStart(conversationId, direct.transport)
            lifecycle.launchStartConfiguration(firstGeneration, conversationId) { direct }
            runCurrent()

            val secondGeneration = lifecycle.beginStart(conversationId, liveKit.transport)
            lifecycle.launchStartConfiguration(secondGeneration, conversationId) { liveKit }
            runCurrent()

            controller.activeIdentity.value = ActiveVoiceAgentIdentity(conversationId, liveKit.transport)
            controller.lifecycle.value = VoiceAgentCallLifecycle.Active(conversationId)
            secondResult.complete(activeServiceStartResult())
            runCurrent()
            firstResult.complete(activeServiceStartResult())
            runCurrent()

            lifecycle.endCall()

            assertEquals(VoiceAgentTransport.LiveKitExperimental, host.foregroundTransports.last())
        } finally {
            firstResult.complete(VoiceAgentCallStartResult.Superseded)
            secondResult.complete(VoiceAgentCallStartResult.Superseded)
            scope.cancel()
        }
    }

    @Test
    fun `newer configuration failure keeps older active transport for observation and end`() = runTest {
        val firstResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val conversationId = Uuid.random()
        val direct = serviceRequest(conversationId)
        val liveKit = direct.copy(transport = VoiceAgentTransport.LiveKitExperimental)
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(firstResult)),
            activeIdentity = ActiveVoiceAgentIdentity(conversationId, direct.transport),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            val firstGeneration = lifecycle.beginStart(conversationId, direct.transport)
            lifecycle.launchStartConfiguration(firstGeneration, conversationId) { direct }
            runCurrent()

            val secondGeneration = lifecycle.beginStart(conversationId, liveKit.transport)
            controller.lifecycle.value = VoiceAgentCallLifecycle.Active(conversationId)
            firstResult.complete(activeServiceStartResult())
            runCurrent()

            lifecycle.launchStartConfiguration(secondGeneration, conversationId) {
                throw VoiceAgentCallConfigurationException("LiveKit configuration failed")
            }
            runCurrent()

            assertEquals(VoiceAgentTransport.DirectGemini, host.foregroundTransports.last())

            lifecycle.endCall()

            assertEquals(VoiceAgentTransport.DirectGemini, host.foregroundTransports.last())
        } finally {
            firstResult.complete(VoiceAgentCallStartResult.Superseded)
            scope.cancel()
        }
    }

    @Test
    fun `configuration failure preserves hosted controller through host stop lifecycle`() = runTest {
        val activeConversation = Uuid.random()
        val controller = RecordingServiceController(
            activeIdentity = ActiveVoiceAgentIdentity(activeConversation, VoiceAgentTransport.DirectGemini),
        )
        controller.lifecycle.value = VoiceAgentCallLifecycle.Active(activeConversation)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var lifecycle: VoiceAgentCallServiceLifecycle
        val host = RecordingLifecycleHost(onStopSelf = { lifecycle.destroy() })
        lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            val invalidConversation = Uuid.random()
            val generation = lifecycle.beginStart(invalidConversation)
            lifecycle.launchStartConfiguration(generation, invalidConversation) {
                throw VoiceAgentCallConfigurationException("api_key=[redacted]")
            }
            runCurrent()

            assertEquals(0, host.stopSelfCalls)
            assertEquals(0, controller.closeNowCalls)
            assertEquals(0, controller.endCalls)
            assertTrue(controller.requests.isEmpty())
            assertTrue(controller.statuses.isEmpty())
            assertEquals(activeConversation.toString(), host.foregroundConversationIds.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `configuration failure while cleanup is retained keeps destroy cleanup retry enabled`() = runTest {
        val retainedFailure = IllegalStateException("cleanup retained")
        val controller = RecordingServiceController().apply {
            lifecycle.value = VoiceAgentCallLifecycle.CleanupFailed(retainedFailure)
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var lifecycle: VoiceAgentCallServiceLifecycle
        val host = RecordingLifecycleHost(onStopSelf = { lifecycle.destroy() })
        lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val conversationId = Uuid.random()
        val generation = lifecycle.beginStart(conversationId)

        lifecycle.launchStartConfiguration(generation, conversationId) {
            throw VoiceAgentCallConfigurationException("invalid voice configuration")
        }
        runCurrent()

        assertEquals(1, host.stopSelfCalls)
        assertEquals(1, host.destroyBaseCalls)
        assertEquals(1, controller.closeNowCalls)
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `malformed intent rejection preserves hosted controller through host stop lifecycle`() = runTest {
        val activeConversation = Uuid.random()
        val controller = RecordingServiceController(
            activeIdentity = ActiveVoiceAgentIdentity(activeConversation, VoiceAgentTransport.DirectGemini),
        )
        controller.lifecycle.value = VoiceAgentCallLifecycle.Active(activeConversation)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var lifecycle: VoiceAgentCallServiceLifecycle
        val host = RecordingLifecycleHost(onStopSelf = { lifecycle.destroy() })
        lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            lifecycle.rejectInvalidStart(VoiceAgentCallConfigurationException("invalid conversation id"))

            assertEquals(0, host.stopSelfCalls)
            assertEquals(0, controller.closeNowCalls)
            assertEquals(0, controller.endCalls)
            assertTrue(controller.requests.isEmpty())
            assertTrue(controller.statuses.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `invalid start while cleanup is retained keeps destroy cleanup retry enabled`() = runTest {
        val retainedFailure = IllegalStateException("cleanup retained")
        val controller = RecordingServiceController().apply {
            lifecycle.value = VoiceAgentCallLifecycle.CleanupFailed(retainedFailure)
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var lifecycle: VoiceAgentCallServiceLifecycle
        val host = RecordingLifecycleHost(onStopSelf = { lifecycle.destroy() })
        lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)

        lifecycle.rejectInvalidStart(
            VoiceAgentCallConfigurationException("invalid conversation id"),
        )

        assertEquals(1, host.stopSelfCalls)
        assertEquals(1, host.destroyBaseCalls)
        assertEquals(1, controller.closeNowCalls)
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `idle malformed intent stop and destroy perform no controller mutation`() = runTest {
        val controller = RecordingServiceController()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var lifecycle: VoiceAgentCallServiceLifecycle
        val host = RecordingLifecycleHost(onStopSelf = { lifecycle.destroy() })
        lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)

        lifecycle.rejectInvalidStart(VoiceAgentCallConfigurationException("invalid conversation id"))

        assertEquals(1, host.stopSelfCalls)
        assertEquals(1, host.destroyBaseCalls)
        assertEquals(0, controller.closeNowCalls)
        assertEquals(0, controller.endCalls)
        assertTrue(controller.requests.isEmpty())
        assertTrue(controller.statuses.isEmpty())
    }

    @Test
    fun `idle malformed intent invalidates blocked preflight before host stop`() = runTest {
        val controller = RecordingServiceController()
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val request = serviceRequest()
        val releasePreflight = CompletableDeferred<Unit>()
        try {
            val startGeneration = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(startGeneration, request.conversationId) {
                releasePreflight.await()
                request
            }
            runCurrent()

            lifecycle.rejectInvalidStart(VoiceAgentCallConfigurationException("invalid conversation id"))
            releasePreflight.complete(Unit)
            runCurrent()

            assertEquals(startGeneration + 1, lifecycle.currentGeneration)
            assertTrue(controller.requests.isEmpty())
            assertTrue(controller.statuses.isEmpty())
            assertEquals(
                listOf("cancelNotification", "startForeground", "reportFailure", "stopSelf"),
                host.events,
            )
        } finally {
            releasePreflight.complete(Unit)
            lifecycle.destroy()
        }
        assertEquals(0, controller.closeNowCalls)
    }

    @Test
    fun `failed start reports exact error and stops only matching generation`() = runTest {
        val failure = IllegalStateException("token=private")
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(CompletableDeferred(VoiceAgentCallStartResult.Failed(failure)))),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val request = serviceRequest()
        try {
            val generation = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(generation, request.conversationId) { request }
            runCurrent()

            assertSame(failure, host.reportedFailures.single())
            assertEquals(1, host.stopForegroundCalls)
            assertEquals(1, host.stopSelfCalls)
            assertEquals(
                listOf(
                    "cancelNotification",
                    "startForeground",
                    "cancelNotification",
                    "startForeground",
                    "reportFailure",
                    "stopForeground",
                    "stopSelf",
                ),
                host.events,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `superseded start does not stop winning generation`() = runTest {
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(CompletableDeferred(VoiceAgentCallStartResult.Superseded))),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val request = serviceRequest()
        try {
            val generation = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(generation, request.conversationId) { request }
            runCurrent()

            assertEquals(0, host.stopForegroundCalls)
            assertEquals(0, host.stopSelfCalls)
            assertTrue(host.reportedFailures.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `active notification follows controller state and idle stops matching generation`() = runTest {
        val request = serviceRequest()
        val startResult = CompletableDeferred<VoiceAgentCallStartResult>()
        val controller = RecordingServiceController(
            startResults = ArrayDeque(listOf(startResult)),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            val generation = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(generation, request.conversationId) { request }
            runCurrent()
            controller.activeIdentity.value = ActiveVoiceAgentIdentity(request.conversationId, request.transport)
            controller.lifecycle.value = VoiceAgentCallLifecycle.Active(request.conversationId)
            startResult.complete(
                VoiceAgentCallStartResult.Active(
                    VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback),
                ),
            )
            runCurrent()

            val connected = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
            controller.state.value = connected
            runCurrent()
            assertEquals(connected, host.foregroundStates.last())

            controller.lifecycle.value = VoiceAgentCallLifecycle.Idle
            runCurrent()
            assertEquals(1, host.stopForegroundCalls)
            assertEquals(1, host.stopSelfCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `active result followed by already idle lifecycle stops matching generation`() = runTest {
        val request = serviceRequest()
        val controller = RecordingServiceController()
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            val generation = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(generation, request.conversationId) { request }
            runCurrent()

            assertEquals(1, host.stopForegroundCalls)
            assertEquals(1, host.stopSelfCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cleanup failed lifecycle reports exact error before autonomous stop`() = runTest {
        val request = serviceRequest()
        val controller = RecordingServiceController()
        controller.activeIdentity.value = ActiveVoiceAgentIdentity(request.conversationId, request.transport)
        controller.lifecycle.value = VoiceAgentCallLifecycle.Active(request.conversationId)
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            val generation = lifecycle.beginStart(request.conversationId)
            lifecycle.launchStartConfiguration(generation, request.conversationId) { request }
            runCurrent()
            val failure = IllegalStateException("secret=credential")

            controller.lifecycle.value = VoiceAgentCallLifecycle.CleanupFailed(failure)
            runCurrent()

            assertSame(failure, host.reportedFailures.single())
            assertEquals(1, host.stopForegroundCalls)
            assertEquals(1, host.stopSelfCalls)
            assertTrue(host.events.indexOf("reportFailure") < host.events.indexOf("stopForeground"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `end awaits exact terminal result and old completion cannot stop newer generation`() = runTest {
        val endResult = CompletableDeferred<VoiceAgentCallEndResult>()
        val oldConversation = Uuid.random()
        val controller = RecordingServiceController(
            activeIdentity = ActiveVoiceAgentIdentity(oldConversation, VoiceAgentTransport.DirectGemini),
            endResults = ArrayDeque(listOf(endResult)),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            assertTrue(lifecycle.endCall())
            runCurrent()
            assertEquals(1, controller.endCalls)
            assertEquals(0, host.stopSelfCalls)
            assertFalse(lifecycle.endCall())

            lifecycle.beginStart(Uuid.random())
            endResult.complete(VoiceAgentCallEndResult.Completed)
            runCurrent()

            assertEquals(0, host.endCompletedCalls)
            assertEquals(0, host.stopForegroundCalls)
            assertEquals(0, host.stopSelfCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `end failure reports exact error then stops matching generation`() = runTest {
        val failure = IllegalStateException("cleanup token=secret")
        val controller = RecordingServiceController(
            activeIdentity = ActiveVoiceAgentIdentity(Uuid.random(), VoiceAgentTransport.DirectGemini),
            endResults = ArrayDeque(listOf(CompletableDeferred(VoiceAgentCallEndResult.Failed(failure)))),
        )
        val host = RecordingLifecycleHost()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        try {
            assertTrue(lifecycle.endCall())
            runCurrent()

            assertSame(failure, host.reportedFailures.single())
            assertEquals(1, host.endCompletedCalls)
            assertEquals(1, host.stopForegroundCalls)
            assertEquals(1, host.stopSelfCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `destroy closes controller before cancelling service jobs and destroys base`() = runTest {
        val events = mutableListOf<String>()
        val controller = RecordingServiceController(events = events)
        val host = RecordingLifecycleHost(events = events)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(controller, scope, host)
        val generation = lifecycle.currentGeneration

        lifecycle.destroy()

        assertEquals(generation + 1, lifecycle.currentGeneration)
        assertFalse(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertEquals(1, controller.closeNowCalls)
        assertEquals(1, host.destroyBaseCalls)
        assertEquals(listOf("closeNow", "cancelNotification", "destroyBase"), events)
    }

    @Test
    fun `destroy closes controller before cancelling blocked end waiter`() = runTest {
        val events = mutableListOf<String>()
        val blockedEnd = CompletableDeferred<VoiceAgentCallEndResult>()
        val controller = RecordingServiceController(
            endResults = ArrayDeque(listOf(blockedEnd)),
            events = events,
        )
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val lifecycle = VoiceAgentCallServiceLifecycle(
            controller = controller,
            serviceScope = scope,
            host = RecordingLifecycleHost(events),
        )
        try {
            assertTrue(lifecycle.endCall())
            runCurrent()

            lifecycle.destroy()
            runCurrent()

            assertEquals(1, controller.closeNowCalls)
            assertEquals(1, controller.endCancellations)
            assertTrue(events.indexOf("closeNow") < events.indexOf("endCancelled"))
        } finally {
            blockedEnd.complete(VoiceAgentCallEndResult.Completed)
            scope.cancel()
        }
    }
}

internal class RecordingServiceController(
    activeIdentity: ActiveVoiceAgentIdentity? = null,
    val startResults: ArrayDeque<CompletableDeferred<VoiceAgentCallStartResult>> = ArrayDeque(),
    val endResults: ArrayDeque<CompletableDeferred<VoiceAgentCallEndResult>> = ArrayDeque(),
    private val events: MutableList<String>? = null,
) : VoiceAgentCallServiceController {
    override val activeIdentity = MutableStateFlow(activeIdentity)
    override val lifecycle = MutableStateFlow<VoiceAgentCallLifecycle>(VoiceAgentCallLifecycle.Idle)
    override val state = MutableStateFlow(VoiceAgentUiState())
    val requests = mutableListOf<VoiceAgentCallRequest>()
    val statuses = mutableListOf<VoiceCallStatus>()
    var startCancellations = 0
    var endCalls = 0
    var endCancellations = 0
    var closeNowCalls = 0

    override suspend fun start(request: VoiceAgentCallRequest): VoiceAgentCallStartResult {
        requests += request
        return try {
            startResults.removeFirstOrNull()?.await() ?: activeServiceStartResult()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            startCancellations += 1
            throw cancellation
        }
    }

    override suspend fun end(): VoiceAgentCallEndResult {
        endCalls += 1
        return try {
            endResults.removeFirstOrNull()?.await() ?: VoiceAgentCallEndResult.Completed
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            endCancellations += 1
            events?.add("endCancelled")
            throw cancellation
        }
    }

    override fun closeNow() {
        closeNowCalls += 1
        events?.add("closeNow")
    }

    override fun updateCallStatus(status: VoiceCallStatus) {
        statuses += status
    }
}

internal class RecordingLifecycleHost(
    val events: MutableList<String> = mutableListOf(),
    private val onStopSelf: (() -> Unit)? = null,
) : VoiceAgentCallServiceLifecycleHost {
    val reportedFailures = mutableListOf<Throwable>()
    val foregroundStates = mutableListOf<VoiceAgentUiState>()
    val foregroundConversationIds = mutableListOf<String>()
    val foregroundTransports = mutableListOf<VoiceAgentTransport>()
    val completedConversationIds = mutableListOf<Uuid?>()
    var stopForegroundCalls = 0
    var stopSelfCalls = 0
    var endCompletedCalls = 0
    var destroyBaseCalls = 0

    override fun cancelNotification() {
        events += "cancelNotification"
    }

    override fun startForeground(
        conversationId: String,
        transport: VoiceAgentTransport,
        state: VoiceAgentUiState,
    ) {
        events += "startForeground"
        foregroundConversationIds += conversationId
        foregroundTransports += transport
        foregroundStates += state
    }

    override fun endCompleted(conversationId: Uuid?) {
        events += "endCompleted"
        endCompletedCalls += 1
        completedConversationIds += conversationId
    }

    override fun stopForeground() {
        events += "stopForeground"
        stopForegroundCalls += 1
    }

    override fun stopSelf() {
        events += "stopSelf"
        stopSelfCalls += 1
        onStopSelf?.invoke()
    }

    override fun reportFailure(error: Throwable) {
        events += "reportFailure"
        reportedFailures += error
    }

    override fun destroyBaseService() {
        events += "destroyBase"
        destroyBaseCalls += 1
    }
}

private fun activeServiceStartResult() = VoiceAgentCallStartResult.Active(
    VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback),
)

internal fun serviceRequest(
    conversationId: Uuid = Uuid.random(),
    voiceModelId: String = "gemini-flash",
) = VoiceAgentCallRequest(
    conversationId = conversationId,
    transport = VoiceAgentTransport.DirectGemini,
    config = VoiceAgentLaunchConfig(
        hermesVoiceBaseUrl = "https://voice.test",
        credentials = me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials(
            deviceApiKey = "profile-key",
        ),
        voiceModelId = voiceModelId,
        assistantName = "Hermes",
        assistantPrompt = "system",
        directAccountConfigurationHash = "sha256:" + "a".repeat(64),
    ),
)
