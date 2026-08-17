package me.rerere.rikkahub.voiceagent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.gemini.OkHttpGeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceLatencyTelemetryCoordinator
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceTraceHeaders
import okhttp3.OkHttpClient
import java.io.File
import kotlin.uuid.Uuid

interface ManagedVoiceCallSession {
    val state: StateFlow<VoiceAgentUiState>
    fun start()
    fun interrupt()
    fun setMuted(value: Boolean)
    fun reconnect()
    fun recordDiagnostic(name: String, detail: String)
    fun end()
    suspend fun endAndDrain()
    fun closeNow()
}

internal interface VoiceAgentCallFactory {
    suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long = VOICE_AGENT_END_DRAIN_TIMEOUT_MS,
    ): VoiceAgentSessionCreationResult
}

internal class TransportSelectingVoiceAgentCallFactory(
    private val directFactoryProvider: () -> VoiceAgentCallFactory,
    private val liveKitFactoryProvider: () -> VoiceAgentCallFactory,
    private val liveKitEnabled: Boolean,
) : VoiceAgentCallFactory {
    override suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long,
    ): VoiceAgentSessionCreationResult = when (request.transport) {
        VoiceAgentTransport.DirectGemini -> directFactoryProvider().createOwned(
            request,
            routeLease,
            scope,
            endDrainTimeoutMillis,
        )
        VoiceAgentTransport.LiveKitExperimental -> if (liveKitEnabled) {
            liveKitFactoryProvider().createOwned(request, routeLease, scope, endDrainTimeoutMillis)
        } else {
            finishFailedOwnedVoiceSessionCreation(
                creationError = IllegalStateException("LiveKit experimental voice transport is disabled"),
                cleanup = voiceAgentRouteCleanupOperation(routeLease),
            )
        }
    }
}

internal sealed interface VoiceAgentSessionCreationResult {
    data class Created(
        val session: RouteOwnedManagedVoiceCallSession,
    ) : VoiceAgentSessionCreationResult

    data class FailedClean(
        val error: Throwable,
    ) : VoiceAgentSessionCreationResult

    data class FailedDirty(
        val error: Throwable,
        val cleanup: VoiceAgentCleanupOperation,
    ) : VoiceAgentSessionCreationResult
}

internal class DefaultVoiceAgentCallFactory internal constructor(
    private val context: Context,
    private val chatService: ChatService?,
    private val settingsStore: SettingsStore?,
    private val okHttpClient: OkHttpClient,
    private val observability: VoiceObservability,
    private val metadataEpochNowMs: () -> Long,
    private val sessionApiFactory: (HermesVoiceApi) -> VoiceSessionApi = { HermesVoiceSessionApi(api = it) },
    private val toolApiFactory: (HermesVoiceApi) -> VoiceToolApi = { HermesVoiceToolApi(api = it) },
    private val geminiFactory: () -> GeminiLiveVoiceClient = {
        OkHttpGeminiLiveVoiceClient(httpClient = okHttpClient)
    },
    private val audioFactory: (VoiceAudioRouteOwner, VoiceCaptureSource) -> VoiceAudioEngine = { owner, source ->
        AndroidVoiceAudioEngine(context = context, routeOwner = owner, captureSource = source)
    },
    private val conversationStoreFactory: (Uuid) -> VoiceConversationStore = { conversationId ->
        ChatServiceVoiceConversationStore(
            conversationId = conversationId,
            chatService = requireNotNull(chatService) { "chatService is required for default conversation store" },
        )
    },
    private val contextProviderFactory: (String) -> VoiceAgentContextProvider = { voiceModelId ->
        SettingsVoiceAgentContextProvider(
            settingsStore = requireNotNull(settingsStore) { "settingsStore is required for default context provider" },
            voiceModelName = voiceModelId,
        )
    },
    private val artifactWriterFactory: (File, VoiceTraceContext, CoroutineScope) -> VoiceE2EArtifactWriter =
        ::createDefaultVoiceE2EArtifactWriter,
) : VoiceAgentCallFactory {
    constructor(
        context: Context,
        chatService: ChatService,
        settingsStore: SettingsStore,
        okHttpClient: OkHttpClient,
        observability: VoiceObservability = NoOpVoiceObservability,
    ) : this(
        context = context,
        chatService = chatService,
        settingsStore = settingsStore,
        okHttpClient = okHttpClient,
        observability = observability,
        metadataEpochNowMs = System::currentTimeMillis,
    )

    override suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long,
    ): VoiceAgentSessionCreationResult {
        val cleanup = voiceAgentRouteCleanupOperation(routeLease)
        return try {
            VoiceAgentSessionCreationResult.Created(
                when (request.transport) {
                    VoiceAgentTransport.DirectGemini -> createSession(
                        request.conversationId,
                        request.config,
                        routeLease,
                        scope,
                        endDrainTimeoutMillis,
                        request.captureFixtureToken,
                    )
                    VoiceAgentTransport.LiveKitExperimental -> {
                        throw VoiceAgentCallConfigurationException(
                            "LiveKit experimental voice transport is unavailable",
                        )
                    }
                },
            )
        } catch (creationError: Throwable) {
            finishFailedOwnedVoiceSessionCreation(creationError, cleanup)
        }
    }

    private fun createSession(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long,
        captureFixtureToken: String?,
    ): RouteOwnedManagedVoiceCallSession {
        val route = routeLease.metadata
        val baseTraceContext = newVoiceTraceContext()
        val propagatedTraceContext = runCatching {
            observability.withSentryPropagation(baseTraceContext)
        }.getOrDefault(baseTraceContext)
        val (traceContext, traceHeaders) = runCatching {
            propagatedTraceContext to HermesVoiceTraceHeaders.from(propagatedTraceContext)
        }.getOrElse {
            runCatching {
                observability.recordEvent(
                    name = "hermes_voice.mobile.session.ended",
                    trace = propagatedTraceContext,
                    attributes = mapOf("modelId" to config.voiceModelId),
                )
            }
            baseTraceContext to HermesVoiceTraceHeaders.from(baseTraceContext)
        }
        val captureSource = VoiceCaptureFixtureArming.claimSource(captureFixtureToken)
            .getOrElse { cause ->
                throw VoiceAgentCallConfigurationException("Capture fixture token is not armed")
                    .also { it.initCause(cause) }
            }
        var audio: VoiceAudioEngine? = null
        val coreSession = runCatching {
            val mobileApi = HermesVoiceApi(
                baseUrl = config.hermesVoiceBaseUrl,
                credentials = config.credentials,
                traceHeaders = traceHeaders,
            )
            audio = audioFactory(route.owner, captureSource)
            val telemetryCoordinator = VoiceLatencyTelemetryCoordinator(
                traceContext = traceContext,
                transport = "DirectGemini",
                observability = observability,
            )
            VoiceAgentCallSession(
                modelId = config.voiceModelId,
                sessionApi = sessionApiFactory(mobileApi),
                toolApi = toolApiFactory(mobileApi),
                gemini = geminiFactory(),
                audio = checkNotNull(audio),
                conversationStore = conversationStoreFactory(conversationId),
                contextProvider = contextProviderFactory(config.voiceModelId),
                observability = observability,
                traceContext = traceContext,
                telemetryCoordinator = telemetryCoordinator,
                voiceE2EArtifacts = artifactWriterFactory(context.noBackupFilesDir, traceContext, scope),
                sessionMetadata = buildDefaultVoiceE2ESessionMetadata(
                    traceContext = traceContext,
                    conversationId = conversationId,
                    packageName = context.packageName,
                    voiceModelId = config.voiceModelId,
                    routeOwner = route.owner,
                    startedAtEpochMs = metadataEpochNowMs(),
                ),
                metadataEpochNowMs = metadataEpochNowMs,
                scope = scope,
                directConfigurationBinding = VoiceDirectConfigurationBinding(
                    directAccountConfigurationHash = config.directAccountConfigurationHash,
                    conversationHash = voiceConfigurationIdentity(conversationId.toString()),
                ),
            )
        }.getOrElse { throwable ->
            runCatching { audio?.release() }
            captureSource.close()
            runCatching {
                observability.recordEvent(
                    name = "hermes_voice.mobile.session.ended",
                    trace = traceContext,
                    attributes = mapOf("modelId" to config.voiceModelId),
                )
            }
            throw throwable
        }
        return RouteOwnedVoiceCallSession(coreSession, routeLease, endDrainTimeoutMillis)
    }

}

internal suspend fun finishFailedOwnedVoiceSessionCreation(
    creationError: Throwable,
    cleanup: VoiceAgentCleanupOperation,
): VoiceAgentSessionCreationResult {
    val cleanupResult = try {
        cleanup.run(VoiceAgentCleanupMode.Immediate)
    } catch (cleanupCancellation: CancellationException) {
        val canonical = cleanupCancellation.canonicalVoiceAgentCancellation()
        canonical.addVoiceAgentSuppressedDistinct(creationError.canonicalIfCancellation())
        throw canonical
    }
    if (creationError is CancellationException) {
        val canonical = creationError.canonicalVoiceAgentCancellation()
        if (cleanupResult is VoiceAgentCleanupResult.Failed) {
            canonical.addVoiceAgentSuppressedDistinct(cleanupResult.error)
        }
        throw canonical
    }
    return when (cleanupResult) {
        VoiceAgentCleanupResult.Completed -> VoiceAgentSessionCreationResult.FailedClean(creationError)
        is VoiceAgentCleanupResult.Failed -> {
            creationError.addVoiceAgentSuppressedDistinct(cleanupResult.error)
            VoiceAgentSessionCreationResult.FailedDirty(creationError, cleanup)
        }
    }
}

private fun Throwable.canonicalIfCancellation(): Throwable =
    (this as? CancellationException)?.canonicalVoiceAgentCancellation() ?: this

internal fun buildDefaultVoiceE2ESessionMetadata(
    traceContext: VoiceTraceContext,
    conversationId: Uuid,
    packageName: String,
    voiceModelId: String,
    routeOwner: VoiceAudioRouteOwner,
    startedAtEpochMs: Long,
): VoiceE2ESessionMetadata = VoiceE2ESessionMetadata(
    voiceTraceId = traceContext.traceId,
    voiceSessionId = traceContext.voiceSessionId,
    conversationId = conversationId.toString(),
    packageName = packageName,
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    debuggable = BuildConfig.DEBUG,
    voiceModelId = voiceModelId,
    audioRouteOwner = routeOwner.diagnosticLabel,
    providerModel = null,
    status = "created",
    startedAtEpochMs = startedAtEpochMs,
    sentryDsnConfigured = BuildConfig.VOICE_AGENT_SENTRY_DSN.isNotBlank(),
    sentryTracingEnabled = BuildConfig.VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE.toDoubleOrNull()
        ?.let { it > 0.0 } ?: false,
    sentryPropagationCreated = traceContext.sentryTrace != null,
)

internal fun createDefaultVoiceE2EArtifactWriter(
    noBackupFilesDir: File,
    traceContext: VoiceTraceContext,
    scope: CoroutineScope,
): VoiceE2EArtifactWriter = VoiceE2EArtifactWriter.create(
    enabled = true,
    rootDirectory = noBackupFilesDir,
    traceId = traceContext.traceId,
    scope = scope,
)
