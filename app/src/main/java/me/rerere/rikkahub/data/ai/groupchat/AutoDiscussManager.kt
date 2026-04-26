package me.rerere.rikkahub.data.ai.groupchat

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatParticipant
import me.rerere.rikkahub.data.model.SpeakingOrder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import kotlin.uuid.Uuid

private const val TAG = "AutoDiscussManager"
private const val AUTO_DISCUSS_CONTINUE_PROMPT_KEY = "auto_discuss_continue"
private const val DEFAULT_CONTINUE_PROMPT = "Continue the discussion. Share your thoughts on what has been said so far."

data class AutoDiscussState(
    val isRunning: Boolean = false,
    val conversationId: Uuid? = null,
    val totalRounds: Int = 0,
    val completedRounds: Int = 0,
    val remainingRounds: Int = 0,
    val currentParticipant: GroupChatParticipant? = null,
    val error: Throwable? = null,
)

class AutoDiscussManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val groupChatManager: GroupChatManager,
    private val generationHandler: GenerationHandler,
    private val inputTransformers: List<InputMessageTransformer>,
    private val outputTransformers: List<OutputMessageTransformer>,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var autoDiscussJob: Job? = null

    private val _autoDiscussState = MutableStateFlow(AutoDiscussState())
    val autoDiscussState: StateFlow<AutoDiscussState> = _autoDiscussState.asStateFlow()

    fun isAutoDiscussRunning(): Boolean {
        return autoDiscussJob?.isActive == true
    }

    suspend fun startAutoDiscuss(
        conversationId: Uuid,
        rounds: Int,
        topicText: String? = null,
        onMessageGenerated: suspend (List<UIMessage>) -> Unit,
        onStateChange: suspend (AutoDiscussState) -> Unit,
    ) {
        if (autoDiscussJob?.isActive == true) {
            return
        }

        val conversation = conversationRepository.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found: $conversationId")

        if (!groupChatManager.isGroupChat(conversation)) {
            throw IllegalStateException("Auto-discuss only available for group chats with at least 2 participants")
        }

        val participants = groupChatManager.getEnabledParticipants(conversation)
        if (participants.size < 2) {
            throw IllegalStateException("Need at least 2 enabled participants for auto-discuss")
        }

        autoDiscussJob = scope.launch {
            try {
                _autoDiscussState.update {
                    AutoDiscussState(
                        isRunning = true,
                        conversationId = conversationId,
                        totalRounds = rounds,
                        completedRounds = 0,
                        remainingRounds = rounds,
                        currentParticipant = null,
                        error = null,
                    )
                }
                onStateChange(_autoDiscussState.value)

                val settings = settingsStore.settingsFlow.first()
                var currentConversation = conversation

                if (!topicText.isNullOrBlank()) {
                    val userMessage = UIMessage.user(topicText.trim())
                    val updatedConversation = currentConversation.copy(
                        messageNodes = currentConversation.messageNodes + userMessage.toMessageNode(),
                    )
                    conversationRepository.updateConversation(updatedConversation)
                    onMessageGenerated(listOf(userMessage))
                    currentConversation = updatedConversation
                }

                for (round in 0 until rounds) {
                    if (!isActive) break

                    val orderedParticipants = getOrderedParticipants(
                        participants = participants,
                        speakingOrder = currentConversation.groupChatConfig.speakingOrder,
                        round = round,
                    )

                    for ((index, participant) in orderedParticipants.withIndex()) {
                        if (!isActive) break

                        _autoDiscussState.update {
                            it.copy(
                                currentParticipant = participant,
                            )
                        }
                        onStateChange(_autoDiscussState.value)

                        try {
                            val participantConversation = conversationRepository.getConversationById(conversationId)
                                ?: continue

                            val messages = mutableListOf<UIMessage>()
                            groupChatManager.generateForParticipant(
                                conversation = participantConversation,
                                participant = participant,
                                settings = settings,
                                getAssistant = { id -> settings.assistants.find { it.id == id } },
                                getModelById = { id -> settings.findModelById(id) },
                                inputTransformers = inputTransformers,
                                outputTransformers = outputTransformers,
                                generationHandler = generationHandler,
                            ).collect { chunk ->
                                when (chunk) {
                                    is GenerationChunk.Messages -> {
                                        messages.clear()
                                        messages.addAll(chunk.messages)
                                        onMessageGenerated(chunk.messages)
                                    }
                                }
                            }

                            delay(500)

                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _autoDiscussState.update {
                                it.copy(error = e)
                            }
                            onStateChange(_autoDiscussState.value)
                        }
                    }

                    _autoDiscussState.update {
                        it.copy(
                            completedRounds = round + 1,
                            remainingRounds = rounds - round - 1,
                            currentParticipant = null,
                        )
                    }
                    onStateChange(_autoDiscussState.value)

                    if (round < rounds - 1) {
                        val continuePrompt = getContinuePrompt(settings)
                        val continueMessage = UIMessage.user(continuePrompt)
                        val latestConversation = conversationRepository.getConversationById(conversationId)
                            ?: continue
                        val updatedConversation = latestConversation.copy(
                            messageNodes = latestConversation.messageNodes + continueMessage.toMessageNode(),
                        )
                        conversationRepository.updateConversation(updatedConversation)
                        onMessageGenerated(listOf(continueMessage))
                        delay(300)
                    }
                }

                _autoDiscussState.update {
                    it.copy(
                        isRunning = false,
                        currentParticipant = null,
                    )
                }
                onStateChange(_autoDiscussState.value)

            } catch (e: CancellationException) {
                _autoDiscussState.update {
                    it.copy(
                        isRunning = false,
                        currentParticipant = null,
                    )
                }
                onStateChange(_autoDiscussState.value)
                throw e
            } catch (e: Exception) {
                _autoDiscussState.update {
                    it.copy(
                        isRunning = false,
                        currentParticipant = null,
                        error = e,
                    )
                }
                onStateChange(_autoDiscussState.value)
            }
        }
    }

    fun stopAutoDiscuss() {
        autoDiscussJob?.cancel()
        autoDiscussJob = null
        _autoDiscussState.update {
            AutoDiscussState()
        }
    }

    private fun getOrderedParticipants(
        participants: List<GroupChatParticipant>,
        speakingOrder: SpeakingOrder,
        round: Int,
    ): List<GroupChatParticipant> {
        return when (speakingOrder) {
            SpeakingOrder.Sequential -> participants

            SpeakingOrder.Random -> participants.shuffled()

            SpeakingOrder.Parallel -> participants
        }
    }

    private fun getContinuePrompt(settings: Settings): String {
        val promptTemplate = settings.quickMessages
            .find { it.id.toString() == AUTO_DISCUSS_CONTINUE_PROMPT_KEY }
            ?.content

        return if (promptTemplate.isNullOrBlank()) {
            DEFAULT_CONTINUE_PROMPT
        } else {
            promptTemplate.applyPlaceholders()
        }
    }

    fun canStartAutoDiscuss(conversation: Conversation): Boolean {
        if (!groupChatManager.isGroupChat(conversation)) {
            return false
        }
        val participants = groupChatManager.getEnabledParticipants(conversation)
        return participants.size >= 2
    }

    fun getParticipantLabel(
        participant: GroupChatParticipant,
        allParticipants: List<GroupChatParticipant>,
        settings: Settings,
    ): String {
        return groupChatManager.getParticipantLabel(
            participant = participant,
            allParticipants = allParticipants,
            getAssistant = { id -> settings.assistants.find { it.id == id } },
            getModelDisplayName = { id -> id?.let { settings.findModelById(it) }?.displayName ?: "Unknown" },
        )
    }
}
