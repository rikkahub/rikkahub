package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.ai.memory.EmbeddingMemoryRecaller
import me.rerere.rikkahub.data.ai.memory.MemoryEmbedderResolver
import me.rerere.rikkahub.data.ai.memory.MemoryRecaller
import me.rerere.rikkahub.data.ai.memory.RecencyMemoryRecaller
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.rag.store.RoomVectorStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        MemoryEmbedderResolver(
            providerManager = get(),
            settingsProvider = { get<SettingsStore>().settingsFlow.value },
        )
    }

    single {
        MemoryRepository(
            memoryDAO = get(),
            database = get(),
            memoryVectorDAO = get(),
            embedderResolver = get(),
        )
    }

    // Embedding recall over per-memory vectors, with a recency fallback when no usable embedding
    // model is configured. Both candidate loading and embedder resolution read CURRENT settings per
    // recall (via the repository / resolver) so a model add/change/delete self-heals.
    single<MemoryRecaller> {
        val memoryRepo = get<MemoryRepository>()
        EmbeddingMemoryRecaller(
            loadCandidates = { assistantId ->
                val memories = memoryRepo.getRecalledMemoriesOfAssistant(assistantId)
                val vectorsById = memoryRepo.getMemoryVectors(memories.map { it.id })
                    .associateBy { it.memoryId }
                memories.map { memory ->
                    val row = vectorsById[memory.id]
                    EmbeddingMemoryRecaller.MemoryCandidate(
                        memory = memory,
                        storedVector = row?.let { RoomVectorStore.decodeVector(it.embedding) },
                        storedContentHash = row?.contentHash,
                        storedEmbeddingSpace = row?.embeddingSpace,
                    )
                }
            },
            resolveContext = { get<MemoryEmbedderResolver>().resolve() },
            fallback = RecencyMemoryRecaller(
                loadMemories = { assistantId -> memoryRepo.getRecalledMemoriesOfAssistant(assistantId) },
            ),
        )
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

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }
}
