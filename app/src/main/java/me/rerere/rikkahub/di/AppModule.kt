package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeFileStore
import me.rerere.ai.runtime.contract.RuntimeLogSink
import me.rerere.ai.runtime.contract.TaskBudgetClock
import me.rerere.ai.runtime.contract.TurnConfigSource
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.runtime.AndroidLogSink
import me.rerere.rikkahub.data.ai.runtime.AppModelProviderResolver
import me.rerere.rikkahub.data.ai.runtime.FilesManagerRuntimeFileStore
import me.rerere.rikkahub.data.ai.runtime.MonotonicTaskBudgetClock
import me.rerere.rikkahub.data.ai.runtime.SettingsStoreRuntimeAdapter
import me.rerere.rikkahub.data.ai.runtime.SystemRuntimeClock
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.automation.AutomationKillSwitch
import me.rerere.rikkahub.service.automation.AutomationRuntimeRegistry
import me.rerere.rikkahub.ui.components.EmojiData
import me.rerere.rikkahub.ui.components.EmojiUtils
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.ui.components.ai.chatinput.SoundEffectPlayer
import me.rerere.rikkahub.ui.pages.chat.board.BoardViewModel
import me.rerere.rikkahub.ui.pages.schedule.ScheduleVM
import me.rerere.rikkahub.utils.lifecycle.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.uuid.Uuid

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
        AILoggingManager()
    }

    // The lifecycle-aware subagent orchestrator (SPEC.md M4), the product replacement for the
    // retired SubagentRunner. It drives the child through GenerationHandler.generateText (PreToolUse
    // hook dispatch preserved) and persists the run through the TaskRunStore (TaskRunRepository).
    // The TaskBudgetClock is REQUIRED here: it is what makes the wall-time budget cap enforceable in
    // the shipped build (a missing/zero clock silently disables it — review finding #1).
    single<TaskBudgetClock> { MonotonicTaskBudgetClock() }
    // The single in-memory home for live subagent execution handles (SPEC.md M4): board claim
    // ownership and orphan release both key on the handle ids registered here.
    single { ExecutionHandleRegistry() }
    single {
        TaskCoordinator(
            generationHandler = get(),
            store = get(),
            clock = get(),
        )
    }

    // The chat-side board panel's view model (SPEC.md M5, decision #4). The conversation id is a
    // runtime parameter (the panel lives inside one conversation); it WRITES through the same
    // TaskBoardRepository the board tools use, so a UI edit is validated identically to a tool
    // edit — no UI-only validation path exists.
    viewModel { params ->
        BoardViewModel(
            id = params.get(),
            dao = get(),
            repository = get(),
        )
    }

    // The schedule screen's view model (SPEC.md M5 / task T10). targetAssistantId + optional bound
    // conversation id arrive as runtime params (the screen is opened against one assistant, possibly
    // from inside a live conversation). It WRITES through the same TaskScheduleRepository the schedule
    // tools use, so a UI create is gated identically to a tool create. The ensureConversation seam
    // materializes a "Scheduled task" conversation bound to the target assistant when the screen has
    // no conversation yet, so a UI-created schedule's parent is never TaskCoordinator's Uuid.random()
    // default (spec assumption 5).
    viewModel { params ->
        val conversationRepo = get<me.rerere.rikkahub.data.repository.ConversationRepository>()
        ScheduleVM(
            targetAssistantId = params.get<Uuid>(0),
            initialConversationId = params.get<Uuid?>(1),
            repository = get(),
            ensureConversation = { assistantId ->
                val conversation = me.rerere.rikkahub.data.model.Conversation(
                    assistantId = assistantId,
                    title = "Scheduled task",
                    messageNodes = emptyList(),
                )
                conversationRepo.insertConversation(conversation)
                conversation.id
            },
            rollbackConversation = { id ->
                conversationRepo.getConversationById(id)?.let { conversationRepo.deleteConversation(it) }
            },
        )
    }

    // UI automation (#187 v1). The AccessibilityService is instantiated by the Android system, NOT by
    // Koin, so it cannot be a `single { AccessibilityRuntime }` (that would build a dead, never-
    // connected object). The registry is the faithful equivalent: it exposes the live
    // AccessibilityRuntime.instance as the pure backend. The kill-switch is a process-wide STOP
    // dispatch shared by the overlay and the in-app Stop.
    single { AutomationRuntimeRegistry() }
    single { AutomationKillSwitch() }

    // Neutral :ai-runtime contract adapters (issue #243 slice 3). Bound ONLY here in the :app
    // composition root — :ai-runtime has no Koin dependency, so binding there would violate the
    // module boundary. These carry no per-generation state and are fully functional standalone; the
    // per-generation tool catalog (AppToolCatalog) is constructed by ChatService at generation time
    // (slice 10) because its seams require the conversation's automation guard + processingStatus.
    single<TurnConfigSource> { SettingsStoreRuntimeAdapter(settingsStore = get(), scope = get<AppScope>()) }
    single<ModelProviderResolver> { AppModelProviderResolver(providerManager = get()) }
    single<RuntimeFileStore> { FilesManagerRuntimeFileStore(get()) }
    single<RuntimeLogSink> { AndroidLogSink() }
    single<RuntimeClock> { SystemRuntimeClock() }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            workspaceRepository = get(),
            memoryRecaller = get(),
            generationHandler = get(),
            taskCoordinator = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            taskBoardRepository = get(),
            taskScheduleRepository = get(),
            executionHandles = get(),
            taskRunStore = get(),
            automationRegistry = get(),
            automationKillSwitch = get(),
            hookDispatcher = get()
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
