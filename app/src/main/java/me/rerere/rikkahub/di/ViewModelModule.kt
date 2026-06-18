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
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBaseVM
import me.rerere.rikkahub.ui.pages.stats.StatsVM
import me.rerere.rikkahub.ui.pages.imggen.ImgGenVM
import me.rerere.rikkahub.ui.pages.extensions.PromptVM
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesVM
import me.rerere.rikkahub.ui.pages.extensions.SkillDetailVM
import me.rerere.rikkahub.ui.pages.extensions.SkillsVM
import me.rerere.rikkahub.ui.components.ui.WorkspaceSheetVM
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceVM
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.setting.providerdetail.ProviderDetailViewModel
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.pages.translator.TranslatorVM
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
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
            shellRunStore = get(),
            workspaceRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::ProviderDetailViewModel)
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
            workspaceRepository = get(),
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
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModelOf(::WorkspaceSheetVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
    viewModel<KnowledgeBaseVM> {
        KnowledgeBaseVM(
            application = get(),
            settingsStore = get(),
            ingestUseCase = get(),
            filesManager = get(),
            knowledgeChunkDao = get(),
        )
    }
}
