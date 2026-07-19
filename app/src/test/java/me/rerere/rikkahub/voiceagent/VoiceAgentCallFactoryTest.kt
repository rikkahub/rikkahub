package me.rerere.rikkahub.voiceagent

import android.content.ContextWrapper
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class VoiceAgentCallFactoryTest {
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
                audioFactory = { owner ->
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
            val attempt = registry.beginAttempt()
            var disconnectCalls = 0
            val telecomCall = object : VoiceAgentTelecomCall {
                override fun disconnectFromApp() {
                    disconnectCalls += 1
                }
            }
            assertTrue(registry.activate(attempt, telecomCall))
            registry.acknowledgeOutcome(attempt)
            val session = factory.create(
                conversationId = conversationId,
                config = factoryLaunchConfig(voiceModelId = "factory-gemini"),
                routeLease = TelecomVoiceAgentRouteLease(attempt, registry),
                scope = sessionScope,
            )
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
            session.closeNow()
            assertEquals(1, disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(attempt))
        } finally {
            blockedConnect?.release?.complete(Unit)
            sessionScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `default call factory keeps creation failure primary and suppresses lease retirement failure`() = runTest {
        val root = Files.createTempDirectory("voice-factory-failure").toFile()
        val creationFailure = IllegalStateException("session API creation failed")
        val retirementFailure = IllegalArgumentException("Telecom retirement failed")
        val context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageName(): String = "me.rerere.rikkahub.factorytest"
        }
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val telecomCall = object : VoiceAgentTelecomCall {
            override fun disconnectFromApp() {
                throw retirementFailure
            }
        }
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val factory = DefaultVoiceAgentCallFactory(
            context = context,
            chatService = null,
            settingsStore = null,
            okHttpClient = okhttp3.OkHttpClient(),
            observability = NoOpVoiceObservability,
            metadataEpochNowMs = { 1_700_000_010_000 },
            sessionApiFactory = { throw creationFailure },
        )

        try {
            val thrown = runCatching {
                factory.create(
                    conversationId = Uuid.random(),
                    config = factoryLaunchConfig(),
                    routeLease = TelecomVoiceAgentRouteLease(attempt, registry),
                    scope = this,
                )
            }.exceptionOrNull()

            assertSame(creationFailure, thrown)
            assertEquals(listOf(retirementFailure), thrown?.suppressed?.toList())
        } finally {
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

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}

private fun factoryLaunchConfig(
    voiceModelId: String = "gemini-flash",
): VoiceAgentLaunchConfig = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = voiceModelId,
    assistantName = "Hermes",
    assistantPrompt = "system",
)
