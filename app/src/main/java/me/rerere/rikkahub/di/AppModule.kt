package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.voiceagent.DefaultVoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.TransportSelectingVoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentAudioRouteResolver
import me.rerere.rikkahub.voiceagent.VoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallOrchestrator
import me.rerere.rikkahub.voiceagent.VoiceAgentCallServiceController
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteResolution
import me.rerere.rikkahub.voiceagent.VoiceAgentNotificationFactory
import me.rerere.rikkahub.voiceagent.VoiceSessionMetadataStore
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomAdapter
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomCallRegistry
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomGateway
import me.rerere.rikkahub.voiceagent.livekit.LiveKitVoiceCallFactory
import me.rerere.rikkahub.voiceagent.automation.DefaultVoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationLifecycle
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.telemetry.SentryVoiceObservabilityConfig
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.createSentryVoiceObservability
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.remoteConfig
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

    single {
        DefaultVoiceAgentCallFactory(
            context = get(),
            chatService = get(),
            settingsStore = get(),
            okHttpClient = get(),
            observability = get(),
        )
    }

    single {
        LiveKitVoiceCallFactory(
            context = get(),
            chatService = get(),
        )
    }

    single<VoiceAgentCallFactory> {
        TransportSelectingVoiceAgentCallFactory(
            directFactoryProvider = { get<DefaultVoiceAgentCallFactory>() },
            liveKitFactoryProvider = { get<LiveKitVoiceCallFactory>() },
            liveKitEnabled = BuildConfig.VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED,
        )
    }

    single<VoiceObservability> {
        createSentryVoiceObservability(
            context = get(),
            config = SentryVoiceObservabilityConfig(
                dsn = BuildConfig.VOICE_AGENT_SENTRY_DSN,
                environment = BuildConfig.VOICE_AGENT_SENTRY_ENVIRONMENT.ifBlank { "development" },
                tracesSampleRate = BuildConfig.VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE
                    .toDoubleOrNull()
                    ?.coerceIn(0.0, 1.0)
                    ?: 0.0,
            ),
            diagnosticRootDirectory = get<android.content.Context>().noBackupFilesDir,
        )
    }

    single {
        VoiceSessionMetadataStore(rootDirectory = get<android.content.Context>().noBackupFilesDir)
    }

    single {
        VoiceAgentNotificationFactory(context = get())
    }

    single {
        VoiceAgentTelecomAdapter(context = get())
    }

    single<VoiceAgentTelecomGateway> {
        get<VoiceAgentTelecomAdapter>()
    }

    single {
        VoiceAgentTelecomCallRegistry()
    }

    single<VoiceAutomationRuntime> {
        DefaultVoiceAutomationRuntime(
            noBackupFilesDir = get<android.content.Context>().noBackupFilesDir,
        )
    }

    if (BuildConfig.DEBUG) {
        single(createdAtStart = true) {
            VoiceAutomationLifecycleRecorder(
                appScope = get(),
                runtime = get(),
            )
        }
    }

    single {
        VoiceAgentAudioRouteResolver(
            gateway = get(),
            registry = get(),
            cleanupScope = get<AppScope>(),
        )
    }

    single {
        val routeResolver = get<VoiceAgentAudioRouteResolver>()
        VoiceAgentCallOrchestrator(
            factory = get(),
            resolveRoute = {
                when (val resolution = routeResolver.resolve()) {
                    is VoiceAgentRouteResolution.Resolved -> resolution.lease
                    is VoiceAgentRouteResolution.CleanupFailed -> throw resolution.error
                    is VoiceAgentRouteResolution.Superseded -> error(
                        "Voice route resolution was superseded by another Telecom attempt",
                    )
                }
            },
            appScope = get<AppScope>(),
        )
    }

    single<VoiceAgentCallServiceController> {
        get<VoiceAgentCallOrchestrator>()
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}

private class VoiceAutomationLifecycleRecorder(
    appScope: AppScope,
    private val runtime: VoiceAutomationRuntime,
) {
    init {
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    val lifecycle = when (event) {
                        Lifecycle.Event.ON_START -> VoiceAutomationLifecycle.FOREGROUND
                        Lifecycle.Event.ON_STOP -> VoiceAutomationLifecycle.BACKGROUND
                        else -> null
                    }
                    if (lifecycle != null && runtime.status().state == VoiceAutomationRunState.Active) {
                        runtime.record(
                            VoiceAutomationEventInput(
                                name = VoiceAutomationEventName.LIFECYCLE_OBSERVED,
                                lifecycle = lifecycle,
                            ),
                        )
                    }
                },
            )
        }
    }
}
