package me.rerere.rikkahub.service

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.ai.TranslationService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationPageResult
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import java.time.Instant
import java.util.Locale
import kotlin.uuid.Uuid

sealed interface SendMessageResult {
    data class MessageAdded(val snapshot: ConversationRuntimeSnapshot) : SendMessageResult
    data class GenerationStarted(val handle: GenerationHandle) : SendMessageResult
}

interface ConversationCommands {
    suspend fun ensureConversation(conversationId: Uuid, assistantId: Uuid? = null): ConversationRuntimeSnapshot
    suspend fun send(
        conversationId: Uuid,
        assistantId: Uuid?,
        parts: List<UIMessagePart>,
        generateResponse: Boolean = true,
        modeInjectionIds: List<String>? = null,
        lorebookIds: List<String>? = null,
    ): SendMessageResult

    suspend fun regenerate(
        conversationId: Uuid,
        messageId: Uuid,
        regenerateAssistantMessage: Boolean = true,
    ): GenerationHandle

    suspend fun approveTool(
        conversationId: Uuid,
        generationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ): GenerationHandle

    suspend fun stop(conversationId: Uuid, generationId: Uuid): GenerationState
    suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>)
    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid)
    suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int)
    suspend fun forkConversation(conversationId: Uuid, messageId: Uuid): Conversation
    suspend fun deleteConversation(conversationId: Uuid)
    suspend fun deleteConversations(assistantId: Uuid)
    suspend fun restoreConversation(conversation: Conversation)
    suspend fun updateTitle(conversationId: Uuid, title: String)
    suspend fun updatePinned(conversationId: Uuid, pinned: Boolean)
    suspend fun moveToFolder(conversationId: Uuid, folderId: Uuid?)
    suspend fun moveToAssistant(conversationId: Uuid, assistantId: Uuid)
    suspend fun updateInjections(
        conversationId: Uuid,
        modeInjectionIds: List<String>?,
        lorebookIds: List<String>?,
    )
    suspend fun replaceConfiguration(conversationId: Uuid, conversation: Conversation)
    suspend fun updateFavorite(conversationId: Uuid, nodeId: Uuid, isFavorite: Boolean)
    suspend fun deleteFolder(folderId: Uuid)
    suspend fun translateMessage(conversationId: Uuid, messageId: Uuid, targetLanguage: Locale)
    suspend fun clearTranslation(conversationId: Uuid, messageId: Uuid)
    suspend fun generateTitle(conversationId: Uuid, force: Boolean = false)
    suspend fun generateSuggestions(conversationId: Uuid)
    suspend fun compress(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    )
}

interface ConversationQueries {
    suspend fun getConversation(conversationId: Uuid): ConversationRuntimeSnapshot
    fun observeConversation(conversationId: Uuid): Flow<ConversationRuntimeSnapshot>
    fun observeFailures(conversationId: Uuid): Flow<ChatFailure>
    fun observeActiveConversations(): Flow<Map<Uuid, ConversationRuntimeSnapshot>>
    fun listConversations(assistantId: Uuid): Flow<List<Conversation>>
    fun listPinnedConversations(): Flow<List<Conversation>>
    suspend fun pageConversations(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
        search: String? = null,
        folderId: Uuid? = null,
        unfiledOnly: Boolean = false,
    ): ConversationPageResult
    suspend fun searchMessages(query: String): List<MessageSearchResult>
}

class DefaultConversationApplication(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val filesManager: FilesManager,
    private val runtimeStore: ConversationRuntimeStore,
    private val generationCoordinator: ConversationGenerationCoordinator,
    private val translationService: TranslationService,
    private val postProcessor: ConversationPostProcessor,
) : ConversationCommands, ConversationQueries {
    override suspend fun ensureConversation(
        conversationId: Uuid,
        assistantId: Uuid?,
    ): ConversationRuntimeSnapshot = runtimeStore.ensure(conversationId) {
        val selectedAssistantId = assistantId ?: throw invalid(
            conversationId,
            "assistantId is required when creating a conversation",
        )
        val assistant = settingsStore.settingsFlow.value.getAssistantById(selectedAssistantId)
            ?: throw invalid(conversationId, "Assistant not found")
        Conversation.ofId(
            id = conversationId,
            assistantId = selectedAssistantId,
            newConversation = true,
        ).updateCurrentMessages(assistant.presetMessages)
    }

    override suspend fun send(
        conversationId: Uuid,
        assistantId: Uuid?,
        parts: List<UIMessagePart>,
        generateResponse: Boolean,
        modeInjectionIds: List<String>?,
        lorebookIds: List<String>?,
    ): SendMessageResult {
        if (parts.isEmptyInputMessage()) throw invalid(conversationId, "Message is empty")
        ensureConversation(conversationId, assistantId)
        val current = runtimeStore.require(conversationId).conversation
        val assistant = requireAssistant(current)
        val processedParts = preprocessUserInputParts(parts, assistant)
        val injections = resolveInjections(current, modeInjectionIds, lorebookIds)
        val append: (Conversation) -> Conversation = { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = processedParts,
                ).toMessageNode(),
                chatSuggestions = emptyList(),
                modeInjectionIds = injections.first,
                lorebookIds = injections.second,
                updateAt = Instant.now(),
            )
        }

        if (!generateResponse) {
            return SendMessageResult.MessageAdded(
                runtimeStore.mutate(
                    conversationId,
                    requireIdle = true,
                    persist = true,
                    transform = append,
                )
            )
        }

        val handle = runtimeStore.beginGeneration(conversationId, append)
        generationCoordinator.start(handle)
        return SendMessageResult.GenerationStarted(handle)
    }

    override suspend fun regenerate(
        conversationId: Uuid,
        messageId: Uuid,
        regenerateAssistantMessage: Boolean,
    ): GenerationHandle {
        runtimeStore.require(conversationId)
        var messageEndExclusive: Int? = null
        val handle = runtimeStore.beginGeneration(conversationId) { conversation ->
            val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == messageId }
            }
            if (nodeIndex < 0) throw notFound(conversationId, "Message not found")
            val message = conversation.messageNodes[nodeIndex].messages.first { it.id == messageId }
            when {
                message.role == MessageRole.USER -> conversation.copy(
                    messageNodes = conversation.messageNodes.take(nodeIndex + 1),
                    chatSuggestions = emptyList(),
                    updateAt = Instant.now(),
                )

                regenerateAssistantMessage -> {
                    messageEndExclusive = nodeIndex
                    conversation.copy(chatSuggestions = emptyList(), updateAt = Instant.now())
                }

                else -> throw invalid(conversationId, "Assistant regeneration is disabled")
            }
        }
        generationCoordinator.start(handle, messageEndExclusive)
        return handle
    }

    override suspend fun approveTool(
        conversationId: Uuid,
        generationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): GenerationHandle {
        val approval = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        var found = false
        val snapshot = runtimeStore.transitionGeneration(
            conversationId = conversationId,
            expectedGenerationId = generationId,
            persistConversation = true,
            allowed = { state ->
                state is GenerationState.AwaitingApproval && toolCallId in state.toolCallIds
            },
            transformConversation = { conversation ->
                conversation.copy(
                    messageNodes = conversation.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { message ->
                            message.copy(parts = message.parts.map { part ->
                                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId && part.isPending) {
                                    found = true
                                    part.copy(approvalState = approval)
                                } else {
                                    part
                                }
                            })
                        })
                    },
                    updateAt = Instant.now(),
                )
            },
            transformGeneration = { _, conversation ->
                if (!found) throw conflict(conversationId, generationId, "Stale tool approval")
                val pendingIds = conversation.currentMessages.flatMap(UIMessage::getTools)
                    .filter(UIMessagePart.Tool::isPending)
                    .map(UIMessagePart.Tool::toolCallId)
                if (pendingIds.isEmpty()) {
                    GenerationState.Queued(generationId)
                } else {
                    GenerationState.AwaitingApproval(generationId, pendingIds)
                }
            },
        )
        val state = snapshot.generation
        if (state is GenerationState.Queued) {
            val handle = GenerationHandle(conversationId, generationId, state)
            runtimeStore.awaitJobCompletion(conversationId, generationId)
            generationCoordinator.start(handle)
            return handle
        }
        return GenerationHandle(conversationId, generationId, state)
    }

    override suspend fun stop(conversationId: Uuid, generationId: Uuid): GenerationState {
        val snapshot = runtimeStore.require(conversationId)
        if (snapshot.generation.generationIdOrNull != generationId || !snapshot.generation.isBusy) {
            throw conflict(conversationId, generationId, "Generation is not active")
        }
        if (snapshot.generation is GenerationState.AwaitingApproval) {
            return runtimeStore.transitionGeneration(
                conversationId = conversationId,
                expectedGenerationId = generationId,
                persistConversation = true,
                allowed = { it is GenerationState.AwaitingApproval },
                transformConversation = ::cancelPendingTools,
                transformGeneration = { _, _ -> GenerationState.Cancelled(generationId) },
            ).generation
        }

        val job = runtimeStore.cancelJob(conversationId, generationId)
        runCatching { job?.join() }.onFailure { if (it is CancellationException) throw it }
        val latest = runtimeStore.require(conversationId).generation
        if (latest is GenerationState.Queued || latest is GenerationState.Running) {
            return runtimeStore.transitionGeneration(
                conversationId = conversationId,
                expectedGenerationId = generationId,
                persistConversation = true,
                allowed = { it is GenerationState.Queued || it is GenerationState.Running },
                transformConversation = ::cancelPendingTools,
                transformGeneration = { _, _ -> GenerationState.Cancelled(generationId) },
            ).generation
        }
        return latest
    }

    override suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmptyInputMessage()) throw invalid(conversationId, "Message is empty")
        val current = runtimeStore.require(conversationId).conversation
        val processed = preprocessUserInputParts(parts, requireAssistant(current))
        var edited = false
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    if (node.messages.none { it.id == messageId }) return@map node
                    edited = true
                    node.copy(
                        messages = node.messages + UIMessage(role = node.role, parts = processed),
                        selectIndex = node.messages.size,
                    )
                },
                updateAt = Instant.now(),
            ).also { if (!edited) throw notFound(conversationId, "Message not found") }
        }
    }

    override suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid) {
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) { conversation ->
            val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == messageId }
            }
            if (nodeIndex < 0) throw notFound(conversationId, "Message not found")
            conversation.copy(
                messageNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
                    if (index != nodeIndex) return@mapIndexedNotNull node
                    val messages = node.messages.filterNot { it.id == messageId }
                    messages.takeIf(List<UIMessage>::isNotEmpty)?.let {
                        node.copy(messages = it, selectIndex = node.selectIndex.coerceAtMost(it.lastIndex))
                    }
                },
                updateAt = Instant.now(),
            )
        }
    }

    override suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) {
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) { conversation ->
            val node = conversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw notFound(conversationId, "Message node not found")
            if (selectIndex !in node.messages.indices) throw invalid(conversationId, "Invalid selectIndex")
            conversation.copy(
                messageNodes = conversation.messageNodes.map {
                    if (it.id == nodeId) it.copy(selectIndex = selectIndex) else it
                },
                updateAt = Instant.now(),
            )
        }
    }

    override suspend fun forkConversation(conversationId: Uuid, messageId: Uuid): Conversation {
        val source = runtimeStore.mutate(conversationId, requireIdle = true) { it }
        val nodeIndex = source.conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (nodeIndex < 0) throw notFound(conversationId, "Message not found")
        val nodes = source.conversation.messageNodes.take(nodeIndex + 1).map { node ->
            node.copy(
                id = Uuid.random(),
                messages = node.messages.map { message ->
                    message.copy(parts = message.parts.map(::copyPartForFork))
                },
            )
        }
        val fork = createForkConversation(source.conversation, nodes)
        runtimeStore.ensure(fork.id) { fork }
        runtimeStore.mutate(fork.id, persist = true) { fork }
        return fork
    }

    override suspend fun deleteConversation(conversationId: Uuid) = runtimeStore.delete(conversationId)

    override suspend fun deleteConversations(assistantId: Uuid) {
        val conversationIds = conversationRepository.getConversationsOfAssistant(assistantId)
            .first()
            .map(Conversation::id)
        val snapshots = conversationIds.map { runtimeStore.require(it) }
        snapshots.firstOrNull { it.generation.isBusy }?.let { snapshot ->
            throw conflict(snapshot.conversation.id, snapshot.generation.generationIdOrNull)
        }
        conversationIds.forEach { runtimeStore.delete(it) }
    }

    override suspend fun restoreConversation(conversation: Conversation) {
        runtimeStore.ensure(conversation.id) { conversation }
        runtimeStore.mutate(conversation.id, requireIdle = true, persist = true) { conversation }
    }

    override suspend fun updateTitle(conversationId: Uuid, title: String) {
        runtimeStore.mutate(conversationId, persist = true) { it.copy(title = title) }
    }

    override suspend fun updatePinned(conversationId: Uuid, pinned: Boolean) {
        runtimeStore.mutate(conversationId, persist = true) { it.copy(isPinned = pinned) }
    }

    override suspend fun moveToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (folderId != null) {
            val conversation = runtimeStore.require(conversationId).conversation
            val folder = folderRepository.getFolderById(folderId)
                ?: throw notFound(conversationId, "Folder not found")
            if (folder.assistantId != conversation.assistantId) {
                throw invalid(conversationId, "Folder belongs to another assistant")
            }
        }
        runtimeStore.mutate(conversationId, persist = true) { it.copy(folderId = folderId) }
    }

    override suspend fun moveToAssistant(conversationId: Uuid, assistantId: Uuid) {
        if (settingsStore.settingsFlow.value.getAssistantById(assistantId) == null) {
            throw invalid(conversationId, "Assistant not found")
        }
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) {
            it.copy(assistantId = assistantId, folderId = null)
        }
    }

    override suspend fun updateInjections(
        conversationId: Uuid,
        modeInjectionIds: List<String>?,
        lorebookIds: List<String>?,
    ) {
        val conversation = runtimeStore.require(conversationId).conversation
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: throw notFound(conversationId, "Assistant not found")
        if (!assistant.allowConversationPromptInjection &&
            (modeInjectionIds.orEmpty().isNotEmpty() || lorebookIds.orEmpty().isNotEmpty())
        ) {
            throw invalid(conversationId, "Conversation prompt injection is not enabled for this assistant")
        }
        val validModes = settings.modeInjections.map { it.id }.toSet()
        val modes = modeInjectionIds?.map { parseUuid(it, conversationId, "modeInjectionIds") }?.toSet()
            ?: conversation.modeInjectionIds
        if (!validModes.containsAll(modes)) throw invalid(conversationId, "Unknown mode injection id")
        val validLorebooks = settings.lorebooks.map { it.id }.toSet()
        val lorebooks = lorebookIds?.map { parseUuid(it, conversationId, "lorebookIds") }?.toSet()
            ?: conversation.lorebookIds
        if (!validLorebooks.containsAll(lorebooks)) throw invalid(conversationId, "Unknown lorebook id")
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) {
            it.copy(modeInjectionIds = modes, lorebookIds = lorebooks)
        }
    }

    override suspend fun replaceConfiguration(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) throw invalid(conversationId, "Conversation id mismatch")
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) { current ->
            current.copy(
                customSystemPrompt = conversation.customSystemPrompt,
                modeInjectionIds = conversation.modeInjectionIds,
                lorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
            )
        }
    }

    override suspend fun updateFavorite(conversationId: Uuid, nodeId: Uuid, isFavorite: Boolean) {
        runtimeStore.mutate(conversationId) { current ->
            if (current.messageNodes.none { it.id == nodeId }) {
                throw notFound(conversationId, "Message node not found")
            }
            current.copy(messageNodes = current.messageNodes.map { node ->
                if (node.id == nodeId) node.copy(isFavorite = isFavorite) else node
            })
        }
    }

    override suspend fun deleteFolder(folderId: Uuid) {
        val affected = runtimeStore.currentSnapshots().values.filter { it.conversation.folderId == folderId }
        affected.firstOrNull { it.generation.isBusy }?.let {
            throw conflict(it.conversation.id, it.generation.generationIdOrNull)
        }
        affected.forEach { snapshot ->
            runtimeStore.mutate(snapshot.conversation.id, persist = true) { it.copy(folderId = null) }
        }
        folderRepository.deleteFolder(folderId)
    }

    override suspend fun translateMessage(
        conversationId: Uuid,
        messageId: Uuid,
        targetLanguage: Locale,
    ) {
        val message = runtimeStore.require(conversationId).conversation
            .getMessageNodeByMessageId(messageId)?.messages?.firstOrNull { it.id == messageId }
            ?: throw notFound(conversationId, "Message not found")
        val text = message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n\n", transform = UIMessagePart.Text::text)
            .trim()
        if (text.isBlank()) throw invalid(conversationId, "Message has no text")
        try {
            translationService.translate(settingsStore.settingsFlow.value, text, targetLanguage)
                .collect { translated -> updateTranslation(conversationId, messageId, translated, false) }
            runtimeStore.persistCurrent(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            clearTranslation(conversationId, messageId)
            throw error
        }
    }

    override suspend fun clearTranslation(conversationId: Uuid, messageId: Uuid) {
        updateTranslation(conversationId, messageId, null, true)
    }

    override suspend fun generateTitle(conversationId: Uuid, force: Boolean) =
        postProcessor.generateTitle(conversationId, force)

    override suspend fun generateSuggestions(conversationId: Uuid) =
        postProcessor.generateSuggestions(conversationId)

    override suspend fun compress(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ) = postProcessor.compress(conversationId, additionalPrompt, targetTokens, keepRecentMessages)

    override suspend fun getConversation(conversationId: Uuid): ConversationRuntimeSnapshot =
        runtimeStore.require(conversationId)

    override fun observeConversation(conversationId: Uuid): Flow<ConversationRuntimeSnapshot> =
        runtimeStore.observe(conversationId)

    override fun observeFailures(conversationId: Uuid): Flow<ChatFailure> =
        runtimeStore.observeFailures(conversationId)

    override fun observeActiveConversations(): Flow<Map<Uuid, ConversationRuntimeSnapshot>> =
        runtimeStore.observeAll()

    override fun listConversations(assistantId: Uuid): Flow<List<Conversation>> =
        conversationRepository.getConversationsOfAssistant(assistantId)

    override fun listPinnedConversations(): Flow<List<Conversation>> =
        conversationRepository.getPinnedConversations()

    override suspend fun pageConversations(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
        search: String?,
        folderId: Uuid?,
        unfiledOnly: Boolean,
    ): ConversationPageResult = when {
        !search.isNullOrBlank() ->
            conversationRepository.searchConversationsOfAssistantPage(assistantId, search, offset, limit)
        folderId != null -> conversationRepository.getConversationsOfFolderPage(folderId, offset, limit)
        unfiledOnly -> conversationRepository.getUnfiledConversationsOfAssistantPage(assistantId, offset, limit)
        else -> conversationRepository.getConversationsOfAssistantPage(assistantId, offset, limit)
    }

    override suspend fun searchMessages(query: String): List<MessageSearchResult> =
        conversationRepository.searchMessages(query)

    private fun requireAssistant(conversation: Conversation): Assistant =
        settingsStore.settingsFlow.value.getAssistantById(conversation.assistantId)
            ?: throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.Configuration,
                    message = "Assistant not found",
                    conversationId = conversation.id,
                )
            )

    private fun resolveInjections(
        conversation: Conversation,
        modeInjectionIds: List<String>?,
        lorebookIds: List<String>?,
    ): Pair<Set<Uuid>, Set<Uuid>> {
        if (modeInjectionIds == null && lorebookIds == null) {
            return conversation.modeInjectionIds to conversation.lorebookIds
        }
        val settings = settingsStore.settingsFlow.value
        val assistant = requireAssistant(conversation)
        if (!assistant.allowConversationPromptInjection &&
            (modeInjectionIds.orEmpty().isNotEmpty() || lorebookIds.orEmpty().isNotEmpty())
        ) {
            throw invalid(conversation.id, "Conversation prompt injection is not enabled for this assistant")
        }
        val modes = modeInjectionIds?.map { parseUuid(it, conversation.id, "modeInjectionIds") }?.toSet()
            ?: conversation.modeInjectionIds
        val lorebooks = lorebookIds?.map { parseUuid(it, conversation.id, "lorebookIds") }?.toSet()
            ?: conversation.lorebookIds
        if (!settings.modeInjections.map { it.id }.toSet().containsAll(modes)) {
            throw invalid(conversation.id, "Unknown mode injection id")
        }
        if (!settings.lorebooks.map { it.id }.toSet().containsAll(lorebooks)) {
            throw invalid(conversation.id, "Unknown lorebook id")
        }
        return modes to lorebooks
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant) = parts.map { part ->
        if (part is UIMessagePart.Text) {
            part.copy(
                text = part.text.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.USER,
                    visual = false,
                )
            )
        } else {
            part
        }
    }

    private suspend fun updateTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        translation: String?,
        persist: Boolean,
    ) {
        var found = false
        runtimeStore.mutate(conversationId, persist = persist) { conversation ->
            conversation.copy(messageNodes = conversation.messageNodes.map { node ->
                node.copy(messages = node.messages.map { message ->
                    if (message.id == messageId) {
                        found = true
                        message.copy(translation = translation)
                    } else message
                })
            }).also { if (!found) throw notFound(conversationId, "Message not found") }
        }
    }

    private fun cancelPendingTools(conversation: Conversation): Conversation = conversation.copy(
        messageNodes = conversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                message.finishPendingTools { tool ->
                    tool.copy(
                        output = listOf(UIMessagePart.Text("""{"status":"cancelled","error":"Generation cancelled by user."}""")),
                        approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
                    )
                }.finishReasoning()
            })
        },
        updateAt = Instant.now(),
    )

    private fun copyPartForFork(part: UIMessagePart): UIMessagePart {
        fun copyLocal(url: String): String {
            if (!url.startsWith("file:")) return url
            return filesManager.createChatFilesByContents(listOf(url.toUri()))
                .firstOrNull()?.toString() ?: url
        }
        return when (part) {
            is UIMessagePart.Image -> part.copy(url = copyLocal(part.url))
            is UIMessagePart.Document -> part.copy(url = copyLocal(part.url))
            is UIMessagePart.Video -> part.copy(url = copyLocal(part.url))
            is UIMessagePart.Audio -> part.copy(url = copyLocal(part.url))
            else -> part
        }
    }

    private fun invalid(conversationId: Uuid?, message: String) = ChatCommandException(
        ChatFailure(
            code = ChatFailureCode.InvalidRequest,
            message = message,
            conversationId = conversationId,
        )
    )

    private fun notFound(conversationId: Uuid?, message: String) = ChatCommandException(
        ChatFailure(
            code = ChatFailureCode.NotFound,
            message = message,
            conversationId = conversationId,
        )
    )

    private fun conflict(conversationId: Uuid, generationId: Uuid?, message: String = "Conversation is busy") =
        ChatCommandException(
            ChatFailure(
                code = ChatFailureCode.Conflict,
                message = message,
                conversationId = conversationId,
                generationId = generationId,
            )
        )

    private fun parseUuid(value: String, conversationId: Uuid, field: String): Uuid =
        runCatching { Uuid.parse(value) }.getOrElse {
            throw invalid(conversationId, "Invalid $field")
        }
}

internal fun createForkConversation(source: Conversation, nodes: List<MessageNode>) = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = nodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)
