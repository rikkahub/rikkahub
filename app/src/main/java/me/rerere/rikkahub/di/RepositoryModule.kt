package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.CloudMediaResolver
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.cloud.CloudSyncRepository
import me.rerere.rikkahub.data.sync.cloud.ConversationDomainSync
import me.rerere.rikkahub.data.sync.cloud.FavoriteDomainSync
import me.rerere.rikkahub.data.sync.cloud.FileDomainSync
import me.rerere.rikkahub.data.sync.cloud.FolderDomainSync
import me.rerere.rikkahub.data.sync.cloud.MemoryDomainSync
import me.rerere.rikkahub.data.sync.cloud.MessageNodeDomainSync
import me.rerere.rikkahub.data.sync.cloud.SettingsDomainSync
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
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
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                        target = "/skills",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                        target = "/tool_outputs",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                        target = "/upload",
                    ),
                ),
            )
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        CloudMediaResolver(
            context = get(),
            filesRepository = get(),
            cloudSyncRepository = get(),
        )
    }

    single {
        SkillManager(get(), get())
    }

    single {
        CloudSyncRepository(
            appContext = get(),
            appScope = get(),
            outboxDao = get(),
            stateDao = get(),
            revisionDao = get(),
            settingsStore = get(),
            okHttpClient = get(),
        )
    }

    // createdAtStart: observe Settings changes into outbox as soon as process starts
    single(createdAtStart = true) {
        val domain = SettingsDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            settingsStore = get(),
            appScope = get(),
        )
        get<CloudSyncRepository>().settingsDomainSync = domain
        domain
    }

    single(createdAtStart = true) {
        val domain = ConversationDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            conversationDAO = get(),
        )
        get<CloudSyncRepository>().conversationDomainSync = domain
        get<ConversationRepository>().conversationDomainSync = domain
        domain
    }

    single(createdAtStart = true) {
        val domain = FolderDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            folderDAO = get(),
        )
        get<CloudSyncRepository>().folderDomainSync = domain
        get<FolderRepository>().folderDomainSync = domain
        domain
    }

    single(createdAtStart = true) {
        val domain = MessageNodeDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            messageNodeDAO = get(),
            conversationDAO = get(),
        )
        get<CloudSyncRepository>().messageNodeDomainSync = domain
        get<ConversationRepository>().messageNodeDomainSync = domain
        domain
    }

    single(createdAtStart = true) {
        val domain = MemoryDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            memoryDAO = get(),
        )
        get<CloudSyncRepository>().memoryDomainSync = domain
        get<MemoryRepository>().memoryDomainSync = domain
        domain
    }

    single(createdAtStart = true) {
        val domain = FavoriteDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            favoriteDAO = get(),
        )
        get<CloudSyncRepository>().favoriteDomainSync = domain
        get<FavoriteRepository>().favoriteDomainSync = domain
        domain
    }

    single(createdAtStart = true) {
        val domain = FileDomainSync(
            cloudSyncRepository = get(),
            revisionDao = get(),
            filesRepository = get(),
        )
        get<CloudSyncRepository>().fileDomainSync = domain
        get<FilesRepository>().fileDomainSync = domain
        domain
    }
}
