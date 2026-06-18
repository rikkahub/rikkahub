package me.rerere.rikkahub.voiceagent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
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

class DefaultVoiceAgentCallFactory(
    private val context: Context,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
    private val observability: VoiceObservability = NoOpVoiceObservability,
) : VoiceAgentCallFactory {
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
            val voiceLabApi = VoiceLabMobileApi(
                baseUrl = config.voiceLabBaseUrl,
                credentials = config.credentials,
                traceHeaders = traceHeaders,
            )
            VoiceAgentCallSession(
                modelId = config.voiceModelId,
                sessionApi = VoiceLabVoiceSessionApi(api = voiceLabApi),
                toolApi = VoiceLabHermesToolApi(api = voiceLabApi),
                gemini = OkHttpGeminiLiveVoiceClient(httpClient = okHttpClient),
                audio = AndroidVoiceAudioEngine(context = context),
                conversationStore = ChatServiceVoiceConversationStore(
                    conversationId = conversationId,
                    chatService = chatService,
                ),
                contextProvider = SettingsVoiceAgentContextProvider(
                    settingsStore = settingsStore,
                    voiceModelName = config.voiceModelId,
                ),
                observability = observability,
                traceContext = traceContext,
                voiceE2EArtifacts = createDefaultVoiceE2EArtifactWriter(
                    config = config,
                    noBackupFilesDir = context.noBackupFilesDir,
                    traceContext = traceContext,
                    scope = scope,
                ),
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

internal fun createDefaultVoiceE2EArtifactWriter(
    config: VoiceAgentLaunchConfig,
    noBackupFilesDir: File,
    traceContext: VoiceTraceContext,
    scope: CoroutineScope,
): VoiceE2EArtifactWriter = VoiceE2EArtifactWriter.create(
    enabled = config.enableVoiceE2EArtifacts,
    rootDirectory = noBackupFilesDir,
    traceId = traceContext.traceId,
    scope = scope,
)
