package me.rerere.rikkahub.service

import android.net.Uri
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

interface ConversationRuntimePersistence {
    suspend fun load(conversationId: Uuid): Conversation?
    suspend fun persist(conversation: Conversation)
    suspend fun delete(conversation: Conversation)
}

class RepositoryConversationRuntimePersistence(
    private val repository: ConversationRepository,
) : ConversationRuntimePersistence {
    override suspend fun load(conversationId: Uuid): Conversation? =
        repository.getConversationById(conversationId)

    override suspend fun persist(conversation: Conversation) {
        repository.upsertConversation(conversation)
    }

    override suspend fun delete(conversation: Conversation) {
        repository.deleteConversation(conversation)
    }
}

fun interface ConversationFileCleaner {
    fun delete(files: List<Uri>)
}

class FilesManagerConversationFileCleaner(
    private val filesManager: FilesManager,
) : ConversationFileCleaner {
    override fun delete(files: List<Uri>) = filesManager.deleteChatFiles(files)
}
