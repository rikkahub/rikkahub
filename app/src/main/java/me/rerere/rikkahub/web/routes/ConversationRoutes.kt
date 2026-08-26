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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.rerere.rikkahub.service.ChatFailure
import me.rerere.rikkahub.service.ConversationCommands
import me.rerere.rikkahub.service.ConversationQueries
import me.rerere.rikkahub.service.ConversationRuntimeSnapshot
import me.rerere.rikkahub.service.GenerationHandle
import me.rerere.rikkahub.service.SendMessageResult
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.dto.ConversationNodeUpdateEvent
import me.rerere.rikkahub.web.dto.ConversationSnapshotEvent
import me.rerere.rikkahub.web.dto.EditMessageRequest
import me.rerere.rikkahub.web.dto.ErrorEvent
import me.rerere.rikkahub.web.dto.ForkConversationRequest
import me.rerere.rikkahub.web.dto.ForkConversationResponse
import me.rerere.rikkahub.web.dto.GenerationHandleDto
import me.rerere.rikkahub.web.dto.GenerationStateEvent
import me.rerere.rikkahub.web.dto.MessageSearchResultDto
import me.rerere.rikkahub.web.dto.MoveConversationRequest
import me.rerere.rikkahub.web.dto.MoveConversationToFolderRequest
import me.rerere.rikkahub.web.dto.PagedResult
import me.rerere.rikkahub.web.dto.RegenerateRequest
import me.rerere.rikkahub.web.dto.SelectMessageNodeRequest
import me.rerere.rikkahub.web.dto.SendMessageRequest
import me.rerere.rikkahub.web.dto.ToolApprovalRequest
import me.rerere.rikkahub.web.dto.UpdateConversationInjectionsRequest
import me.rerere.rikkahub.web.dto.UpdateConversationTitleRequest
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.dto.toListDto
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

fun Route.conversationRoutes(
    commands: ConversationCommands,
    queries: ConversationQueries,
) {
    route("/conversations") {
        get {
            val assistantId = call.requiredAssistantId()
            val runtimes = queries.observeActiveConversations().first()
            call.respond(
                queries.listConversations(assistantId).first().map { conversation ->
                    conversation.toListDto(runtimes[conversation.id]?.generation)
                }
            )
        }

        get("/paged") {
            val assistantId = call.requiredAssistantId()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            if (offset < 0) throw BadRequestException("offset must be >= 0")
            if (limit !in 1..100) throw BadRequestException("limit must be in 1..100")
            val query = call.request.queryParameters["query"]?.trim()
            val folderValue = call.request.queryParameters["folderId"]?.trim()
            val folderId = folderValue
                ?.takeUnless { it.isBlank() || it == "none" }
                ?.toUuid("folderId")
            val page = queries.pageConversations(
                assistantId = assistantId,
                offset = offset,
                limit = limit,
                search = query,
                folderId = folderId,
                unfiledOnly = folderValue != null && (folderValue.isBlank() || folderValue == "none"),
            )
            val runtimes = queries.observeActiveConversations().first()
            call.respond(
                PagedResult(
                    items = page.items.map { it.toListDto(runtimes[it.id]?.generation) },
                    nextOffset = page.nextOffset,
                )
            )
        }

        get("/search") {
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            if (query.isBlank()) {
                call.respond(emptyList<MessageSearchResultDto>())
                return@get
            }
            call.respond(queries.searchMessages(query).map { result ->
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

        get("/{id}") {
            val snapshot = queries.getConversation(call.conversationId())
            call.respond(snapshot.conversation.toDto(snapshot.generation))
        }

        delete("/{id}") {
            commands.deleteConversation(call.conversationId())
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/pin") {
            val id = call.conversationId()
            val snapshot = queries.getConversation(id)
            commands.updatePinned(id, !snapshot.conversation.isPinned)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/regenerate-title") {
            commands.generateTitle(call.conversationId(), force = true)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/title") {
            val id = call.conversationId()
            val title = call.receive<UpdateConversationTitleRequest>().title.trim()
            if (title.isBlank()) throw BadRequestException("Title must not be blank")
            commands.updateTitle(id, title)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/injections") {
            val id = call.conversationId()
            val request = call.receive<UpdateConversationInjectionsRequest>()
            commands.updateInjections(id, request.modeInjectionIds, request.lorebookIds)
            val snapshot = queries.getConversation(id)
            call.respond(HttpStatusCode.OK, snapshot.conversation.toDto(snapshot.generation))
        }

        post("/{id}/move") {
            val id = call.conversationId()
            val assistantId = call.receive<MoveConversationRequest>().assistantId.toUuid("assistant id")
            commands.moveToAssistant(id, assistantId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/folder") {
            val id = call.conversationId()
            val request = call.receive<MoveConversationToFolderRequest>()
            val folderId = request.folderId?.takeIf(String::isNotBlank)?.toUuid("folder id")
            commands.moveToFolder(id, folderId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/messages") {
            val id = call.conversationId()
            val request = call.receive<SendMessageRequest>()
            val assistantId = request.assistantId?.toUuid("assistant id")
            val result = commands.send(
                conversationId = id,
                assistantId = assistantId,
                parts = request.parts,
                generateResponse = true,
                modeInjectionIds = request.modeInjectionIds,
                lorebookIds = request.lorebookIds,
            )
            val handle = (result as SendMessageResult.GenerationStarted).handle
            call.respond(HttpStatusCode.Accepted, handle.toDto())
        }

        post("/{id}/messages/{messageId}/edit") {
            val id = call.conversationId()
            val messageId = call.parameters["messageId"].toUuid("message id")
            commands.editMessage(id, messageId, call.receive<EditMessageRequest>().parts)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/fork") {
            val id = call.conversationId()
            val messageId = call.receive<ForkConversationRequest>().messageId.toUuid("message id")
            val fork = commands.forkConversation(id, messageId)
            call.respond(HttpStatusCode.Created, ForkConversationResponse(fork.id.toString()))
        }

        delete("/{id}/messages/{messageId}") {
            val id = call.conversationId()
            commands.deleteMessage(id, call.parameters["messageId"].toUuid("message id"))
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        post("/{id}/nodes/{nodeId}/select") {
            val id = call.conversationId()
            val nodeId = call.parameters["nodeId"].toUuid("node id")
            commands.selectMessageNode(id, nodeId, call.receive<SelectMessageNodeRequest>().selectIndex)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/regenerate") {
            val id = call.conversationId()
            val messageId = call.receive<RegenerateRequest>().messageId.toUuid("message id")
            call.respond(HttpStatusCode.Accepted, commands.regenerate(id, messageId).toDto())
        }

        post("/{id}/generations/{generationId}/stop") {
            val id = call.conversationId()
            val generationId = call.parameters["generationId"].toUuid("generation id")
            commands.stop(id, generationId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "stopped"))
        }

        post("/{id}/generations/{generationId}/tool-approval") {
            val id = call.conversationId()
            val generationId = call.parameters["generationId"].toUuid("generation id")
            val request = call.receive<ToolApprovalRequest>()
            val handle = commands.approveTool(
                id,
                generationId,
                request.toolCallId,
                request.approved,
                request.reason,
                request.answer,
            )
            call.respond(HttpStatusCode.Accepted, handle.toDto())
        }

        sse("/{id}/stream") {
            val id = call.parameters["id"].toUuid("conversation id")
            commands.ensureConversation(id)
            heartbeat { period = 1.seconds }

            var previous: ConversationRuntimeSnapshot? = null
            val snapshotEvents = queries.observeConversation(id).map(StreamPayload::Snapshot)
            val failureEvents = queries.observeFailures(id).map(StreamPayload::Failure)
            merge(snapshotEvents, failureEvents).collect { payload ->
                when (payload) {
                    is StreamPayload.Failure -> {
                        val failure = payload.value
                        send(
                            event = "error",
                            data = JsonInstant.encodeToString(
                                ErrorEvent(
                                    revision = queries.getConversation(id).revision,
                                    code = failure.code.name,
                                    message = failure.message,
                                    retryable = failure.retryable,
                                    generationId = failure.generationId?.toString(),
                                )
                            ),
                        )
                    }

                    is StreamPayload.Snapshot -> {
                        val current = payload.value
                        val currentDto = current.conversation.toDto(current.generation)
                        val prior = previous
                        val priorDto = prior?.conversation?.toDto(prior.generation)
                        val event = when {
                            prior != null && prior.conversation == current.conversation &&
                                prior.generation != current.generation -> {
                                "generation_state" to JsonInstant.encodeToString(
                                    GenerationStateEvent(
                                        revision = current.revision,
                                        conversationId = id.toString(),
                                        generation = current.generation.toDto(),
                                    )
                                )
                            }

                            priorDto != null && priorDto.singleNodeDiffOrNull(currentDto) != null -> {
                                val diff = checkNotNull(priorDto.singleNodeDiffOrNull(currentDto))
                                "node_update" to JsonInstant.encodeToString(
                                    ConversationNodeUpdateEvent(
                                        revision = current.revision,
                                        conversationId = currentDto.id,
                                        nodeId = diff.node.id,
                                        nodeIndex = diff.nodeIndex,
                                        node = diff.node,
                                        updateAt = currentDto.updateAt,
                                    )
                                )
                            }

                            else -> "snapshot" to JsonInstant.encodeToString(
                                ConversationSnapshotEvent(
                                    revision = current.revision,
                                    conversation = currentDto,
                                )
                            )
                        }
                        send(event = event.first, data = event.second)
                        previous = current
                    }
                }
            }
        }
    }
}

private sealed interface StreamPayload {
    data class Snapshot(val value: ConversationRuntimeSnapshot) : StreamPayload
    data class Failure(val value: ChatFailure) : StreamPayload
}

private fun GenerationHandle.toDto(): GenerationHandleDto = GenerationHandleDto(
    conversationId = conversationId.toString(),
    generationId = generationId.toString(),
    state = checkNotNull(state.toDto()),
)

private fun io.ktor.server.application.ApplicationCall.conversationId(): Uuid =
    parameters["id"].toUuid("conversation id")

private fun io.ktor.server.application.ApplicationCall.requiredAssistantId(): Uuid =
    request.queryParameters["assistantId"].toUuid("assistantId")
