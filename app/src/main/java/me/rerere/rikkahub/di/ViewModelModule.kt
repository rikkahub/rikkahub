package me.rerere.rikkahub.di

import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerVM
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.ui.pages.debug.DebugVM
import me.rerere.rikkahub.ui.pages.developer.DeveloperVM
import me.rerere.rikkahub.ui.pages.favorite.FavoriteVM
import me.rerere.rikkahub.ui.pages.search.SearchVM
import me.rerere.rikkahub.ui.pages.history.HistoryVM
import me.rerere.rikkahub.ui.pages.stats.StatsVM
import me.rerere.rikkahub.ui.pages.imggen.ImgGenVM
import me.rerere.rikkahub.ui.pages.extensions.PromptVM
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesVM
import me.rerere.rikkahub.ui.pages.extensions.SkillDetailVM
import me.rerere.rikkahub.ui.pages.extensions.SkillsVM
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.setting.browser.SettingBrowserViewModel
import me.rerere.rikkahub.ui.pages.setting.termux.SettingTermuxViewModel
import me.rerere.rikkahub.ui.pages.setting.locallm.SettingLocalLlmViewModel
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.pages.translator.TranslatorVM
import me.rerere.rikkahub.ui.pages.setting.doctor.DoctorViewModel
import me.rerere.rikkahub.ui.pages.setting.scheduledjobs.ScheduledJobsViewModel
import me.rerere.rikkahub.workflow.ui.WorkflowsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::DeveloperVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModel<SkillsVM> {
        SkillsVM(
            context = get(),
            skillManager = get(),
            urlImporter = get(),
        )
    }
    viewModelOf(::SkillDetailVM)
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
    viewModelOf(::WorkflowsViewModel)
    viewModelOf(::ScheduledJobsViewModel)
    viewModelOf(::DoctorViewModel)
    viewModelOf(::SettingBrowserViewModel)
    viewModelOf(::SettingTermuxViewModel)

    // Phase 22A: parameterised by LocalRuntime — one VM instance per provider tile.
    viewModel<SettingLocalLlmViewModel> { params ->
        SettingLocalLlmViewModel(
            runtime = params.get(),
            context = get(),
            prefs = get(),
            httpClient = get(),
            settingsStore = get(),
        )
    }
}
