package me.rerere.rikkahub.voiceagent

import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceSpan
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class VoiceAgentCallFactoryTest {
    @Test
    fun `disabled LiveKit transport is rejected below UI and retires the owned route`() = runTest {
        var directConstructions = 0
        var liveKitConstructions = 0
        val route = OrchestratorFakeRoute()
        val selectingFactory = TransportSelectingVoiceAgentCallFactory(
            directFactoryProvider = {
                directConstructions += 1
                error("Direct factory must not be constructed")
            },
            liveKitFactoryProvider = {
                liveKitConstructions += 1
                error("LiveKit factory must not be constructed")
            },
            liveKitEnabled = false,
        )

        val result = selectingFactory.createOwned(
            request = orchestratorRequest("disabled-livekit").copy(
                transport = VoiceAgentTransport.LiveKitExperimental,
            ),
            routeLease = route.lease,
            scope = this,
        )

        assertTrue(result is VoiceAgentSessionCreationResult.FailedClean)
        assertEquals(1, route.retirementCalls)
        assertEquals(0, directConstructions)
        assertEquals(0, liveKitConstructions)
    }

    @Test
    fun `LiveKit transport bypasses direct factory`() = runTest {
        val directSession = OrchestratorFakeSession()
        val liveKitSession = OrchestratorFakeSession()
        var directConstructions = 0
        var liveKitConstructions = 0
        var directFactory: OrchestratorFakeFactory? = null
        var liveKitFactory: OrchestratorFakeFactory? = null
        val selectingFactory = TransportSelectingVoiceAgentCallFactory(
            directFactoryProvider = {
                directConstructions += 1
                OrchestratorFakeFactory { _, _, _ ->
                    VoiceAgentSessionCreationResult.Created(directSession)
                }.also { directFactory = it }
            },
            liveKitFactoryProvider = {
                liveKitConstructions += 1
                OrchestratorFakeFactory { _, _, _ ->
                    VoiceAgentSessionCreationResult.Created(liveKitSession)
                }.also { liveKitFactory = it }
            },
            liveKitEnabled = true,
        )
        val request = VoiceAgentCallRequest(
            conversationId = Uuid.random(),
            config = factoryLaunchConfig(),
            transport = VoiceAgentTransport.LiveKitExperimental,
        )

        val result = selectingFactory.createOwned(
            request = request,
            routeLease = OrchestratorFakeRoute().lease,
            scope = this,
        )

        assertEquals(0, directConstructions)
        assertEquals(1, liveKitConstructions)
        assertNull(directFactory)
        assertEquals(1, liveKitFactory?.calls)
        assertSame(liveKitSession, (result as VoiceAgentSessionCreationResult.Created).session)
    }

    @Test
    fun `Direct transport bypasses LiveKit factory`() = runTest {
        val directSession = OrchestratorFakeSession()
        var directConstructions = 0
        var liveKitConstructions = 0
        var directFactory: OrchestratorFakeFactory? = null
        var liveKitFactory: OrchestratorFakeFactory? = null
        val selectingFactory = TransportSelectingVoiceAgentCallFactory(
            directFactoryProvider = {
                directConstructions += 1
                OrchestratorFakeFactory { _, _, _ ->
                    VoiceAgentSessionCreationResult.Created(directSession)
                }.also { directFactory = it }
            },
            liveKitFactoryProvider = {
                liveKitConstructions += 1
                OrchestratorFakeFactory { _, _, _ ->
                    error("LiveKit factory must not run")
                }.also { liveKitFactory = it }
            },
            liveKitEnabled = true,
        )

        val result = selectingFactory.createOwned(
            request = VoiceAgentCallRequest(
                conversationId = Uuid.random(),
                config = factoryLaunchConfig(),
                transport = VoiceAgentTransport.DirectGemini,
            ),
            routeLease = OrchestratorFakeRoute().lease,
            scope = this,
        )

        assertEquals(1, directConstructions)
        assertEquals(0, liveKitConstructions)
        assertEquals(1, directFactory?.calls)
        assertNull(liveKitFactory)
        assertSame(directSession, (result as VoiceAgentSessionCreationResult.Created).session)
    }

    @Test
    fun `livekit request never falls back to direct session creation`() = runTest {
        val root = Files.createTempDirectory("voice-factory-livekit-unavailable").toFile()
        val conversationId = Uuid.random()
        var directSessionCreations = 0
        val factory = ownedCreationFactory(root, conversationId) {
            directSessionCreations += 1
            FakeVoiceSessionApi()
        }
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val telecomCall = RecordingFactoryTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        try {
            val result = factory.createOwned(
                request = VoiceAgentCallRequest(
                    conversationId = conversationId,
                    config = factoryLaunchConfig(),
                    transport = VoiceAgentTransport.LiveKitExperimental,
                ),
                routeLease = registry.consumeActiveOutcome(attempt).requireResolvedLease(),
                scope = this,
            )

            assertTrue(result is VoiceAgentSessionCreationResult.FailedClean)
            assertEquals(0, directSessionCreations)
            assertEquals(1, telecomCall.disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(attempt))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `successful owned creation returns Created and leaves route live`() = runTest {
        val root = Files.createTempDirectory("voice-factory-owned-success").toFile()
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())
        val conversationId = Uuid.random()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val telecomCall = RecordingFactoryTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
        val factory = ownedCreationFactory(root, conversationId)
        try {
            val result = factory.createOwned(
                request = VoiceAgentCallRequest(
                    conversationId,
                    factoryLaunchConfig(),
                    VoiceAgentTransport.DirectGemini,
                ),
                routeLease = lease,
                scope = sessionScope,
            )

            assertTrue(result is VoiceAgentSessionCreationResult.Created)
            assertEquals(0, telecomCall.disconnectCalls)
            assertTrue(registry.isOwnedAttemptActive(attempt))

            val created = result as VoiceAgentSessionCreationResult.Created
            assertSame(
                VoiceAgentCleanupResult.Completed,
                created.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
            )
        } finally {
            sessionScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `owned creation failure with successful retirement returns FailedClean`() = runTest {
        val root = Files.createTempDirectory("voice-factory-owned-clean-failure").toFile()
        val creationFailure = IllegalStateException("session API creation failed")
        val conversationId = Uuid.random()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val telecomCall = RecordingFactoryTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val factory = ownedCreationFactory(root, conversationId) { throw creationFailure }
        try {
            val result = factory.createOwned(
                request = VoiceAgentCallRequest(
                    conversationId,
                    factoryLaunchConfig(),
                    VoiceAgentTransport.DirectGemini,
                ),
                routeLease = registry.consumeActiveOutcome(attempt).requireResolvedLease(),
                scope = this,
            )

            assertTrue(result is VoiceAgentSessionCreationResult.FailedClean)
            assertSame(creationFailure, (result as VoiceAgentSessionCreationResult.FailedClean).error)
            assertTrue(creationFailure.suppressed.isEmpty())
            assertEquals(1, telecomCall.disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(attempt))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `owned creation failure with failed retirement returns FailedDirty`() = runTest {
        val root = Files.createTempDirectory("voice-factory-owned-dirty-failure").toFile()
        val creationFailure = IllegalStateException("session API creation failed")
        val retirementFailure = IllegalArgumentException("Telecom retirement failed")
        val conversationId = Uuid.random()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val telecomCall = RecordingFactoryTelecomCall(retirementFailure)
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val factory = ownedCreationFactory(root, conversationId) { throw creationFailure }
        try {
            val result = factory.createOwned(
                request = VoiceAgentCallRequest(
                    conversationId,
                    factoryLaunchConfig(),
                    VoiceAgentTransport.DirectGemini,
                ),
                routeLease = registry.consumeActiveOutcome(attempt).requireResolvedLease(),
                scope = this,
            )

            assertTrue(result is VoiceAgentSessionCreationResult.FailedDirty)
            val dirty = result as VoiceAgentSessionCreationResult.FailedDirty
            assertSame(creationFailure, dirty.error)
            assertEquals(listOf(retirementFailure), creationFailure.suppressed.toList())
            assertEquals(1, telecomCall.disconnectCalls)

            telecomCall.retirementFailure = null
            assertSame(VoiceAgentCleanupResult.Completed, dirty.cleanup.run(VoiceAgentCleanupMode.Immediate))
            assertEquals(2, telecomCall.disconnectCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `owned creation cancellation retires route and throws exact cancellation`() = runTest {
        val root = Files.createTempDirectory("voice-factory-owned-cancellation").toFile()
        val cancellation = CancellationException("creation cancelled")
        val conversationId = Uuid.random()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val telecomCall = RecordingFactoryTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val factory = ownedCreationFactory(root, conversationId) { throw cancellation }
        try {
            val thrown = runCatching {
                factory.createOwned(
                    request = VoiceAgentCallRequest(
                        conversationId,
                        factoryLaunchConfig(),
                        VoiceAgentTransport.DirectGemini,
                    ),
                    routeLease = registry.consumeActiveOutcome(attempt).requireResolvedLease(),
                    scope = this,
                )
            }.exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(1, telecomCall.disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(attempt))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `owned creation cancellation suppresses retirement failure onto exact cancellation`() = runTest {
        val root = Files.createTempDirectory("voice-factory-owned-dirty-cancellation").toFile()
        val cancellation = CancellationException("creation cancelled")
        val retirementFailure = IllegalStateException("Telecom retirement failed")
        val conversationId = Uuid.random()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val telecomCall = RecordingFactoryTelecomCall(retirementFailure)
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val factory = ownedCreationFactory(root, conversationId) { throw cancellation }
        try {
            val thrown = runCatching {
                factory.createOwned(
                    request = VoiceAgentCallRequest(
                        conversationId,
                        factoryLaunchConfig(),
                        VoiceAgentTransport.DirectGemini,
                    ),
                    routeLease = registry.consumeActiveOutcome(attempt).requireResolvedLease(),
                    scope = this,
                )
            }.exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(listOf(retirementFailure), cancellation.suppressed.toList())
            assertEquals(1, telecomCall.disconnectCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `default call factory started session writes propagated metadata through real artifact writer`() = runTest {
        val root = Files.createTempDirectory("voice-e2e-default-factory-session").toFile()
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())
        var blockedConnect: BlockedConnect? = null
        try {
            val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
            val context = object : ContextWrapper(null) {
                override fun getNoBackupFilesDir(): File = root
                override fun getPackageName(): String = "me.rerere.rikkahub.factorytest"
            }
            val observability = PropagatingVoiceObservability()
            val gemini = FakeGeminiLiveVoiceClient()
            blockedConnect = gemini.blockNextConnectCompletion()
            var sessionMobileApi: HermesVoiceApi? = null
            var toolMobileApi: HermesVoiceApi? = null
            var audioRouteOwner: VoiceAudioRouteOwner? = null
            val factory = DefaultVoiceAgentCallFactory(
                context = context,
                chatService = null,
                settingsStore = null,
                okHttpClient = okhttp3.OkHttpClient(),
                observability = observability,
                metadataEpochNowMs = { 1_700_000_010_000 },
                sessionApiFactory = { api ->
                    sessionMobileApi = api
                    FakeVoiceSessionApi()
                },
                toolApiFactory = { api ->
                    toolMobileApi = api
                    FakeVoiceToolApi()
                },
                geminiFactory = { gemini },
                audioFactory = { owner, _ ->
                    audioRouteOwner = owner
                    FakeVoiceAudioEngine()
                },
                conversationStoreFactory = {
                    InMemoryVoiceConversationStore(Conversation.ofId(id = conversationId))
                },
                contextProviderFactory = {
                    FakeVoiceAgentContextProvider(VoiceContext(systemInstruction = "system", turns = emptyList()))
                },
            )
            val registry = VoiceAgentTelecomCallRegistry()
            val attempt = registry.beginAttempt().requireAllocatedAttemptId()
            var disconnectCalls = 0
            val telecomCall = object : VoiceAgentTelecomCall {
                override fun disconnectFromApp() {
                    disconnectCalls += 1
                }
            }
            assertTrue(registry.activate(attempt, telecomCall))
            registry.acknowledgeOutcome(attempt)
            val creation = factory.createOwned(
                request = VoiceAgentCallRequest(
                    conversationId = conversationId,
                    config = factoryLaunchConfig(voiceModelId = "factory-gemini"),
                    transport = VoiceAgentTransport.DirectGemini,
                ),
                routeLease = registry.consumeActiveOutcome(attempt).requireResolvedLease(),
                scope = sessionScope,
            )
            val session = (creation as VoiceAgentSessionCreationResult.Created).session
            assertTrue(sessionMobileApi != null)
            assertSame(sessionMobileApi, toolMobileApi)
            assertEquals(VoiceAudioRouteOwner.Telecom, audioRouteOwner)

            session.start()
            gemini.awaitConnectCount(1)
            assertEquals(0, disconnectCalls)
            assertTrue(registry.isOwnedAttemptActive(attempt))
            val traceId = requireNotNull(observability.propagatedTrace).traceId
            val sessionJson = File(VoiceE2EArtifactPaths.rootDirectory(root), "$traceId/session.json")
            withTimeout(1000) {
                while (!sessionJson.isFile) {
                    delay(10)
                }
            }

            val started = Json.parseToJsonElement(sessionJson.readText()).jsonObject
            assertEquals("started", started.string("status"))
            assertEquals(traceId, started.string("voiceTraceId"))
            assertEquals(observability.propagatedTrace?.voiceSessionId, started.string("voiceSessionId"))
            assertEquals(conversationId.toString(), started.string("conversationId"))
            assertEquals("me.rerere.rikkahub.factorytest", started.string("packageName"))
            assertEquals(BuildConfig.VERSION_NAME, started.string("versionName"))
            assertEquals(BuildConfig.VERSION_CODE, started.string("versionCode"))
            assertEquals("factory-gemini", started.string("voiceModelId"))
            assertEquals("telecom", started.string("audioRouteOwner"))
            assertEquals("1700000010000", started.getValue("startedAtEpochMs").jsonPrimitive.content)
            assertEquals(BuildConfig.DEBUG, started.boolean("debuggable"))
            assertEquals(BuildConfig.VOICE_AGENT_SENTRY_DSN.isNotBlank(), started.boolean("sentryDsnConfigured"))
            assertEquals(
                BuildConfig.VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE.toDoubleOrNull()?.let { it > 0.0 } ?: false,
                started.boolean("sentryTracingEnabled"),
            )
            assertTrue(started.boolean("sentryPropagationCreated"))
            assertSame(
                VoiceAgentCleanupResult.Completed,
                session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
            )
            assertEquals(1, disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(attempt))
        } finally {
            blockedConnect?.release?.complete(Unit)
            sessionScope.cancel()
            root.deleteRecursively()
        }
    }

    private class PropagatingVoiceObservability : VoiceObservability {
        var propagatedTrace: VoiceTraceContext? = null

        override fun withSentryPropagation(trace: VoiceTraceContext): VoiceTraceContext =
            trace.copy(
                sentryTrace = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                sentryBaggage = "sentry-environment=test",
            ).also { propagatedTrace = it }

        override fun recordEvent(
            name: String,
            trace: VoiceTraceContext,
            attributes: Map<String, Any?>,
        ) = Unit

        override suspend fun <T> withSpan(
            name: String,
            trace: VoiceTraceContext,
            block: suspend (VoiceSpan) -> T,
        ): T = block(
            object : VoiceSpan {
                override fun setAttribute(key: String, value: Any?) = Unit
                override fun setAttributes(attributes: Map<String, Any?>) = Unit
            }
        )

        override fun captureException(
            throwable: Throwable,
            trace: VoiceTraceContext,
            attributes: Map<String, Any?>,
        ) = Unit
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun ownedCreationFactory(
        root: File,
        conversationId: Uuid,
        sessionApiFactory: (HermesVoiceApi) -> VoiceSessionApi = { FakeVoiceSessionApi() },
    ): DefaultVoiceAgentCallFactory {
        val context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageName(): String = "me.rerere.rikkahub.factorytest"
        }
        return DefaultVoiceAgentCallFactory(
            context = context,
            chatService = null,
            settingsStore = null,
            okHttpClient = okhttp3.OkHttpClient(),
            observability = NoOpVoiceObservability,
            metadataEpochNowMs = { 1_700_000_010_000 },
            sessionApiFactory = sessionApiFactory,
            toolApiFactory = { FakeVoiceToolApi() },
            geminiFactory = { FakeGeminiLiveVoiceClient() },
            audioFactory = { _, _ -> FakeVoiceAudioEngine() },
            conversationStoreFactory = {
                InMemoryVoiceConversationStore(Conversation.ofId(id = conversationId))
            },
            contextProviderFactory = {
                FakeVoiceAgentContextProvider(VoiceContext(systemInstruction = "system", turns = emptyList()))
            },
        )
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}

private class RecordingFactoryTelecomCall(
    var retirementFailure: Throwable? = null,
) : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        retirementFailure?.let { throw it }
    }
}

private fun factoryLaunchConfig(
    voiceModelId: String = "gemini-flash",
): VoiceAgentLaunchConfig = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = voiceModelId,
    assistantName = "Hermes",
    assistantPrompt = "system",
    directAccountConfigurationHash = "sha256:" + "a".repeat(64),
)
