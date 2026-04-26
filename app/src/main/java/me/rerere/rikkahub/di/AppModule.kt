package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.groupchat.AutoDiscussManager
import me.rerere.rikkahub.data.ai.groupchat.GroupChatManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.mcp.McpManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UpdateChecker
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
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            groupChatManager = get(),
            autoDiscussManager = get(),
        )
    }

    single {
        GroupChatManager(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            aiLoggingManager = get(),
            mcpManager = get(),
            localTools = get(),
            skillManager = get(),
        )
    }

    single {
        AutoDiscussManager(
            context = get(),
            settingsStore = get(),
            conversationRepository = get(),
            groupChatManager = get(),
            generationHandler = get(),
            inputTransformers = listOf(
                me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer,
                me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer,
                me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer,
                me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer,
                me.rerere.rikkahub.data.ai.transformers.OcrTransformer,
            ),
            outputTransformers = listOf(
                me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer,
                me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer,
                me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer,
            ),
        )
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
