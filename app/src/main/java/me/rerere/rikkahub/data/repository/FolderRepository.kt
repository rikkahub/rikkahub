package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.model.Folder
import java.time.Instant
import kotlin.uuid.Uuid

class FolderRepository internal constructor(
    private val folderDAO: FolderDAO,
    private val conversationDAO: ConversationDAO,
    private val mutationCoordinator: FolderMutationCoordinator,
) {
    fun getFoldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> {
        return folderDAO.getFoldersOfAssistant(assistantId.toString())
            .map { list -> list.map { it.toFolder() } }
    }

    suspend fun getFolderById(id: Uuid): Folder? {
        return folderDAO.getFolderById(id.toString())?.toFolder()
    }

    suspend fun createFolder(assistantId: Uuid, name: String): Folder {
        val folder = Folder(
            assistantId = assistantId,
            name = name,
            createAt = Instant.now(),
        )
        folderDAO.insert(folder.toEntity())
        return folder
    }

    suspend fun renameFolder(id: Uuid, name: String) {
        folderDAO.rename(id.toString(), name)
    }

    suspend fun moveConversationToFolder(
        conversationId: Uuid,
        folderId: Uuid?,
        onCommitted: () -> Unit = {},
    ): Boolean {
        val result = mutationCoordinator.mutate(
            validate = {
                val conversation = conversationDAO.getConversationById(conversationId.toString())
                conversation != null && (
                    folderId == null ||
                        folderDAO.getFolderById(folderId.toString())?.assistantId == conversation.assistantId
                    )
            },
            mutation = {
                conversationDAO.updateFolderId(conversationId.toString(), folderId?.toString().orEmpty())
            },
            onCommitted = { onCommitted() },
        )
        return result is FolderMutationResult.Applied
    }

    /**
     * 删除文件夹，先把归属该文件夹的会话 folder_id 清空，再删除文件夹本身（不影响会话）。
     */
    suspend fun deleteFolder(id: Uuid, onCommitted: () -> Unit = {}) {
        mutationCoordinator.mutate(
            validate = { true },
            mutation = {
                conversationDAO.clearFolder(id.toString())
                folderDAO.deleteById(id.toString())
            },
            onCommitted = { onCommitted() },
        )
    }
}

internal interface FolderTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

internal class RoomFolderTransactionRunner(
    private val database: AppDatabase,
) : FolderTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}

internal class FolderMutationCoordinator(
    private val transactionRunner: FolderTransactionRunner,
) {
    private val mutex = Mutex()

    suspend fun <T> mutate(
        validate: suspend () -> Boolean,
        mutation: suspend () -> T,
        onCommitted: (T) -> Unit = {},
    ): FolderMutationResult<T> = mutex.withLock {
        val result = transactionRunner.runInTransaction {
            if (validate()) {
                FolderMutationResult.Applied(mutation())
            } else {
                FolderMutationResult.Rejected
            }
        }
        if (result is FolderMutationResult.Applied) {
            onCommitted(result.value)
        }
        result
    }
}

internal sealed interface FolderMutationResult<out T> {
    data class Applied<T>(val value: T) : FolderMutationResult<T>

    data object Rejected : FolderMutationResult<Nothing>
}

private fun FolderEntity.toFolder(): Folder = Folder(
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    name = name,
    sortIndex = sortIndex,
    createAt = Instant.ofEpochMilli(createAt),
)

private fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)
