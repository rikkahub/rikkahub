package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.RegenerateRequest
import me.rerere.rikkahub.web.dto.SendMessageRequest
import me.rerere.rikkahub.web.dto.ToolApprovalRequest
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.dto.toListDto
import me.rerere.rikkahub.web.dto.toUIParts
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private fun String?.toUuid(name: String = "id"): Uuid {
    if (this == null) throw BadRequestException("Missing $name")
    return runCatching { Uuid.parse(this) }.getOrNull()
        ?: throw BadRequestException("Invalid $name")
}

fun Route.conversationRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore
) {
    route("/conversations") {
        // GET /api/conversations - List conversations of current assistant
        get {
            val settings = settingsStore.settingsFlow.first()
            val conversations = conversationRepo
                .getConversationsOfAssistant(settings.assistantId)
                .first()
                .map { it.toListDto() }
            call.respond(conversations)
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

        // POST /api/conversations/{id}/messages - Send a message
        post("/{id}/messages") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<SendMessageRequest>()

            chatService.initializeConversation(uuid)
            chatService.sendMessage(uuid, request.toUIParts(), answer = true)

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
            chatService.cleanupConversation(uuid)
            call.respond(HttpStatusCode.OK, mapOf("status" to "stopped"))
        }

        // POST /api/conversations/{id}/tool-approval - Handle tool approval
        post("/{id}/tool-approval") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<ToolApprovalRequest>()
            chatService.handleToolApproval(uuid, request.toolCallId, request.approved, request.reason)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // SSE /api/conversations/{id}/stream - Stream conversation updates
        sse("/{id}/stream") {
            val id = call.parameters["id"] ?: return@sse
            val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@sse

            chatService.initializeConversation(uuid)
            chatService.addConversationReference(uuid)

            try {
                chatService.getConversationFlow(uuid).collect { conversation ->
                    val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
                    val dto = conversation.toDto(isGenerating)
                    val json = JsonInstant.encodeToString(dto)
                    send(data = json, event = "update")
                }
            } finally {
                chatService.removeConversationReference(uuid)
            }
        }
    }
}
