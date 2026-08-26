package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatCommandException
import me.rerere.rikkahub.service.ChatFailureCode
import me.rerere.rikkahub.service.ConversationCommands
import me.rerere.rikkahub.service.ConversationQueries
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val settingsStore: SettingsStore,
    private val commands: ConversationCommands,
    private val queries: ConversationQueries,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        queries.listConversations(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            commands.deleteConversation(conversation.id)
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            commands.deleteConversations(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            val snapshot = queries.getConversation(conversationId)
            commands.updatePinned(conversationId, !snapshot.conversation.isPinned)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        queries.listPinnedConversations()

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            commands.restoreConversation(conversation)
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return try {
            queries.getConversation(conversationId).conversation
        } catch (error: ChatCommandException) {
            if (error.failure.code == ChatFailureCode.NotFound) null else throw error
        }
    }
}
