package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtySessionManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxWorkdirServerManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ScheduledPromptManager
import me.rerere.rikkahub.service.ScheduledPromptWorker
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        TermuxCommandManager(get())
    }

    single {
        TermuxWorkdirServerManager(
            appScope = get(),
            settingsStore = get(),
            termuxCommandManager = get(),
        )
    }

    single {
        TermuxPtySessionManager(
            json = get(),
            okHttpClient = get(),
            termuxCommandManager = get(),
            settingsStore = get(),
        )
    }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get())
    }

    single {
        SkillsRepository(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            termuxCommandManager = get(),
        )
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
        AILoggingManager()
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
            termuxCommandManager = get(),
            termuxPtySessionManager = get(),
            mcpManager = get(),
            filesManager = get()
        )
    }

    single {
        ScheduledPromptManager(
            context = get(),
            appScope = get(),
            settingsStore = get()
        )
    }

    workerOf(::ScheduledPromptWorker)

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
