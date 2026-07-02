package me.rerere.rikkahub.voiceagent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.OkHttpGeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
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

class DefaultVoiceAgentCallFactory private constructor(
    private val context: Context,
    private val chatService: ChatService?,
    private val settingsStore: SettingsStore?,
    private val okHttpClient: OkHttpClient?,
    private val observability: VoiceObservability = NoOpVoiceObservability,
    private val dependencies: DefaultVoiceAgentCallFactoryDependencies = AndroidDefaultVoiceAgentCallFactoryDependencies,
    private val metadataEpochNowMs: () -> Long = System::currentTimeMillis,
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
        dependencies = AndroidDefaultVoiceAgentCallFactoryDependencies,
        metadataEpochNowMs = System::currentTimeMillis,
    )

    internal constructor(
        context: Context,
        observability: VoiceObservability,
        dependencies: DefaultVoiceAgentCallFactoryDependencies,
        metadataEpochNowMs: () -> Long = System::currentTimeMillis,
    ) : this(
        context = context,
        chatService = null,
        settingsStore = null,
        okHttpClient = null,
        observability = observability,
        dependencies = dependencies,
        metadataEpochNowMs = metadataEpochNowMs,
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
            VoiceAgentCallSession(
                modelId = config.voiceModelId,
                sessionApi = dependencies.createSessionApi(
                    config = config,
                    traceHeaders = traceHeaders,
                ),
                toolApi = dependencies.createToolApi(
                    config = config,
                    traceHeaders = traceHeaders,
                ),
                gemini = dependencies.createGemini(okHttpClient = okHttpClient),
                audio = dependencies.createAudio(context = context),
                conversationStore = dependencies.createConversationStore(
                    conversationId = conversationId,
                    chatService = chatService,
                ),
                contextProvider = dependencies.createContextProvider(
                    settingsStore = settingsStore,
                    voiceModelName = config.voiceModelId,
                ),
                observability = observability,
                traceContext = traceContext,
                voiceE2EArtifacts = dependencies.createArtifactWriter(
                    noBackupFilesDir = context.noBackupFilesDir,
                    traceContext = traceContext,
                    scope = scope,
                ),
                sessionMetadata = VoiceE2ESessionMetadata(
                    voiceTraceId = traceContext.traceId,
                    voiceSessionId = traceContext.voiceSessionId,
                    conversationId = conversationId.toString(),
                    packageName = context.packageName,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    debuggable = BuildConfig.DEBUG,
                    voiceModelId = config.voiceModelId,
                    providerModel = null,
                    status = "created",
                    startedAtEpochMs = metadataEpochNowMs(),
                    sentryDsnConfigured = BuildConfig.VOICE_AGENT_SENTRY_DSN.isNotBlank(),
                    sentryTracingEnabled = BuildConfig.VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE.toDoubleOrNull()
                        ?.let { it > 0.0 } ?: false,
                    sentryPropagationCreated = traceContext.sentryTrace != null,
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

internal interface DefaultVoiceAgentCallFactoryDependencies {
    fun createSessionApi(
        config: VoiceAgentLaunchConfig,
        traceHeaders: VoiceLabTraceHeaders,
    ): VoiceSessionApi

    fun createToolApi(
        config: VoiceAgentLaunchConfig,
        traceHeaders: VoiceLabTraceHeaders,
    ): VoiceToolApi

    fun createGemini(okHttpClient: OkHttpClient?): GeminiLiveVoiceClient

    fun createAudio(context: Context): VoiceAudioEngine

    fun createConversationStore(
        conversationId: Uuid,
        chatService: ChatService?,
    ): VoiceConversationStore

    fun createContextProvider(
        settingsStore: SettingsStore?,
        voiceModelName: String,
    ): VoiceAgentContextProvider

    fun createArtifactWriter(
        noBackupFilesDir: File,
        traceContext: VoiceTraceContext,
        scope: CoroutineScope,
    ): VoiceE2EArtifactWriter
}

private object AndroidDefaultVoiceAgentCallFactoryDependencies : DefaultVoiceAgentCallFactoryDependencies {
    override fun createSessionApi(
        config: VoiceAgentLaunchConfig,
        traceHeaders: VoiceLabTraceHeaders,
    ): VoiceSessionApi = VoiceLabVoiceSessionApi(
        api = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
            traceHeaders = traceHeaders,
        )
    )

    override fun createToolApi(
        config: VoiceAgentLaunchConfig,
        traceHeaders: VoiceLabTraceHeaders,
    ): VoiceToolApi = VoiceLabHermesToolApi(
        api = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
            traceHeaders = traceHeaders,
        )
    )

    override fun createGemini(okHttpClient: OkHttpClient?): GeminiLiveVoiceClient =
        OkHttpGeminiLiveVoiceClient(httpClient = requireNotNull(okHttpClient))

    override fun createAudio(context: Context): VoiceAudioEngine = AndroidVoiceAudioEngine(context = context)

    override fun createConversationStore(
        conversationId: Uuid,
        chatService: ChatService?,
    ): VoiceConversationStore = ChatServiceVoiceConversationStore(
        conversationId = conversationId,
        chatService = requireNotNull(chatService),
    )

    override fun createContextProvider(
        settingsStore: SettingsStore?,
        voiceModelName: String,
    ): VoiceAgentContextProvider = SettingsVoiceAgentContextProvider(
        settingsStore = requireNotNull(settingsStore),
        voiceModelName = voiceModelName,
    )

    override fun createArtifactWriter(
        noBackupFilesDir: File,
        traceContext: VoiceTraceContext,
        scope: CoroutineScope,
    ): VoiceE2EArtifactWriter = createDefaultVoiceE2EArtifactWriter(
        noBackupFilesDir = noBackupFilesDir,
        traceContext = traceContext,
        scope = scope,
    )
}

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
