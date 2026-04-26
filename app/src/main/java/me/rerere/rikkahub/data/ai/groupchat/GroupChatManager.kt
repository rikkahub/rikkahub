package me.rerere.rikkahub.data.ai.groupchat

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatParticipant
import me.rerere.rikkahub.data.model.MessageParticipantInfo
import me.rerere.rikkahub.data.model.SpeakingOrder
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.mcp.McpManager
import kotlin.uuid.Uuid

private const val TAG = "GroupChatManager"

data class GroupChatGenerationResult(
    val participantId: Uuid,
    val messages: List<UIMessage>,
    val isSuccess: Boolean,
    val error: Throwable? = null
)

class GroupChatManager(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val mcpManager: McpManager,
    private val localTools: LocalTools,
    private val skillManager: SkillManager,
) {
    fun isGroupChat(conversation: Conversation): Boolean {
        return conversation.groupChatConfig.isGroupChat &&
                conversation.groupChatConfig.enabledParticipants.size >= 2
    }

    fun getEnabledParticipants(conversation: Conversation): List<GroupChatParticipant> {
        return conversation.groupChatConfig.enabledParticipants
    }

    fun getParticipantLabel(
        participant: GroupChatParticipant,
        allParticipants: List<GroupChatParticipant>,
        getAssistant: (Uuid) -> Assistant?,
        getModelDisplayName: (Uuid?) -> String,
    ): String {
        return me.rerere.rikkahub.data.ai.groupchat.getParticipantLabel(
            participant = participant,
            allParticipants = allParticipants,
            getAssistant = getAssistant,
            getModelDisplayName = getModelDisplayName,
        )
    }

    fun buildGroupSystemPrompt(
        conversation: Conversation,
        participant: GroupChatParticipant,
        baseSystemPrompt: String,
        getAssistant: (Uuid) -> Assistant?,
        getModelDisplayName: (Uuid?) -> String,
    ): String {
        return buildSystemPromptForParticipant(
            conversation = conversation,
            participant = participant,
            baseSystemPrompt = baseSystemPrompt,
            getAssistant = getAssistant,
            getModelDisplayName = getModelDisplayName,
        )
    }

    fun resolveTargetParticipants(
        conversation: Conversation,
        mentionedParticipantIds: List<Uuid>? = null,
    ): List<GroupChatParticipant> {
        return me.rerere.rikkahub.data.ai.groupchat.resolveTargetParticipants(
            conversation = conversation,
            mentionedParticipantIds = mentionedParticipantIds,
        )
    }

    fun transformMessagesForParticipant(
        messages: List<UIMessage>,
        conversation: Conversation,
        selfParticipantId: Uuid,
        getParticipantInfo: (Uuid) -> MessageParticipantInfo?,
    ): List<UIMessage> {
        return messages.map { message ->
            transformMessageForGroupChat(
                message = message,
                conversation = conversation,
                selfParticipantId = selfParticipantId,
                getParticipantInfo = getParticipantInfo,
            )
        }
    }

    fun createParticipantInfo(
        participant: GroupChatParticipant,
        getAssistant: (Uuid) -> Assistant?,
        getModelDisplayName: (Uuid?) -> String,
        allParticipants: List<GroupChatParticipant>,
    ): MessageParticipantInfo {
        val displayName = getParticipantLabel(
            participant = participant,
            allParticipants = allParticipants,
            getAssistant = getAssistant,
            getModelDisplayName = getModelDisplayName,
        )
        return MessageParticipantInfo(
            participantId = participant.id,
            assistantId = participant.assistantId,
            modelId = participant.modelId,
            displayName = displayName,
        )
    }

    fun addParticipantMetadata(
        message: UIMessage,
        participantInfo: MessageParticipantInfo,
    ): UIMessage {
        val metadata = buildJsonObject {
            put("participantId", participantInfo.participantId.toString())
            put("assistantId", participantInfo.assistantId.toString())
            put("displayName", participantInfo.displayName)
            participantInfo.modelId?.let { put("modelId", it.toString()) }
        }
        val updatedParts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(metadata = metadata)
                else -> part
            }
        }
        return message.copy(parts = updatedParts)
    }

    fun extractParticipantInfoFromMessage(message: UIMessage): MessageParticipantInfo? {
        val textPart = message.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()
        val metadata = textPart?.metadata ?: return null
        val participantIdStr = metadata["participantId"]?.jsonPrimitive?.content ?: return null
        val assistantIdStr = metadata["assistantId"]?.jsonPrimitive?.content ?: return null
        val displayName = metadata["displayName"]?.jsonPrimitive?.content ?: return null
        val modelIdStr = metadata["modelId"]?.jsonPrimitive?.content

        return MessageParticipantInfo(
            participantId = Uuid.parse(participantIdStr),
            assistantId = Uuid.parse(assistantIdStr),
            modelId = modelIdStr?.let { Uuid.parse(it) },
            displayName = displayName,
        )
    }

    fun generateForParticipant(
        conversation: Conversation,
        participant: GroupChatParticipant,
        settings: Settings,
        getAssistant: (Uuid) -> Assistant?,
        getModelById: (Uuid) -> Model?,
        inputTransformers: List<InputMessageTransformer>,
        outputTransformers: List<OutputMessageTransformer>,
        generationHandler: GenerationHandler,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): Flow<GenerationChunk> = flow {
        val assistant = getAssistant(participant.assistantId)
        val modelId = participant.modelId ?: assistant?.chatModelId
        val model = modelId?.let { getModelById(it) } ?: settings.getCurrentChatModel()

        if (model == null || assistant == null) {
            emit(GenerationChunk.Messages(emptyList()))
            return@flow
        }

        val allParticipants = conversation.groupChatConfig.enabledParticipants
        val participantInfo = createParticipantInfo(
            participant = participant,
            getAssistant = getAssistant,
            getModelDisplayName = { id -> id?.let { getModelById(it) }?.displayName ?: "Unknown" },
            allParticipants = allParticipants,
        )

        val messagesForGeneration = transformMessagesForParticipant(
            messages = conversation.currentMessages,
            conversation = conversation,
            selfParticipantId = participant.id,
            getParticipantInfo = { extractParticipantInfoFromMessage(conversation, it) },
        )

        val tools = buildToolsForParticipant(
            settings = settings,
            assistant = assistant,
        )

        val memories = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }

        val groupSystemPrompt = buildGroupSystemPrompt(
            conversation = conversation,
            participant = participant,
            baseSystemPrompt = assistant.systemPrompt,
            getAssistant = getAssistant,
            getModelDisplayName = { id -> id?.let { getModelById(it) }?.displayName ?: "Unknown" },
        )

        val assistantWithGroupPrompt = assistant.copy(systemPrompt = groupSystemPrompt)

        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messagesForGeneration,
            inputTransformers = inputTransformers,
            outputTransformers = outputTransformers,
            assistant = assistantWithGroupPrompt,
            memories = memories,
            tools = tools,
            processingStatus = processingStatus,
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    val messagesWithMetadata = chunk.messages.map { message ->
                        if (message.role == MessageRole.ASSISTANT) {
                            addParticipantMetadata(message, participantInfo)
                        } else {
                            message
                        }
                    }
                    emit(GenerationChunk.Messages(messagesWithMetadata))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun extractParticipantInfoFromMessage(
        conversation: Conversation,
        messageId: Uuid,
    ): MessageParticipantInfo? {
        val message = conversation.messageNodes
            .flatMap { it.messages }
            .find { it.id == messageId }
        return message?.let { extractParticipantInfoFromMessage(it) }
    }

    private fun buildToolsForParticipant(
        settings: Settings,
        assistant: Assistant,
    ): List<Tool> {
        return buildList {
            if (settings.enableWebSearch) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                        skillManager = skillManager,
                    )
                )
            }
            mcpManager.getAllAvailableTools().forEach { tool ->
                add(
                    Tool(
                        name = "mcp__" + tool.name,
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = {
                            mcpManager.callTool(tool.name, it.jsonObject)
                        },
                    )
                )
            }
        }
    }

    fun generateGroupChatTitle(
        participants: List<GroupChatParticipant>,
        getAssistant: (Uuid) -> Assistant?,
        getModelDisplayName: (Uuid?) -> String,
    ): String {
        if (participants.isEmpty()) return "New Group Chat"
        if (participants.size == 1) {
            return getParticipantLabel(
                participant = participants.first(),
                allParticipants = participants,
                getAssistant = getAssistant,
                getModelDisplayName = getModelDisplayName,
            )
        }
        val names = participants.map { p ->
            getParticipantLabel(p, participants, getAssistant, getModelDisplayName)
        }
        return if (names.size <= 3) {
            names.joinToString(", ")
        } else {
            "${names.take(3).joinToString(", ")}..."
        }
    }

    fun createGroupChatConfig(
        participants: List<GroupChatParticipant>,
        speakingOrder: SpeakingOrder = SpeakingOrder.Sequential,
        groupSystemPrompt: String? = null,
    ): GroupChatConfig {
        val sortedParticipants = participants.sortedBy { it.order }
        return GroupChatConfig(
            isGroupChat = sortedParticipants.size >= 2,
            participants = sortedParticipants,
            speakingOrder = speakingOrder,
            groupSystemPrompt = groupSystemPrompt,
            autoDiscussEnabled = false,
            autoDiscussRounds = 0,
            autoDiscussRemaining = 0,
        )
    }

    fun addParticipantToConfig(
        config: GroupChatConfig,
        participant: GroupChatParticipant,
    ): GroupChatConfig {
        val newOrder = if (config.participants.isEmpty()) 0 else config.participants.maxOf { it.order } + 1
        val participantWithOrder = participant.copy(order = newOrder)
        val newParticipants = config.participants + participantWithOrder
        return config.copy(
            isGroupChat = newParticipants.size >= 2,
            participants = newParticipants,
        )
    }

    fun removeParticipantFromConfig(
        config: GroupChatConfig,
        participantId: Uuid,
    ): GroupChatConfig {
        val newParticipants = config.participants.filter { it.id != participantId }
        return config.copy(
            isGroupChat = newParticipants.size >= 2,
            participants = newParticipants,
        )
    }

    fun reorderParticipants(
        config: GroupChatConfig,
        participantIds: List<Uuid>,
    ): GroupChatConfig {
        val participantMap = config.participants.associateBy { it.id }
        val newParticipants = participantIds.mapIndexed { index, id ->
            participantMap[id]?.copy(order = index)
        }.filterNotNull()
        return config.copy(participants = newParticipants)
    }

    fun toggleParticipantEnabled(
        config: GroupChatConfig,
        participantId: Uuid,
    ): GroupChatConfig {
        val newParticipants = config.participants.map { p ->
            if (p.id == participantId) p.copy(enabled = !p.enabled) else p
        }
        return config.copy(participants = newParticipants)
    }
}
