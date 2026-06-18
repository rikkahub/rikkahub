package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            chatService.deleteConversation(conversation)
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            // Route each delete through ChatService so it is tombstoned + its live generation job
            // cancelled before the DB delete; the repo bulk delete bypasses that and a finalizer can
            // resurrect an in-flight conversation.
            conversationRepo.getConversationsOfAssistant(assistant.id).first().forEach { conversation ->
                chatService.deleteConversation(conversation)
            }
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            // Through ChatService so the delete tombstone is cleared; a direct repo insert would leave
            // the id tombstoned and saveConversation would keep no-op'ing for the restored chat.
            chatService.restoreConversation(conversation)
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }
}
