package me.rerere.rikkahub.voiceagent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.gemini.OkHttpGeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileApi
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabTraceHeaders
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

interface VoiceAgentCallFactory {
    fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession
}

class DefaultVoiceAgentCallFactory internal constructor(
    private val context: Context,
    private val chatService: ChatService?,
    private val settingsStore: SettingsStore?,
    private val okHttpClient: OkHttpClient,
    private val observability: VoiceObservability,
    private val metadataEpochNowMs: () -> Long,
    private val sessionApiFactory: (VoiceLabMobileApi) -> VoiceSessionApi = { VoiceLabVoiceSessionApi(api = it) },
    private val toolApiFactory: (VoiceLabMobileApi) -> VoiceToolApi = { VoiceLabHermesToolApi(api = it) },
    private val geminiFactory: () -> GeminiLiveVoiceClient = {
        OkHttpGeminiLiveVoiceClient(httpClient = okHttpClient)
    },
    private val audioFactory: () -> VoiceAudioEngine = {
        AndroidVoiceAudioEngine(context = context)
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

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession {
        val baseTraceContext = newVoiceTraceContext()
        val propagatedTraceContext = runCatching {
            observability.withSentryPropagation(baseTraceContext)
        }.getOrDefault(baseTraceContext)
        val (traceContext, traceHeaders) = runCatching {
            propagatedTraceContext to VoiceLabTraceHeaders.from(propagatedTraceContext)
        }.getOrElse {
            runCatching {
                observability.recordEvent(
                    name = "voicelab.mobile.session.ended",
                    trace = propagatedTraceContext,
                    attributes = mapOf("modelId" to config.voiceModelId),
                )
            }
            baseTraceContext to VoiceLabTraceHeaders.from(baseTraceContext)
        }
        return runCatching {
            val mobileApi = VoiceLabMobileApi(
                baseUrl = config.voiceLabBaseUrl,
                credentials = config.credentials,
                traceHeaders = traceHeaders,
            )
            VoiceAgentCallSession(
                modelId = config.voiceModelId,
                sessionApi = sessionApiFactory(mobileApi),
                toolApi = toolApiFactory(mobileApi),
                gemini = geminiFactory(),
                audio = audioFactory(),
                conversationStore = conversationStoreFactory(conversationId),
                contextProvider = contextProviderFactory(config.voiceModelId),
                observability = observability,
                traceContext = traceContext,
                voiceE2EArtifacts = artifactWriterFactory(context.noBackupFilesDir, traceContext, scope),
                sessionMetadata = buildDefaultVoiceE2ESessionMetadata(
                    traceContext = traceContext,
                    conversationId = conversationId,
                    packageName = context.packageName,
                    voiceModelId = config.voiceModelId,
                    startedAtEpochMs = metadataEpochNowMs(),
                ),
                metadataEpochNowMs = metadataEpochNowMs,
                scope = scope,
            )
        }.getOrElse { throwable ->
            runCatching {
                observability.recordEvent(
                    name = "voicelab.mobile.session.ended",
                    trace = traceContext,
                    attributes = mapOf("modelId" to config.voiceModelId),
                )
            }
            throw throwable
        }
    }
}

internal fun buildDefaultVoiceE2ESessionMetadata(
    traceContext: VoiceTraceContext,
    conversationId: Uuid,
    packageName: String,
    voiceModelId: String,
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
