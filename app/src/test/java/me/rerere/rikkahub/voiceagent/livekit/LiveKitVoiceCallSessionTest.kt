package me.rerere.rikkahub.voiceagent.livekit

import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.voiceagent.DirectFallbackVoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.OrchestratorFakeRoute
import me.rerere.rikkahub.voiceagent.VoiceAgentSessionCreationResult
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomFailure
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupMode
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupResult
import me.rerere.rikkahub.voiceagent.VoiceAudioStatus
import me.rerere.rikkahub.voiceagent.VoiceSessionStatus
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifact
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactWriter
import me.rerere.rikkahub.voiceagent.orchestratorRequest
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationCorrelationKind
import me.rerere.rikkahub.voiceagent.automation.DefaultVoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunBinding
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixture
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveKitVoiceCallSessionTest {
    @Test
    fun `fixture remains armed until the matching ready event`() = runTest {
        VoiceCaptureFixtureArming.clearForTest()
        val token = VoiceCaptureFixtureArming.arm(
            initial = VoiceCaptureFixture("prompt.pcm", byteArrayOf(1, 2), 2, 0),
            staged = emptyList(),
        )
        val captureSource = VoiceCaptureFixtureArming.claim(token).getOrThrow()
        val delivered = mutableListOf<List<Byte>>()
        val pump = backgroundScope.launch {
            captureSource.pump(
                onPcm16 = { delivered += it.toList() },
                onFixtureComplete = {},
            )
        }
        val fixture = fixture(
            automationRuntime = SessionRecordingAutomationRuntime(),
            captureSource = captureSource,
        )

        fixture.session.start()
        runCurrent()
        assertTrue(delivered.isEmpty())

        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()
        captureSource.awaitIdle()
        assertEquals(listOf(listOf<Byte>(1, 2)), delivered)

        fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        pump.cancel()
        VoiceCaptureFixtureArming.clearForTest()
    }

    @Test
    fun `second real LiveKit reconnect callback remains structurally visible in artifact`() = runTest {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("livekit-second-reconnect").toFile(),
        )
        runtime.prepare(
            VoiceAutomationRunBinding(
                AUTOMATION_RUN_HASH,
                AUTOMATION_COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )
        val fixture = fixture(automationRuntime = runtime)
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()

        repeat(2) {
            fixture.room.emit(LiveKitRoomEvent.Reconnecting)
            fixture.room.emit(LiveKitRoomEvent.Reconnected)
            runCurrent()
        }
        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )

        val names = runtime.finalizeRun().readLines().map { line ->
            Regex("""\"name\":\"([^\"]+)\"""").find(line)!!.groupValues[1]
        }
        assertEquals(2, names.count { it == "reconnect_started" })
    }

    @Test
    fun `autonomous failure cleanup does not block the session Main dispatcher`() = runTest {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val cleanupDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val sessionScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val route = OrchestratorFakeRoute {
            cleanupStarted.countDown()
            check(releaseCleanup.await(5, TimeUnit.SECONDS)) { "cleanup release timed out" }
        }
        val fixture = fixture(
            connectFailure = IllegalStateException("connect failed"),
            route = route,
            sessionScope = sessionScope,
            cleanupDispatcher = cleanupDispatcher,
        )
        try {
            fixture.session.start()
            assertTrue("autonomous cleanup did not start", cleanupStarted.await(5, TimeUnit.SECONDS))

            val mainProbe = CountDownLatch(1)
            sessionScope.launch { mainProbe.countDown() }

            assertTrue(
                "session Main dispatcher was blocked by autonomous cleanup",
                mainProbe.await(1, TimeUnit.SECONDS),
            )
        } finally {
            releaseCleanup.countDown()
            sessionScope.cancel()
            cleanupDispatcher.close()
            mainDispatcher.close()
        }
    }

    @Test
    fun `closed fallback session reports its route unusable`() = runTest {
        val session = LiveKitVoiceCallSession(
            details = details(),
            traceId = TEST_TRACE_ID,
            room = FakeLiveKitRoomFacade(),
            routeLease = DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("telecom_unavailable", "test fallback"),
            ),
            scope = backgroundScope,
        )

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )

        assertFalse(session.isRouteUsable)
    }

    @Test
    fun `factory trace ID is published in the LiveKit session UI state`() = runTest {
        val trace = VoiceTraceContext(traceId = "VA123456-0000000000000001", voiceSessionId = "voice-session")
        val returnedDetails = details()
        val root = Files.createTempDirectory("livekit-trace-factory").toFile()
        val factory = LiveKitVoiceCallFactory(
            context = object : ContextWrapper(null) {
                override fun getNoBackupFilesDir(): File = root
            },
            traceContextFactory = { trace },
            sessionDetailsFactory = { request, requestedTrace ->
                returnedDetails.copy(
                    correlationBinding = returnedDetails.correlationBinding.copy(
                        conversationHash = voiceSha256(request.conversationId.toString()),
                        traceHash = voiceSha256(requestedTrace.traceId),
                    ),
                )
            },
            roomFactory = { FakeLiveKitRoomFacade() },
            conversationStoreFactory = { InMemoryVoiceConversationStore() },
            artifactWriterFactory = { _, _, _ -> VoiceE2EArtifactWriter.disabled() },
        )

        try {
            val result = factory.createOwned(
                request = orchestratorRequest("livekit-trace").copy(
                    transport = VoiceAgentTransport.LiveKitExperimental,
                ),
                routeLease = OrchestratorFakeRoute().lease,
                scope = backgroundScope,
            )

            val session = (result as VoiceAgentSessionCreationResult.Created).session
            assertEquals(trace.traceId, session.state.value.traceId)
            session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `room connection is not usable until expected worker ready`() = runTest {
        val fixture = fixture()

        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Connected)
        runCurrent()

        assertFalse(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        fixture.room.emit(
            LiveKitRoomEvent.Data(
                participantIdentity = AGENT_IDENTITY,
                topic = READY_TOPIC,
                payload = readyJson(),
            ),
        )
        runCurrent()

        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertEquals(listOf(AGENT_IDENTITY), fixture.room.remoteAudioParticipants)
        assertTrue(
            fixture.room.lifecycle.indexOf("remote-audio:$AGENT_IDENTITY") <
                fixture.room.lifecycle.indexOf("connect"),
        )
        assertEquals(listOf(LIVEKIT_URL to PARTICIPANT_TOKEN), fixture.room.connections)
        assertEquals(listOf(true), fixture.room.microphoneValues)
    }

    @Test
    fun `ready rejects the wrong agent topic and voice session`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Connected)
        fixture.room.emit(LiveKitRoomEvent.Data("other-agent", READY_TOPIC, readyJson()))
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, "other.topic", readyJson()))
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson("lvs_other")))
        runCurrent()

        assertFalse(fixture.session.state.value.session is VoiceSessionStatus.Connected)
    }

    @Test
    fun `ready records worker evidence only after its hash matches the active trace`() = runTest {
        val runtime = SessionRecordingAutomationRuntime()
        val fixture = fixture(automationRuntime = runtime)
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Connected)
        fixture.room.emit(
            LiveKitRoomEvent.Data(
                AGENT_IDENTITY,
                READY_TOPIC,
                readyJson(eventIdHash = OTHER_WORKER_EVENT_HASH),
            ),
        )
        runCurrent()

        assertFalse(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertTrue(runtime.events.none { it.correlationKind == VoiceAutomationCorrelationKind.WORKER_EVENT })

        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()

        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        val workerEvent = runtime.events.single {
            it.correlationKind == VoiceAutomationCorrelationKind.WORKER_EVENT
        }
        assertEquals(VoiceAutomationEventName.CALL_ACTIVE, workerEvent.name)
        assertEquals(WORKER_EVENT_HASH, workerEvent.correlationHash)
        assertFalse(workerEvent.correlationHash.orEmpty().contains(TEST_TRACE_ID))

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.CALL_STOPPED,
                succeeded = true,
            ),
            runtime.events.last(),
        )
    }

    @Test
    fun `LiveKit callbacks cannot write into a replacement automation run`() = runTest {
        val runtime = SessionRecordingAutomationRuntime()
        val fixture = fixture(automationRuntime = runtime)
        fixture.session.start()
        runCurrent()
        runtime.activeRunHash = REPLACEMENT_AUTOMATION_RUN_HASH

        fixture.room.emit(
            LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()),
        )
        fixture.room.emit(LiveKitRoomEvent.Reconnecting)
        fixture.room.emit(LiveKitRoomEvent.Reconnected)
        runCurrent()
        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )

        assertTrue(runtime.events.all { it.name == VoiceAutomationEventName.CALL_START_REQUESTED })
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.CALL_ACTIVE })
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.RECONNECT_STARTED })
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED })
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.CALL_STOPPED })
    }

    @Test
    fun `ready rejects invalid observed timestamp without worker evidence`() = runTest {
        val runtime = SessionRecordingAutomationRuntime()
        val fixture = fixture(automationRuntime = runtime)
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Connected)
        fixture.room.emit(
            LiveKitRoomEvent.Data(
                AGENT_IDENTITY,
                READY_TOPIC,
                readyJson(observedAt = "not-a-time"),
            ),
        )
        runCurrent()

        assertFalse(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertTrue(runtime.events.none { it.correlationKind == VoiceAutomationCorrelationKind.WORKER_EVENT })
    }

    @Test
    fun `mute and explicit interrupt use only LiveKit`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()

        fixture.session.setMuted(true)
        runCurrent()
        fixture.session.setMuted(false)
        fixture.session.interrupt()
        runCurrent()

        assertEquals(listOf(false, true), fixture.room.microphoneValues.takeLast(2))
        assertEquals(VoiceAudioStatus.Listening, fixture.session.state.value.audio)
        assertEquals(
            listOf(Triple(AGENT_IDENTITY, INTERRUPT_RPC, "")),
            fixture.room.rpcCalls,
        )
    }

    @Test
    fun `LiveKit automation activates one binding and emits only hashed session correlations`() = runTest {
        val runtime = SessionRecordingAutomationRuntime()
        val automationAudio = SessionAutomationAudioBinding()
        val fixture = fixture(
            automationRuntime = runtime,
            automationAudio = automationAudio,
        )

        fixture.session.start()
        runCurrent()

        assertEquals(listOf(AUTOMATION_RUN_HASH), automationAudio.activations)
        assertEquals(
            listOf(
                VoiceAutomationCorrelationKind.SESSION to
                    "sha256:6dde1c43f223440f4bfba0ed05aa33cb837253ac01e0cadc1d223eff98914e06",
                VoiceAutomationCorrelationKind.ROOM to
                    "sha256:3991f60c5217aa9e5a07f65f0fcbdd77e67e3ad561e3b36a0bab7afcea93aeee",
                VoiceAutomationCorrelationKind.PARTICIPANT to
                    "sha256:74b422c6852d91b5711278847ec3328d8cbc5278dbd3714be0f152238d9181b3",
                VoiceAutomationCorrelationKind.PARTICIPANT to
                    "sha256:4020120a252b921edd22293a005a8d6e2f30a34547010be3227bbf916520088f",
                VoiceAutomationCorrelationKind.DISPATCH to
                    "sha256:a1d74bdb82dd482b0e06b213cef16f71eff7b25072ee144ddee0156900bfa335",
            ),
            runtime.events.map { it.correlationKind to it.correlationHash },
        )
        assertTrue(runtime.events.all { it.name == VoiceAutomationEventName.CALL_START_REQUESTED })
        assertTrue(runtime.events.all { it.observedTransport == VoiceAgentTransport.LiveKitExperimental })
        assertTrue(
            runtime.events
                .mapNotNull(VoiceAutomationEventInput::correlationHash)
                .none { hash ->
                    listOf(
                        VOICE_SESSION_ID,
                        "rikka_1",
                        "mobile_lvs_1",
                        AGENT_IDENTITY,
                        "AD_1",
                    ).any(hash::contains)
                },
        )

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(1, automationAudio.closeCalls)
    }

    @Test
    fun `LiveKit interrupt marks shared output state before sending RPC`() = runTest {
        lateinit var sessionFixture: SessionFixture
        val automationProbe = SessionRecordingAudioProbe {
            assertTrue(sessionFixture.room.rpcCalls.isEmpty())
        }
        sessionFixture = fixture(automationAudioProbe = automationProbe)
        sessionFixture.session.start()
        runCurrent()

        sessionFixture.session.interrupt()
        runCurrent()

        assertEquals(1, automationProbe.interruptionStarts)
        assertEquals(
            listOf(Triple(AGENT_IDENTITY, INTERRUPT_RPC, "")),
            sessionFixture.room.rpcCalls,
        )

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            sessionFixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        sessionFixture.session.interrupt()
        runCurrent()

        assertEquals(1, automationProbe.interruptionStarts)
        assertEquals(1, sessionFixture.room.rpcCalls.size)
    }

    @Test
    fun `latest mute request wins while initial microphone publication is suspended`() = runTest {
        val fixture = fixture()
        val initialMicrophoneGate = CompletableDeferred<Unit>()
        fixture.room.microphoneGate = initialMicrophoneGate
        fixture.session.start()
        runCurrent()
        assertEquals(listOf(true), fixture.room.microphoneValues)

        fixture.session.setMuted(true)
        fixture.session.setMuted(false)
        fixture.session.setMuted(true)

        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        runCurrent()
        assertEquals(listOf(true), fixture.room.microphoneValues)

        initialMicrophoneGate.complete(Unit)
        runCurrent()

        assertEquals(listOf(true, false), fixture.room.microphoneValues)
        assertFalse(fixture.room.sdkMicrophoneEnabled)
        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
    }

    @Test
    fun `false microphone publication result fails and cleans the experimental call`() = runTest {
        val fixture = fixture()
        fixture.room.microphoneResult = false

        fixture.session.start()
        runCurrent()

        val status = fixture.session.state.value.session
        assertTrue(status is VoiceSessionStatus.Error)
        assertTrue((status as VoiceSessionStatus.Error).message.contains("microphone", ignoreCase = true))
        assertTrue(
            fixture.session.state.value.diagnostics.any { it.name == "livekit_microphone_failed" },
        )
        assertFalse(fixture.session.isRouteUsable)
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `microphone publication exception fails and cleans the experimental call`() = runTest {
        val fixture = fixture()
        fixture.room.microphoneFailure = IllegalStateException("synthetic publication failure")

        fixture.session.start()
        runCurrent()

        val status = fixture.session.state.value.session
        assertTrue(status is VoiceSessionStatus.Error)
        assertTrue((status as VoiceSessionStatus.Error).message.contains("microphone", ignoreCase = true))
        assertTrue(
            fixture.session.state.value.diagnostics.any { it.name == "livekit_microphone_failed" },
        )
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `RPC methods are registered before connect and unregistered by one idempotent cleanup`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        fixture.session.start()
        runCurrent()

        assertTrue(
            fixture.room.lifecycle.indexOf("register:hermes.job.accepted") < fixture.room.lifecycle.indexOf("connect"),
        )
        assertEquals("persisted", fixture.room.invoke("hermes.job.accepted", AGENT_IDENTITY, "payload"))

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd),
        )

        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
        assertEquals(1, fixture.route.retirementCalls)
    }

    @Test
    fun `cleanup retries failed stages without repeating completed stages`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        val disconnectFailure = IllegalStateException("disconnect failed")
        fixture.room.disconnectFailure = disconnectFailure
        fixture.session.start()
        runCurrent()

        val first = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        assertEquals(disconnectFailure, (first as VoiceAgentCleanupResult.Failed).error)
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(0, fixture.room.closeCalls)

        fixture.room.disconnectFailure = null

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(2, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `persistence RPC accepts only the expected worker and is drained before store close`() = runTest {
        val persistence = RecordingPersistenceOwner()
        val fixture = fixture(
            persistenceHandler = persistence::handle,
            persistenceOwner = persistence,
        )
        fixture.session.start()
        runCurrent()
        persistence.onDrain = {
            assertTrue(fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC))
        }

        val handler = fixture.room.rpcHandlers.getValue(LIVEKIT_PERSISTENCE_RPC)
        val wrongCallerFailure = runCatching {
            handler(LiveKitRpcInvocation("unexpected-worker", acceptedEventJson()))
        }.exceptionOrNull()
        val ack = handler(LiveKitRpcInvocation(AGENT_IDENTITY, acceptedEventJson()))

        assertTrue(wrongCallerFailure is IllegalArgumentException)
        assertEquals("""{"status":"persisted"}""", ack)
        assertEquals(listOf("evt_accepted"), persistence.events)
        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd),
        )
        assertEquals(listOf("drain", "close"), persistence.lifecycle)
        assertFalse(
            "lifecycle=${fixture.room.lifecycle} handlers=${fixture.room.rpcHandlers.keys}",
            fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC),
        )
    }

    @Test
    fun `immediate cleanup joins an admitted persistence handler before closing its owner`() = runTest {
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerGate = CompletableDeferred<Unit>()
        val persistence = RecordingPersistenceOwner(
            handlerStarted = handlerStarted,
            handlerGate = handlerGate,
        )
        val fixture = fixture(
            persistenceHandler = persistence::handle,
            persistenceOwner = persistence,
        )
        fixture.session.start()
        runCurrent()

        val invocation = async {
            fixture.room.rpcHandlers.getValue(LIVEKIT_PERSISTENCE_RPC)(
                LiveKitRpcInvocation(AGENT_IDENTITY, acceptedEventJson()),
            )
        }
        handlerStarted.await()
        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }
        runCurrent()

        assertFalse(cleanup.isCompleted)
        assertFalse(invocation.isCompleted)
        assertTrue(persistence.lifecycle.isEmpty())

        handlerGate.complete(Unit)
        runCurrent()

        assertEquals("""{"status":"persisted"}""", invocation.await())
        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertEquals(listOf("drain", "close"), persistence.lifecycle)
        assertEquals(listOf("evt_accepted"), persistence.events)
    }

    @Test
    fun `graceful cleanup joins admitted work before entering persistence drain`() = runTest {
        val admittedBeforePersistence = CompletableDeferred<Unit>()
        val enterPersistence = CompletableDeferred<Unit>()
        val persistence = RecordingPersistenceOwner()
        val fixture = fixture(
            persistenceHandler = { callerIdentity, payload ->
                admittedBeforePersistence.complete(Unit)
                enterPersistence.await()
                persistence.handle(callerIdentity, payload)
            },
            persistenceOwner = persistence,
        )
        fixture.session.start()
        runCurrent()

        val invocation = async {
            fixture.room.invoke(
                LIVEKIT_PERSISTENCE_RPC,
                AGENT_IDENTITY,
                acceptedEventJson(),
            )
        }
        admittedBeforePersistence.await()
        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd)
        }
        runCurrent()

        assertFalse(cleanup.isCompleted)
        assertTrue(persistence.lifecycle.isEmpty())
        assertTrue(fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC))

        enterPersistence.complete(Unit)
        runCurrent()

        assertEquals("""{"status":"persisted"}""", invocation.await())
        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertEquals(listOf("drain", "close"), persistence.lifecycle)
        assertFalse(fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC))
    }

    @Test
    fun `call stopped follows RPC quiescence persistence drain artifact close and room close`() = runTest {
        val orderedStages = mutableListOf<String>()
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerGate = CompletableDeferred<Unit>()
        val drainStarted = CompletableDeferred<Unit>()
        val drainGate = CompletableDeferred<Unit>()
        val artifactCloseStarted = CompletableDeferred<Unit>()
        val artifactCloseGate = CompletableDeferred<Unit>()
        val persistence = RecordingPersistenceOwner(
            handlerStarted = handlerStarted,
            handlerGate = handlerGate,
            drainStarted = drainStarted,
            drainGate = drainGate,
            artifactCloseStarted = artifactCloseStarted,
            artifactCloseGate = artifactCloseGate,
            stageObserver = orderedStages::add,
        )
        val runtime = SessionRecordingAutomationRuntime { event ->
            if (event.name == VoiceAutomationEventName.CALL_STOPPED) {
                orderedStages += "call-stopped"
            }
        }
        val fixture = fixture(
            persistenceHandler = persistence::handle,
            persistenceOwner = persistence,
            automationRuntime = runtime,
            roomLifecycleObserver = { stage -> orderedStages += "room-$stage" },
        )
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()

        val invocation = async {
            fixture.room.invoke(
                LIVEKIT_PERSISTENCE_RPC,
                AGENT_IDENTITY,
                acceptedEventJson(),
            )
        }
        handlerStarted.await()
        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd)
        }
        runCurrent()

        assertFalse(cleanup.isCompleted)
        assertFalse(drainStarted.isCompleted)
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.CALL_STOPPED })

        handlerGate.complete(Unit)
        drainStarted.await()

        assertFalse(cleanup.isCompleted)
        assertEquals(0, fixture.room.disconnectCalls)
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.CALL_STOPPED })

        drainGate.complete(Unit)
        artifactCloseStarted.await()

        assertFalse(cleanup.isCompleted)
        assertEquals(0, fixture.room.disconnectCalls)
        assertTrue(runtime.events.none { it.name == VoiceAutomationEventName.CALL_STOPPED })

        artifactCloseGate.complete(Unit)
        runCurrent()

        assertEquals("""{"status":"persisted"}""", invocation.await())
        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertEquals(
            listOf(
                "rpc-finished",
                "persistence-drain-started",
                "persistence-drained",
                "artifact-writer-close-started",
                "artifact-writer-closed",
                "persistence-owner-closed",
                "room-disconnect",
                "room-close",
                "call-stopped",
            ),
            orderedStages,
        )
    }

    @Test
    fun `failed persistence drain keeps owner and RPC open until a successful retry`() = runTest {
        val persistence = RecordingPersistenceOwner()
        val drainFailure = IllegalStateException("persistence drain failed")
        persistence.drainFailure = drainFailure
        val fixture = fixture(
            persistenceHandler = persistence::handle,
            persistenceOwner = persistence,
        )
        fixture.session.start()
        runCurrent()

        val first = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        assertSame(drainFailure, (first as VoiceAgentCleanupResult.Failed).error)
        assertTrue(persistence.lifecycle.isEmpty())
        assertTrue(fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC))

        persistence.drainFailure = null
        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd),
        )
        assertEquals(listOf("drain", "close"), persistence.lifecycle)
        assertFalse(fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC))
    }

    @Test
    fun `artifact filesystem failure prevents call stopped and run finalization`() = runTest {
        val root = Files.createTempDirectory("livekit-artifact-write-failure").toFile()
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = backgroundScope,
            )
            writer.drain()
            val blocked = File(
                root,
                "voice-e2e/${VoiceE2EArtifact.VoiceExperiencePrivate.fileName}",
            )
            requireNotNull(blocked.parentFile).mkdirs()
            assertTrue(blocked.mkdir())
            writer(VoiceE2EArtifact.VoiceExperiencePrivate, """{"event":"private"}""")

            var persistenceClosed = false
            val persistenceOwner = object : LiveKitPersistenceOwner {
                override suspend fun drain() {
                    writer.close()
                }

                override fun close() {
                    persistenceClosed = true
                }
            }
            val runtime = SessionRecordingAutomationRuntime()
            val fixture = fixture(
                persistenceHandler = { _, _ -> """{"status":"persisted"}""" },
                persistenceOwner = persistenceOwner,
                automationRuntime = runtime,
            )
            fixture.session.start()
            runCurrent()

            val first = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd)
            val second = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd)

            assertTrue(first is VoiceAgentCleanupResult.Failed)
            assertTrue(second is VoiceAgentCleanupResult.Failed)
            assertSame(
                (first as VoiceAgentCleanupResult.Failed).error.rootCause(),
                (second as VoiceAgentCleanupResult.Failed).error.rootCause(),
            )
            assertFalse(persistenceClosed)
            assertTrue(fixture.room.rpcHandlers.containsKey(LIVEKIT_PERSISTENCE_RPC))
            assertEquals(0, fixture.room.disconnectCalls)
            assertEquals(0, fixture.room.closeCalls)
            assertTrue(runtime.events.none {
                it.name in setOf(
                    VoiceAutomationEventName.CALL_STOPPED,
                    VoiceAutomationEventName.RUN_FINALIZED,
                )
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reserved persistence RPC cannot be supplied without its owner`() = runTest {
        val error = runCatching {
            fixture(
                rpcMethods = mapOf(LIVEKIT_PERSISTENCE_RPC to { "forged-ack" }),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("owned", ignoreCase = true))
    }

    @Test
    fun `cleanup joins in flight connect and event collection before room release`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        fixture.room.connectGate = CompletableDeferred()
        fixture.session.start()
        runCurrent()
        assertTrue("connect" in fixture.room.lifecycle)
        assertTrue("events-started" in fixture.room.lifecycle)

        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }
        runCurrent()

        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertTrue(
            fixture.room.lifecycle.indexOf("connect-finished") < fixture.room.lifecycle.indexOf("disconnect"),
        )
        assertTrue(
            fixture.room.lifecycle.indexOf("events-finished") < fixture.room.lifecycle.indexOf("disconnect"),
        )
        assertTrue(
            fixture.room.lifecycle.indexOf("disconnect") < fixture.room.lifecycle.indexOf("close"),
        )
    }

    @Test
    fun `cleanup preserves canonical cancellation and retries the incomplete stage`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        val cancellation = CancellationException("unregister cancelled")
        fixture.room.unregisterFailure = cancellation
        fixture.session.start()
        runCurrent()

        val thrown = runCatching {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(0, fixture.room.disconnectCalls)
        assertEquals(0, fixture.room.closeCalls)

        fixture.room.unregisterFailure = null

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(2, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `cleanup aggregates independent route and room failures`() = runTest {
        val routeFailure = IllegalStateException("route failed")
        val unregisterFailure = IllegalArgumentException("unregister failed")
        var activeRouteFailure: Throwable? = routeFailure
        val route = OrchestratorFakeRoute {
            activeRouteFailure?.let { throw it }
        }
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
            route = route,
        )
        fixture.room.unregisterFailure = unregisterFailure
        fixture.session.start()
        runCurrent()

        val first = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        val error = (first as VoiceAgentCleanupResult.Failed).error
        assertSame(routeFailure, error)
        assertEquals(listOf(unregisterFailure), error.suppressed.toList())
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(0, fixture.room.disconnectCalls)

        activeRouteFailure = null
        fixture.room.unregisterFailure = null

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(2, fixture.route.retirementCalls)
        assertEquals(2, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `cleanup joins an admitted interrupt before disconnect and release`() = runTest {
        val fixture = fixture()
        val rpcGate = CompletableDeferred<Unit>()
        val rpcTerminationGate = CompletableDeferred<Unit>()
        fixture.room.performRpcGate = rpcGate
        fixture.room.performRpcTerminationGate = rpcTerminationGate
        fixture.session.start()
        runCurrent()

        fixture.session.interrupt()
        runCurrent()
        assertTrue("perform-rpc-started" in fixture.room.lifecycle)

        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }
        runCurrent()
        val completedBeforeRpcTermination = cleanup.isCompleted
        val disconnectsBeforeRpcTermination = fixture.room.disconnectCalls

        rpcGate.complete(Unit)
        rpcTerminationGate.complete(Unit)
        runCurrent()

        assertFalse(completedBeforeRpcTermination)
        assertEquals(0, disconnectsBeforeRpcTermination)
        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertTrue(
            fixture.room.lifecycle.indexOf("perform-rpc-finished") <
                fixture.room.lifecycle.indexOf("disconnect"),
        )
        assertTrue(
            fixture.room.lifecycle.indexOf("disconnect") < fixture.room.lifecycle.indexOf("close"),
        )
    }

    @Test
    fun `interrupt queued before cleanup cannot start after RPC admission closes`() = runTest {
        val queuedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture(sessionScope = queuedScope)
            fixture.session.start()
            runCurrent()

            fixture.session.interrupt()
            assertEquals(
                VoiceAgentCleanupResult.Completed,
                fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
            )
            runCurrent()

            assertTrue(fixture.room.rpcCalls.isEmpty())
            assertFalse("perform-rpc-started" in fixture.room.lifecycle)
        } finally {
            queuedScope.cancel()
        }
    }

    @Test
    fun `cleanup drains an admitted inbound RPC before disconnect and release`() = runTest {
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerGate = CompletableDeferred<Unit>()
        var handlerCompleted = false
        lateinit var fixture: SessionFixture
        fixture = fixture(
            rpcMethods = mapOf(
                "hermes.job.accepted" to {
                    handlerStarted.complete(Unit)
                    handlerGate.await()
                    assertFalse("unregister:hermes.job.accepted" in fixture.room.lifecycle)
                    handlerCompleted = true
                    "persisted"
                },
            ),
        )
        fixture.session.start()
        runCurrent()

        val invocation = async {
            fixture.room.invoke("hermes.job.accepted", AGENT_IDENTITY, "payload")
        }
        runCurrent()
        assertTrue(handlerStarted.isCompleted)

        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }
        runCurrent()
        val completedBeforeHandler = cleanup.isCompleted
        val disconnectsBeforeHandler = fixture.room.disconnectCalls

        handlerGate.complete(Unit)
        runCurrent()

        assertFalse(completedBeforeHandler)
        assertEquals(0, disconnectsBeforeHandler)
        assertEquals("persisted", invocation.await())
        assertTrue(handlerCompleted)
        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertTrue("unregister:hermes.job.accepted" in fixture.room.lifecycle)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `captured inbound RPC handler rejects invocation after admission closes`() = runTest {
        var underlyingCalls = 0
        val fixture = fixture(
            rpcMethods = mapOf(
                "hermes.job.accepted" to {
                    underlyingCalls += 1
                    "persisted"
                },
            ),
        )
        fixture.session.start()
        runCurrent()
        val capturedHandler = fixture.room.captureHandler("hermes.job.accepted")

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        val error = runCatching {
            capturedHandler(LiveKitRpcInvocation(AGENT_IDENTITY, "payload"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("closed", ignoreCase = true))
        assertEquals(0, underlyingCalls)
    }

    @Test
    fun `reconnect events preserve readiness and remote disconnect ends experimental path`() = runTest {
        val runtime = SessionRecordingAutomationRuntime()
        val fixture = fixture(automationRuntime = runtime)
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Reconnecting)
        runCurrent()
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Reconnecting)

        fixture.room.emit(LiveKitRoomEvent.Reconnected)
        runCurrent()
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertEquals(
            listOf(
                VoiceAutomationEventName.RECONNECT_STARTED,
                VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED,
            ),
            runtime.events.map { it.name }.filter {
                it == VoiceAutomationEventName.RECONNECT_STARTED ||
                    it == VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED
            },
        )

        fixture.room.emit(LiveKitRoomEvent.ParticipantDisconnected(AGENT_IDENTITY))
        runCurrent()
        val status = fixture.session.state.value.session
        assertTrue(status is VoiceSessionStatus.Error)
        assertTrue((status as VoiceSessionStatus.Error).message.contains("experimental", ignoreCase = true))
    }

    @Test
    fun `manual reconnect relies on native SDK reconnection without a second connect`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()

        fixture.session.reconnect()
        runCurrent()

        assertEquals(1, fixture.room.connectAttempts)
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertTrue(
            fixture.session.state.value.diagnostics.any { it.name == "livekit_native_reconnect_owned" },
        )
    }

    @Test
    fun `connect failure and readiness timeout map to experimental errors`() = runTest {
        val failed = fixture(connectFailure = IllegalStateException("socket unavailable"))
        failed.session.start()
        runCurrent()
        val failureStatus = failed.session.state.value.session
        assertTrue(failureStatus is VoiceSessionStatus.Error)
        assertTrue((failureStatus as VoiceSessionStatus.Error).message.contains("experimental", ignoreCase = true))

        val timedOut = fixture(readyTimeoutMillis = 1_000)
        timedOut.session.start()
        runCurrent()
        timedOut.room.emit(LiveKitRoomEvent.Connected)
        advanceTimeBy(1_001)
        runCurrent()
        val timeoutStatus = timedOut.session.state.value.session
        assertTrue(timeoutStatus is VoiceSessionStatus.Error)
        assertTrue((timeoutStatus as VoiceSessionStatus.Error).message.contains("timed out", ignoreCase = true))
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        rpcMethods: Map<String, suspend (LiveKitRpcInvocation) -> String> = emptyMap(),
        persistenceHandler: (suspend (callerIdentity: String, payload: String) -> String)? = null,
        persistenceOwner: LiveKitPersistenceOwner? = null,
        connectFailure: Throwable? = null,
        readyTimeoutMillis: Long = 30_000,
        route: OrchestratorFakeRoute = OrchestratorFakeRoute(),
        sessionScope: CoroutineScope = backgroundScope,
        cleanupDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        automationRuntime: VoiceAutomationRuntime? = null,
        automationAudio: SessionAutomationAudioBinding = SessionAutomationAudioBinding(),
        automationAudioProbe: VoiceAutomationAudioProbe? = null,
        captureSource: VoiceCaptureSource = VoiceCaptureSource.Microphone,
        roomLifecycleObserver: (String) -> Unit = {},
    ): SessionFixture {
        val room = FakeLiveKitRoomFacade(
            connectFailure = connectFailure,
            automationAudio = automationAudio,
            lifecycleObserver = roomLifecycleObserver,
        )
        return SessionFixture(
            session = LiveKitVoiceCallSession(
                details = details(),
                traceId = TEST_TRACE_ID,
                room = room,
                routeLease = route.lease,
                scope = sessionScope,
                rpcMethods = rpcMethods,
                persistenceHandler = persistenceHandler?.let { handler ->
                    { invocation -> handler(invocation.callerIdentity, invocation.payload) }
                },
                persistenceOwner = persistenceOwner,
                connectTimeoutMillis = 10_000,
                readyTimeoutMillis = readyTimeoutMillis,
                cleanupDispatcher = cleanupDispatcher,
                automationRuntimeProvider = { automationRuntime },
                automationAudioProbeProvider = { automationAudioProbe },
                captureSource = captureSource,
            ),
            room = room,
            route = route,
        )
    }

    private data class SessionFixture(
        val session: LiveKitVoiceCallSession,
        val room: FakeLiveKitRoomFacade,
        val route: OrchestratorFakeRoute,
    )
}

private class FakeLiveKitRoomFacade(
    private val connectFailure: Throwable? = null,
    override val automationAudio: LiveKitAutomationAudioBinding = SessionAutomationAudioBinding(),
    private val lifecycleObserver: (String) -> Unit = {},
) : LiveKitRoomFacade {
    val lifecycle = mutableListOf<String>()
    private val mutableEvents = MutableSharedFlow<LiveKitRoomEvent>(extraBufferCapacity = 16)
    override val events: Flow<LiveKitRoomEvent> = flow {
        lifecycle += "events-started"
        try {
            mutableEvents.collect { emit(it) }
        } finally {
            lifecycle += "events-finished"
        }
    }
    val connections = mutableListOf<Pair<String, String>>()
    val remoteAudioParticipants = mutableListOf<String>()
    val microphoneValues = mutableListOf<Boolean>()
    val rpcCalls = mutableListOf<Triple<String, String, String>>()
    val rpcHandlers = mutableMapOf<String, suspend (LiveKitRpcInvocation) -> String>()
    var unregisterCalls = 0
    var disconnectCalls = 0
    var closeCalls = 0
    var connectAttempts = 0
    private var connected = false
    var connectGate: CompletableDeferred<Unit>? = null
    var unregisterFailure: Throwable? = null
    var disconnectFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var microphoneGate: CompletableDeferred<Unit>? = null
    var microphoneResult = true
    var microphoneFailure: Throwable? = null
    var performRpcGate: CompletableDeferred<Unit>? = null
    var performRpcTerminationGate: CompletableDeferred<Unit>? = null
    var sdkMicrophoneEnabled = false

    suspend fun emit(event: LiveKitRoomEvent) {
        mutableEvents.emit(event)
    }

    suspend fun invoke(method: String, caller: String, payload: String): String =
        requireNotNull(rpcHandlers[method])(LiveKitRpcInvocation(caller, payload))

    fun captureHandler(method: String): suspend (LiveKitRpcInvocation) -> String =
        requireNotNull(rpcHandlers[method])

    override fun selectRemoteAudioParticipant(participantIdentity: String) {
        lifecycle += "remote-audio:$participantIdentity"
        remoteAudioParticipants += participantIdentity
    }

    override suspend fun connect(url: String, token: String) {
        connectAttempts += 1
        check(!connected) { "Room.connect attempted while room is not disconnected!" }
        lifecycle += "connect"
        connections += url to token
        try {
            connectFailure?.let { throw it }
            connectGate?.await()
            connected = true
        } finally {
            lifecycle += "connect-finished"
        }
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        microphoneValues += enabled
        microphoneGate?.await()
        microphoneFailure?.let { throw it }
        if (microphoneResult) sdkMicrophoneEnabled = enabled
        return microphoneResult
    }

    override suspend fun performRpc(destination: String, method: String, payload: String): String {
        lifecycle += "perform-rpc-started"
        rpcCalls += Triple(destination, method, payload)
        return try {
            performRpcGate?.await()
            "ok"
        } finally {
            withContext(NonCancellable) {
                performRpcTerminationGate?.await()
            }
            lifecycle += "perform-rpc-finished"
        }
    }

    override fun registerRpcMethod(method: String, handler: suspend (LiveKitRpcInvocation) -> String) {
        lifecycle += "register:$method"
        rpcHandlers[method] = handler
    }

    override fun unregisterRpcMethod(method: String) {
        lifecycle += "unregister:$method"
        unregisterCalls += 1
        unregisterFailure?.let { throw it }
        rpcHandlers.remove(method)
    }

    override fun disconnect() {
        lifecycle += "disconnect"
        lifecycleObserver("disconnect")
        disconnectCalls += 1
        disconnectFailure?.let { throw it }
        connected = false
    }

    override fun close() {
        lifecycle += "close"
        lifecycleObserver("close")
        closeCalls += 1
        closeFailure?.let { throw it }
    }
}

private class RecordingPersistenceOwner(
    private val handlerStarted: CompletableDeferred<Unit>? = null,
    private val handlerGate: CompletableDeferred<Unit>? = null,
    private val drainStarted: CompletableDeferred<Unit>? = null,
    private val drainGate: CompletableDeferred<Unit>? = null,
    private val artifactCloseStarted: CompletableDeferred<Unit>? = null,
    private val artifactCloseGate: CompletableDeferred<Unit>? = null,
    private val stageObserver: (String) -> Unit = {},
) : LiveKitPersistenceOwner {
    val events = mutableListOf<String>()
    val lifecycle = mutableListOf<String>()
    var onDrain: () -> Unit = {}
    var drainFailure: Throwable? = null

    suspend fun handle(callerIdentity: String, payload: String): String {
        require(callerIdentity == AGENT_IDENTITY) { "Unexpected LiveKit RPC caller" }
        handlerStarted?.complete(Unit)
        handlerGate?.await()
        events += requireNotNull(Regex(""""eventId":"([^"]+)"""").find(payload)).groupValues[1]
        stageObserver("rpc-finished")
        return """{"status":"persisted"}"""
    }

    override suspend fun drain() {
        onDrain()
        drainFailure?.let { throw it }
        stageObserver("persistence-drain-started")
        drainStarted?.complete(Unit)
        drainGate?.await()
        lifecycle += "drain"
        stageObserver("persistence-drained")
        stageObserver("artifact-writer-close-started")
        artifactCloseStarted?.complete(Unit)
        artifactCloseGate?.await()
        stageObserver("artifact-writer-closed")
    }

    override fun close() {
        lifecycle += "close"
        stageObserver("persistence-owner-closed")
    }
}

private class SessionAutomationAudioBinding : LiveKitAutomationAudioBinding {
    val activations = mutableListOf<String>()
    var closeCalls = 0
        private set

    override fun activate(
        runHash: String,
        captureSource: me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource,
        scope: CoroutineScope,
    ): AutoCloseable {
        activations += runHash
        return AutoCloseable {
            closeCalls += 1
        }
    }

}

private class SessionRecordingAudioProbe(
    private val interruptionCallback: () -> Unit = {},
) : VoiceAutomationAudioProbe {
    var interruptionStarts = 0

    override fun onInjectionStarted(totalBytes: Long) = Unit
    override fun onInjectionChunk(byteCount: Int) = Unit
    override fun onInjectionCompleted() = Unit
    override fun onOutputQueued(byteCount: Int) = Unit
    override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) = Unit
    override fun onOutputDrained() = Unit

    override fun onInterruptionStarted() {
        interruptionCallback()
        interruptionStarts += 1
    }

    override fun onOutputSilenceConfirmed() = Unit
}

private class SessionRecordingAutomationRuntime(
    private val onRecord: (VoiceAutomationEventInput) -> Unit = {},
) : VoiceAutomationRuntime {
    val events = mutableListOf<VoiceAutomationEventInput>()
    var activeRunHash = AUTOMATION_RUN_HASH

    override fun prepare(binding: VoiceAutomationRunBinding) = Unit

    override fun record(event: VoiceAutomationEventInput) {
        events += event
        onRecord(event)
    }

    override fun markReconnectTransportRestored(runHash: String): Boolean {
        if (runHash != activeRunHash) return false
        events += VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED)
        return true
    }

    override fun status() = VoiceAutomationStatus(
        state = VoiceAutomationRunState.Active,
        runHash = activeRunHash,
        comparisonHash = AUTOMATION_COMPARISON_HASH,
        requestedTransport = VoiceAgentTransport.LiveKitExperimental,
        eventCount = events.size.toLong() + 1,
    )

    override fun finalizeRun(): File = error("not used")

    override fun reset() = Unit
}

private fun details() = LiveKitSessionDetails(
    livekitUrl = LIVEKIT_URL,
    participantToken = PARTICIPANT_TOKEN,
    roomName = "rikka_1",
    voiceSessionId = VOICE_SESSION_ID,
    mobileParticipantIdentity = "mobile_lvs_1",
    agentParticipantIdentity = AGENT_IDENTITY,
    dispatchId = "AD_1",
    expiresAt = "2026-07-20T02:00:00Z",
    correlationBinding = LiveKitSessionCorrelationBinding(
        ownerHash = "sha256:${"1".repeat(64)}",
        conversationHash = "sha256:${"2".repeat(64)}",
        voiceSessionHash = "sha256:6dde1c43f223440f4bfba0ed05aa33cb837253ac01e0cadc1d223eff98914e06",
        roomHash = "sha256:3991f60c5217aa9e5a07f65f0fcbdd77e67e3ad561e3b36a0bab7afcea93aeee",
        traceHash = "sha256:${"4".repeat(64)}",
    ),
)

private fun readyJson(
    voiceSessionId: String = VOICE_SESSION_ID,
    observedAt: String = "2026-07-20T00:00:00Z",
    eventIdHash: String = WORKER_EVENT_HASH,
): String =
    """{"version":1,"voiceSessionId":"$voiceSessionId","kind":"ready",""" +
        """"observedAt":"$observedAt","eventIdHash":"$eventIdHash"}"""

private fun acceptedEventJson(): String =
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_accepted","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","prompt":"private question"}"""

private fun Throwable.rootCause(): Throwable {
    var current = this
    while (current.cause != null && current.cause !== current) {
        current = requireNotNull(current.cause)
    }
    return current
}

private const val LIVEKIT_URL = "wss://project.livekit.cloud"
private const val PARTICIPANT_TOKEN = "participant-token"
private const val VOICE_SESSION_ID = "lvs_1"
private const val AGENT_IDENTITY = "agent_lvs_1"
private const val READY_TOPIC = "voice.ready.v1"
private const val INTERRUPT_RPC = "voice.interrupt"
private const val TEST_TRACE_ID = "VA123456-0000000000000000"
private const val WORKER_EVENT_HASH =
    "sha256:3f564e83895a2b6b9ad5e32c6b2c14aea66bdca1c2dc29ddec41a6c0e52c142d"
private const val OTHER_WORKER_EVENT_HASH =
    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
private const val AUTOMATION_RUN_HASH =
    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val AUTOMATION_COMPARISON_HASH =
    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val REPLACEMENT_AUTOMATION_RUN_HASH =
    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
