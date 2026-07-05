package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.voiceagent.DefaultVoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallManager
import me.rerere.rikkahub.voiceagent.VoiceAgentNotificationFactory
import me.rerere.rikkahub.voiceagent.VoiceSessionMetadataStore
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomAdapter
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomCallRegistry
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
        LocalTools(get(), get())
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

    single {
        ChatService(
            context = get(),
            appScope = get(),
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

    single<VoiceAgentCallFactory> {
        DefaultVoiceAgentCallFactory(
            context = get(),
            chatService = get(),
            settingsStore = get(),
            okHttpClient = get(),
            observability = get(),
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
        VoiceAgentCallManager(factory = get())
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

    single {
        VoiceAgentTelecomCallRegistry()
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
