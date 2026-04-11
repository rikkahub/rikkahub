package com.hefengfan.hffchat.di

import com.hefengfan.hffchat.data.files.FilesManager
import com.hefengfan.hffchat.data.files.SkillManager
import com.hefengfan.hffchat.data.repository.ConversationRepository
import com.hefengfan.hffchat.data.repository.FavoriteRepository
import com.hefengfan.hffchat.data.repository.FilesRepository
import com.hefengfan.hffchat.data.repository.GenMediaRepository
import com.hefengfan.hffchat.data.repository.MemoryRepository
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
