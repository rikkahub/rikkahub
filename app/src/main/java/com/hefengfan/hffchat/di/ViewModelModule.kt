package com.hefengfan.hffchat.di

import com.hefengfan.hffchat.ui.pages.assistant.AssistantVM
import com.hefengfan.hffchat.ui.pages.assistant.detail.AssistantDetailVM
import com.hefengfan.hffchat.ui.pages.backup.BackupVM
import com.hefengfan.hffchat.ui.pages.chat.ChatVM
import com.hefengfan.hffchat.ui.pages.debug.DebugVM
import com.hefengfan.hffchat.ui.pages.developer.DeveloperVM
import com.hefengfan.hffchat.ui.pages.favorite.FavoriteVM
import com.hefengfan.hffchat.ui.pages.search.SearchVM
import com.hefengfan.hffchat.ui.pages.history.HistoryVM
import com.hefengfan.hffchat.ui.pages.stats.StatsVM
import com.hefengfan.hffchat.ui.pages.imggen.ImgGenVM
import com.hefengfan.hffchat.ui.pages.extensions.PromptVM
import com.hefengfan.hffchat.ui.pages.extensions.QuickMessagesVM
import com.hefengfan.hffchat.ui.pages.extensions.SkillDetailVM
import com.hefengfan.hffchat.ui.pages.extensions.SkillsVM
import com.hefengfan.hffchat.ui.pages.setting.SettingVM
import com.hefengfan.hffchat.ui.pages.share.handler.ShareHandlerVM
import com.hefengfan.hffchat.ui.pages.translator.TranslatorVM
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
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
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
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
