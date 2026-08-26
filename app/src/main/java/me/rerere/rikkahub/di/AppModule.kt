package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.GenerationPlanFactory
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ConversationCommands
import me.rerere.rikkahub.service.ConversationGenerationCoordinator
import me.rerere.rikkahub.service.ConversationPostProcessor
import me.rerere.rikkahub.service.ConversationQueries
import me.rerere.rikkahub.service.ConversationRuntimeStore
import me.rerere.rikkahub.service.FilesManagerConversationFileCleaner
import me.rerere.rikkahub.service.RepositoryConversationRuntimePersistence
import me.rerere.rikkahub.service.DefaultConversationApplication
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
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
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // 生成通知与业务解耦：应用层只发事件，通知由这里消费；
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
        RepositoryConversationRuntimePersistence(repository = get())
    }

    single {
        FilesManagerConversationFileCleaner(filesManager = get())
    }

    single {
        ConversationRuntimeStore(
            // ConversationRuntimeStore intentionally accepts CoroutineScope so tests can
            // inject TestScope. Koin resolves by the declared type at this call site, so
            // request the concrete application scope explicitly.
            appScope = get<AppScope>(),
            persistence = get<RepositoryConversationRuntimePersistence>(),
            fileCleaner = get<FilesManagerConversationFileCleaner>(),
        )
    }

    single {
        GenerationPlanFactory(
            json = get(),
            settingsStore = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            templateTransformer = get(),
            ocrTransformer = get(),
        )
    }

    single {
        ConversationPostProcessor(
            settingsStore = get(),
            providerManager = get(),
            runtimeStore = get(),
        )
    }

    single {
        ConversationGenerationCoordinator(
            context = get(),
            appScope = get(),
            runtimeStore = get(),
            planFactory = get(),
            engine = get(),
            postProcessor = get(),
            appEventBus = get(),
        )
    }

    single {
        DefaultConversationApplication(
            settingsStore = get(),
            conversationRepository = get(),
            folderRepository = get(),
            filesManager = get(),
            runtimeStore = get(),
            generationCoordinator = get(),
            translationService = get(),
            postProcessor = get(),
        )
    }
    single<ConversationCommands> { get<DefaultConversationApplication>() }
    single<ConversationQueries> { get<DefaultConversationApplication>() }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            conversationCommands = get(),
            conversationQueries = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
