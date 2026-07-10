package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.ConversationDto
import me.rerere.rikkahub.web.dto.ConversationNodeUpdateEvent
import me.rerere.rikkahub.web.dto.ConversationSnapshotEvent
import me.rerere.rikkahub.web.dto.EditMessageRequest
import me.rerere.rikkahub.web.dto.ErrorEvent
import me.rerere.rikkahub.web.dto.ForkConversationRequest
import me.rerere.rikkahub.web.dto.ForkConversationResponse
import me.rerere.rikkahub.web.dto.MoveConversationRequest
import me.rerere.rikkahub.web.dto.MoveConversationToFolderRequest
import me.rerere.rikkahub.web.dto.PagedResult
import me.rerere.rikkahub.web.dto.RegenerateRequest
import me.rerere.rikkahub.web.dto.SelectMessageNodeRequest
import me.rerere.rikkahub.web.dto.SendMessageRequest
import me.rerere.rikkahub.web.dto.ToolApprovalRequest
import me.rerere.rikkahub.web.dto.MessageSearchResultDto
import me.rerere.rikkahub.web.dto.UpdateConversationInjectionsRequest
import me.rerere.rikkahub.web.dto.UpdateConversationTitleRequest
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.dto.toListDto
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal fun Conversation.movedToAssistant(targetAssistantId: Uuid): Conversation = copy(
    assistantId = targetAssistantId,
    folderId = null,
)

fun Route.conversationRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore
) {
    route("/conversations") {
        // GET /api/conversations - List conversations of current assistant
        get {
            val settings = settingsStore.settingsFlow.first()
            val generationJobs = chatService.getConversationJobs().first()
            val conversations = conversationRepo
                .getConversationsOfAssistant(settings.assistantId)
                .first()
                .map { conversation ->
                    conversation.toListDto(isGenerating = generationJobs[conversation.id] != null)
                }
            call.respond(conversations)
        }

        // GET /api/conversations/paged?offset=0&limit=20&query=foo&folderId=none|<uuid>
        // folderId: absent = all conversations, "none" = unfiled only, <uuid> = that folder
        get("/paged") {
            val settings = settingsStore.settingsFlow.first()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            val folderParam = call.request.queryParameters["folderId"]?.trim()

            if (offset < 0) {
                throw BadRequestException("offset must be >= 0")
            }
            if (limit !in 1..100) {
                throw BadRequestException("limit must be in 1..100")
            }

            val page = when {
                // Search ignores folder filter (searches across all conversations of the assistant)
                query.isNotBlank() -> conversationRepo.searchConversationsOfAssistantPage(
                    assistantId = settings.assistantId,
                    titleKeyword = query,
                    offset = offset,
                    limit = limit
                )

                folderParam == null -> conversationRepo.getConversationsOfAssistantPage(
                    assistantId = settings.assistantId,
                    offset = offset,
                    limit = limit
                )

                folderParam.isEmpty() || folderParam == "none" ->
                    conversationRepo.getUnfiledConversationsOfAssistantPage(
                        assistantId = settings.assistantId,
                        offset = offset,
                        limit = limit
                    )

                else -> {
                    val folderId = folderParam.toUuid("folderId")
                    folderRepo.getFolderById(folderId).requireCurrentAssistant(settings.assistantId)
                    conversationRepo.getConversationsOfFolderPage(
                        folderId = folderId,
                        offset = offset,
                        limit = limit
                    )
                }
            }
            val generationJobs = chatService.getConversationJobs().first()

            call.respond(
                PagedResult(
                    items = page.items.map { conversation ->
                        conversation.toListDto(isGenerating = generationJobs[conversation.id] != null)
                    },
                    nextOffset = page.nextOffset
                )
            )
        }

        // GET /api/conversations/search?query=foo - Full-text search messages
        get("/search") {
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            if (query.isBlank()) {
                call.respond(emptyList<MessageSearchResultDto>())
                return@get
            }
            val results = conversationRepo.searchMessages(query)
            call.respond(results.map { result ->
                MessageSearchResultDto(
                    nodeId = result.nodeId,
                    messageId = result.messageId,
                    conversationId = result.conversationId,
                    title = result.title,
                    updateAt = result.updateAt.toEpochMilli(),
                    snippet = result.snippet,
                )
            })
        }

        // GET /api/conversations/{id} - Get single conversation
        get("/{id}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
            call.respond(conversation.toDto(isGenerating))
        }

        // DELETE /api/conversations/{id} - Delete conversation
        delete("/{id}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            conversationRepo.deleteConversation(conversation)
            call.respond(HttpStatusCode.NoContent)
        }

        // POST /api/conversations/{id}/pin - Toggle pinned status
        post("/{id}/pin") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            chatService.saveConversation(uuid, conversation.copy(isPinned = !conversation.isPinned))
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/regenerate-title - Regenerate conversation title
        post("/{id}/regenerate-title") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            chatService.generateTitle(uuid, conversation, force = true)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/title - Update conversation title
        post("/{id}/title") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<UpdateConversationTitleRequest>()
            val title = request.title.trim()

            if (title.isEmpty()) {
                throw BadRequestException("Title must not be blank")
            }

            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            chatService.saveConversation(uuid, conversation.copy(title = title))
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/injections - Update conversation-scoped prompt injections
        post("/{id}/injections") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<UpdateConversationInjectionsRequest>()
            conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            chatService.initializeConversation(uuid)
            val conversation = chatService.getConversationFlow(uuid).first()
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
                ?: throw NotFoundException("Assistant not found")
            if (!assistant.allowConversationPromptInjection) {
                throw BadRequestException("Conversation prompt injection is not enabled for this assistant")
            }

            val (modeInjectionIds, lorebookIds) = validateConversationInjectionIds(
                settings = settings,
                modeInjectionIds = request.modeInjectionIds,
                lorebookIds = request.lorebookIds,
            )
            val updatedConversation = conversation.copy(
                modeInjectionIds = modeInjectionIds,
                lorebookIds = lorebookIds,
            )
            chatService.saveConversation(uuid, updatedConversation)

            val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
            call.respond(HttpStatusCode.OK, updatedConversation.toDto(isGenerating))
        }

        // POST /api/conversations/{id}/move - Move conversation to another assistant
        post("/{id}/move") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<MoveConversationRequest>()
            val targetAssistantId = request.assistantId.toUuid("assistant id")

            val settings = settingsStore.settingsFlow.first()
            if (settings.assistants.none { it.id == targetAssistantId }) {
                throw BadRequestException("Assistant not found")
            }

            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            chatService.saveConversation(uuid, conversation.movedToAssistant(targetAssistantId))
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/folder - Move conversation to a folder (null = unfiled)
        post("/{id}/folder") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<MoveConversationToFolderRequest>()

            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            val targetFolderId = request.folderId?.takeIf { it.isNotBlank() }?.toUuid("folder id")
            if (targetFolderId != null) {
                val folder = folderRepo.getFolderById(targetFolderId)
                    ?: throw NotFoundException("Folder not found")
                if (folder.assistantId != conversation.assistantId) {
                    throw BadRequestException("Folder belongs to another assistant")
                }
            }

            chatService.moveConversationToFolder(uuid, targetFolderId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/messages - Send a message
        post("/{id}/messages") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<SendMessageRequest>()

            chatService.initializeConversation(uuid)
            applyInitialConversationInjections(
                chatService = chatService,
                settingsStore = settingsStore,
                conversationId = uuid,
                modeInjectionIds = request.modeInjectionIds,
                lorebookIds = request.lorebookIds,
            )
            chatService.sendMessage(uuid, request.parts, answer = true)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/messages/{messageId}/edit - Edit a message as a new branch version
        post("/{id}/messages/{messageId}/edit") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            val request = call.receive<EditMessageRequest>()

            chatService.initializeConversation(uuid)
            chatService.editMessage(uuid, messageId, request.parts)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/fork - Create a forked conversation up to message
        post("/{id}/fork") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<ForkConversationRequest>()
            val messageId = request.messageId.toUuid("message id")

            chatService.initializeConversation(uuid)
            val fork = chatService.forkConversationAtMessage(uuid, messageId)

            call.respond(HttpStatusCode.Created, ForkConversationResponse(conversationId = fork.id.toString()))
        }

        // DELETE /api/conversations/{id}/messages/{messageId} - Delete a message
        delete("/{id}/messages/{messageId}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")

            chatService.initializeConversation(uuid)
            chatService.deleteMessage(uuid, messageId)

            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // POST /api/conversations/{id}/nodes/{nodeId}/select - Switch branch selection for a message node
        post("/{id}/nodes/{nodeId}/select") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val nodeId = call.parameters["nodeId"].toUuid("node id")
            val request = call.receive<SelectMessageNodeRequest>()

            chatService.initializeConversation(uuid)
            chatService.selectMessageNode(uuid, nodeId, request.selectIndex)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/regenerate - Regenerate message
        post("/{id}/regenerate") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<RegenerateRequest>()
            val messageId = request.messageId.toUuid("message id")

            val conversation = chatService.getConversationFlow(uuid).first()
            val node = conversation.getMessageNodeByMessageId(messageId)
            val message = node?.messages?.find { it.id == messageId }
                ?: throw NotFoundException("Message not found")

            chatService.regenerateAtMessage(uuid, message)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/stop - Stop generation
        post("/{id}/stop") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            chatService.stopGeneration(uuid)
            call.respond(HttpStatusCode.OK, mapOf("status" to "stopped"))
        }

        // POST /api/conversations/{id}/tool-approval - Handle tool approval
        post("/{id}/tool-approval") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<ToolApprovalRequest>()
            chatService.handleToolApproval(uuid, request.toolCallId, request.approved, request.reason, request.answer)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // SSE /api/conversations/{id}/stream - Stream conversation updates
        sse("/{id}/stream") {
            val id = call.parameters["id"] ?: return@sse
            val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@sse

            chatService.initializeConversation(uuid)
            chatService.addConversationReference(uuid)

            heartbeat {
                period = 1.seconds
            }

            try {
                var sequence = 0L
                var previousDto: ConversationDto? = null

                val knownErrorIds = chatService.errors.value.map { it.id }.toMutableSet()

                val conversationEvents = combine(
                    chatService.getConversationFlow(uuid),
                    chatService
                        .getGenerationJobStateFlow(uuid)
                        .map { it != null }
                        .distinctUntilChanged()
                ) { conversation, isGenerating ->
                    ConversationStreamPayload.Conversation(conversation.toDto(isGenerating))
                }

                val errorEvents = chatService.errors.map { errors ->
                    errors
                        .asSequence()
                        .filter { it.conversationId == uuid && knownErrorIds.add(it.id) }
                        .map { chatError ->
                            chatError.error.message?.takeIf { it.isNotBlank() }
                                ?: chatError.error.toString()
                        }
                        .toList()
                }.map { events ->
                    ConversationStreamPayload.BatchErrors(events)
                }

                merge(conversationEvents, errorEvents).collect { payload ->
                    when (payload) {
                        is ConversationStreamPayload.Conversation -> {
                            sequence += 1
                            val currentDto = payload.value
                            val nodeDiff = previousDto?.singleNodeDiffOrNull(currentDto)
                            if (nodeDiff != null) {
                                val json = JsonInstant.encodeToString(
                                    ConversationNodeUpdateEvent(
                                        seq = sequence,
                                        conversationId = currentDto.id,
                                        nodeId = nodeDiff.node.id,
                                        nodeIndex = nodeDiff.nodeIndex,
                                        node = nodeDiff.node,
                                        updateAt = currentDto.updateAt,
                                        isGenerating = currentDto.isGenerating
                                    )
                                )
                                send(data = json, event = "node_update")
                            } else {
                                val json = JsonInstant.encodeToString(
                                    ConversationSnapshotEvent(
                                        seq = sequence,
                                        conversation = currentDto
                                    )
                                )
                                send(data = json, event = "snapshot")
                            }
                            previousDto = currentDto
                        }

                        is ConversationStreamPayload.BatchErrors -> {
                            payload.messages.forEach { message ->
                                val json = JsonInstant.encodeToString(
                                    ErrorEvent(message = message)
                                )
                                send(data = json, event = "error")
                            }
                        }
                    }
                }
            } finally {
                chatService.removeConversationReference(uuid)
            }
        }
    }
}

private sealed interface ConversationStreamPayload {
    data class Conversation(val value: ConversationDto) : ConversationStreamPayload
    data class BatchErrors(val messages: List<String>) : ConversationStreamPayload
}

private data class ConversationInjectionIds(
    val modeInjectionIds: Set<Uuid>,
    val lorebookIds: Set<Uuid>,
)

private fun validateConversationInjectionIds(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    modeInjectionIds: List<String>,
    lorebookIds: List<String>,
): ConversationInjectionIds {
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val requestedModeInjectionIds = modeInjectionIds.map { it.toUuid("modeInjectionIds") }.toSet()
    if (!validModeInjectionIds.containsAll(requestedModeInjectionIds)) {
        throw BadRequestException("modeInjectionIds contains unknown injection id")
    }

    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val requestedLorebookIds = lorebookIds.map { it.toUuid("lorebookIds") }.toSet()
    if (!validLorebookIds.containsAll(requestedLorebookIds)) {
        throw BadRequestException("lorebookIds contains unknown lorebook id")
    }

    return ConversationInjectionIds(
        modeInjectionIds = requestedModeInjectionIds,
        lorebookIds = requestedLorebookIds,
    )
}

private suspend fun applyInitialConversationInjections(
    chatService: ChatService,
    settingsStore: SettingsStore,
    conversationId: Uuid,
    modeInjectionIds: List<String>?,
    lorebookIds: List<String>?,
) {
    if (modeInjectionIds == null && lorebookIds == null) {
        return
    }

    val conversation = chatService.getConversationFlow(conversationId).first()
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
        ?: throw NotFoundException("Assistant not found")
    if (!assistant.allowConversationPromptInjection) {
        if (modeInjectionIds.orEmpty().isNotEmpty() || lorebookIds.orEmpty().isNotEmpty()) {
            throw BadRequestException("Conversation prompt injection is not enabled for this assistant")
        }
        return
    }

    val (requestedModeInjectionIds, requestedLorebookIds) = validateConversationInjectionIds(
        settings = settings,
        modeInjectionIds = modeInjectionIds ?: conversation.modeInjectionIds.map { it.toString() },
        lorebookIds = lorebookIds ?: conversation.lorebookIds.map { it.toString() },
    )

    chatService.updateConversationState(conversationId) {
        it.copy(
            modeInjectionIds = requestedModeInjectionIds,
            lorebookIds = requestedLorebookIds,
        )
    }
}
