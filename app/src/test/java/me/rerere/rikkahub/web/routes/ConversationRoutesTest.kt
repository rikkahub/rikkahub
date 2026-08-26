package me.rerere.rikkahub.web.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatCommandException
import me.rerere.rikkahub.service.ChatFailure
import me.rerere.rikkahub.service.ChatFailureCode
import me.rerere.rikkahub.service.ConversationCommands
import me.rerere.rikkahub.service.ConversationQueries
import me.rerere.rikkahub.service.ConversationRuntimeSnapshot
import me.rerere.rikkahub.service.GenerationHandle
import me.rerere.rikkahub.service.GenerationState
import me.rerere.rikkahub.service.SendMessageResult
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.ApiException
import me.rerere.rikkahub.web.dto.ErrorResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.uuid.Uuid

class ConversationRoutesTest {
    @Test
    fun `list requires explicit assistant id`() = testConversationApplication {
        val response = client.get("/conversations")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `send returns typed generation handle`() = testConversationApplication {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val response = client.post("/conversations/$conversationId/messages") {
            header("Content-Type", ContentType.Application.Json.toString())
            setBody(
                """{"parts":[{"type":"text","text":"hello"}],"assistantId":"$assistantId"}"""
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(conversationId.toString()))
        assertTrue(body.contains("generationId"))
        assertTrue(body.contains("queued"))
    }

    @Test
    fun `stale generation id maps to conflict`() = testConversationApplication {
        val conversationId = Uuid.random()
        val generationId = Uuid.random()
        val response = client.post(
            "/conversations/$conversationId/generations/$generationId/stop"
        )

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("Conflict"))
    }

    @Test
    fun `stream emits snapshot generation state and stable error`() {
        val conversationId = Uuid.random()
        val generationId = Uuid.random()
        val conversation = Conversation.ofId(conversationId, Uuid.random())
        val snapshots = listOf(
            ConversationRuntimeSnapshot(conversation = conversation, revision = 4),
            ConversationRuntimeSnapshot(
                conversation = conversation,
                revision = 5,
                generation = GenerationState.Running(generationId),
            ),
        )
        val failure = ChatFailure(
            code = ChatFailureCode.Network,
            message = "Network request failed",
            retryable = true,
            conversationId = conversationId,
            generationId = generationId,
        )

        testConversationApplication(snapshots, listOf(failure)) {
            val body = client.get("/conversations/$conversationId/stream").bodyAsText()

            assertTrue(body.contains("event: snapshot"))
            assertTrue(body.contains("\"revision\":4"))
            assertTrue(body.contains("event: generation_state"))
            assertTrue(body.contains("\"revision\":5"))
            assertTrue(body.contains("event: error"))
            assertTrue(body.contains("Network request failed"))
        }
    }

    private fun testConversationApplication(
        snapshots: List<ConversationRuntimeSnapshot> = emptyList(),
        failures: List<ChatFailure> = emptyList(),
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) =
        testApplication {
            val proxy = applicationProxy(snapshots, failures)
            application {
                install(ContentNegotiation) { json(JsonInstant) }
                install(SSE)
                install(StatusPages) {
                    exception<ApiException> { call, cause ->
                        call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
                    }
                    exception<ChatCommandException> { call, cause ->
                        val status = when (cause.failure.code) {
                            ChatFailureCode.InvalidRequest -> HttpStatusCode.BadRequest
                            ChatFailureCode.NotFound -> HttpStatusCode.NotFound
                            ChatFailureCode.Conflict -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.InternalServerError
                        }
                        call.respond(
                            status,
                            ErrorResponse(
                                error = cause.failure.message,
                                code = status.value,
                                failureCode = cause.failure.code.name,
                            )
                        )
                    }
                }
                routing {
                    conversationRoutes(
                        proxy as ConversationCommands,
                        proxy as ConversationQueries,
                    )
                }
            }
            block()
        }

    private fun applicationProxy(
        snapshots: List<ConversationRuntimeSnapshot>,
        failures: List<ChatFailure>,
    ): Any {
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ConversationCommands::class.java, ConversationQueries::class.java),
        ) { _, method, args ->
            when (method.name) {
                "ensureConversation" -> snapshots.first()
                "getConversation" -> snapshots.last()
                "observeConversation" -> flowOf(*snapshots.toTypedArray())
                "observeFailures" -> failures.asFlow()
                "send" -> {
                    val conversationId = args[0] as Uuid
                    val assistantId = args[1] as Uuid?
                    if (assistantId == null) {
                        throw ChatCommandException(
                            ChatFailure(
                                code = ChatFailureCode.InvalidRequest,
                                message = "assistantId is required",
                                conversationId = conversationId,
                            )
                        )
                    }
                    val generationId = Uuid.random()
                    SendMessageResult.GenerationStarted(
                        GenerationHandle(
                            conversationId,
                            generationId,
                            GenerationState.Queued(generationId),
                        )
                    )
                }

                "stop" -> throw ChatCommandException(
                    ChatFailure(
                        code = ChatFailureCode.Conflict,
                        message = "Stale generation id",
                        conversationId = args[0] as Uuid,
                        generationId = args[1] as Uuid,
                    )
                )

                "toString" -> "ConversationApplicationProxy"
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    }
}
