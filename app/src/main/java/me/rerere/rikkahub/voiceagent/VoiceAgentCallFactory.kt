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

class DefaultVoiceAgentCallFactory internal constructor(
    private val context: Context,
    private val sessionFactory: DefaultVoiceAgentManagedSessionFactory,
    private val observability: VoiceObservability,
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
        sessionFactory = AndroidDefaultVoiceAgentManagedSessionFactory(
            chatService = chatService,
            settingsStore = settingsStore,
            okHttpClient = okHttpClient,
        ),
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
            sessionFactory.create(
                DefaultVoiceAgentManagedSessionRequest(
                    context = context,
                    conversationId = conversationId,
                    config = config,
                    traceContext = traceContext,
                    traceHeaders = traceHeaders,
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
                    observability = observability,
                    metadataEpochNowMs = metadataEpochNowMs,
                    scope = scope,
                )
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

internal fun interface DefaultVoiceAgentManagedSessionFactory {
    fun create(request: DefaultVoiceAgentManagedSessionRequest): ManagedVoiceCallSession
}

internal data class DefaultVoiceAgentManagedSessionRequest(
    val context: Context,
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val traceContext: VoiceTraceContext,
    val traceHeaders: VoiceLabTraceHeaders,
    val sessionMetadata: VoiceE2ESessionMetadata,
    val observability: VoiceObservability,
    val metadataEpochNowMs: () -> Long,
    val scope: CoroutineScope,
)

private class AndroidDefaultVoiceAgentManagedSessionFactory(
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) : DefaultVoiceAgentManagedSessionFactory {
    override fun create(request: DefaultVoiceAgentManagedSessionRequest): ManagedVoiceCallSession =
        VoiceAgentCallSession(
            modelId = request.config.voiceModelId,
            sessionApi = createSessionApi(
                config = request.config,
                traceHeaders = request.traceHeaders,
            ),
            toolApi = createToolApi(
                config = request.config,
                traceHeaders = request.traceHeaders,
            ),
            gemini = createGemini(),
            audio = createAudio(context = request.context),
            conversationStore = createConversationStore(conversationId = request.conversationId),
            contextProvider = createContextProvider(voiceModelName = request.config.voiceModelId),
            observability = request.observability,
            traceContext = request.traceContext,
            voiceE2EArtifacts = createArtifactWriter(
                noBackupFilesDir = request.context.noBackupFilesDir,
                traceContext = request.traceContext,
                scope = request.scope,
            ),
            sessionMetadata = request.sessionMetadata,
            metadataEpochNowMs = request.metadataEpochNowMs,
            scope = request.scope,
        )

    private fun createSessionApi(
        config: VoiceAgentLaunchConfig,
        traceHeaders: VoiceLabTraceHeaders,
    ): VoiceSessionApi = VoiceLabVoiceSessionApi(
        api = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
            traceHeaders = traceHeaders,
        )
    )

    private fun createToolApi(
        config: VoiceAgentLaunchConfig,
        traceHeaders: VoiceLabTraceHeaders,
    ): VoiceToolApi = VoiceLabHermesToolApi(
        api = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
            traceHeaders = traceHeaders,
        )
    )

    private fun createGemini(): GeminiLiveVoiceClient =
        OkHttpGeminiLiveVoiceClient(httpClient = okHttpClient)

    private fun createAudio(context: Context): VoiceAudioEngine = AndroidVoiceAudioEngine(context = context)

    private fun createConversationStore(
        conversationId: Uuid,
    ): VoiceConversationStore = ChatServiceVoiceConversationStore(
        conversationId = conversationId,
        chatService = chatService,
    )

    private fun createContextProvider(
        voiceModelName: String,
    ): VoiceAgentContextProvider = SettingsVoiceAgentContextProvider(
        settingsStore = settingsStore,
        voiceModelName = voiceModelName,
    )

    private fun createArtifactWriter(
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
