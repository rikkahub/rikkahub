package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.persistence.VoiceContextBuilder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

interface VoiceConversationStore {
    val conversation: StateFlow<Conversation>
    suspend fun update(transform: (Conversation) -> Conversation)
    fun close() = Unit
}

class ChatServiceVoiceConversationStore(
    private val conversationId: Uuid,
    private val chatService: ChatService,
) : VoiceConversationStore {
    private val closed = AtomicBoolean(false)

    init {
        chatService.addConversationReference(conversationId)
    }

    override val conversation: StateFlow<Conversation> = chatService.getConversationFlow(conversationId)

    override suspend fun update(transform: (Conversation) -> Conversation) {
        val updatedConversation = transform(chatService.getConversationFlow(conversationId).value)
        chatService.saveConversation(conversationId = conversationId, conversation = updatedConversation)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            chatService.removeConversationReference(conversationId)
        }
    }
}

interface VoiceAgentContextProvider {
    fun build(conversation: Conversation): VoiceContext
}

class SettingsVoiceAgentContextProvider(
    private val settingsStore: SettingsStore,
    private val voiceModelName: String = "Gemini Live",
    private val contextBuilder: VoiceContextBuilder = VoiceContextBuilder(),
) : VoiceAgentContextProvider {
    override fun build(conversation: Conversation): VoiceContext {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId)
        return contextBuilder.build(
            assistantName = assistant?.name?.takeIf { it.isNotBlank() } ?: "RikkaHub",
            assistantPrompt = conversation.customSystemPrompt
                ?: assistant?.systemPrompt
                ?: "",
            conversation = conversation,
            voiceModelName = voiceModelName,
            userNickname = settings.displaySetting.userNickname,
        )
    }
}
