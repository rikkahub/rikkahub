package me.rerere.rikkahub.web.routes

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ConversationSessionHandle
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class ConversationRoutesSessionTest {
    @Test
    fun `regenerate initializes and reads through retained session handle`() = runBlocking {
        val fixture = ConversationRouteSessionFixture()

        fixture.application { client ->
            val response = client.post("/conversations/${fixture.conversationId}/regenerate") {
                contentType(ContentType.Application.Json)
                setBody("""{"messageId":"${fixture.message.id}"}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
        }

        val order = inOrder(fixture.chatService)
        order.verify(fixture.chatService).acquireConversationSessionHandle(fixture.conversationId)
        order.verify(fixture.chatService).initializeConversation(fixture.conversationId)
        order.verify(fixture.chatService).regenerateAtMessage(fixture.conversationId, fixture.message)
        assertEquals(1, fixture.releaseCount.get())
        verify(fixture.chatService, never()).getConversationFlow(fixture.conversationId)
        verify(fixture.chatService, never()).addConversationReference(fixture.conversationId)
        verify(fixture.chatService, never()).removeConversationReference(fixture.conversationId)
    }

    @Test
    fun `initialization failure still closes retained session handle`() = runBlocking {
        val fixture = ConversationRouteSessionFixture()
        `when`(fixture.chatService.initializeConversation(fixture.conversationId))
            .thenThrow(IllegalStateException("initialization failed"))

        fixture.application { client ->
            val response = client.post("/conversations/${fixture.conversationId}/regenerate") {
                contentType(ContentType.Application.Json)
                setBody("""{"messageId":"${fixture.message.id}"}""")
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        assertEquals(1, fixture.releaseCount.get())
        verify(fixture.chatService, never()).regenerateAtMessage(fixture.conversationId, fixture.message)
    }

    @Test
    fun `conversation stream closes and releases retained handle after deletion`() = runBlocking {
        val fixture = ConversationRouteSessionFixture()

        fixture.application { client ->
            coroutineScope {
                val responseBody = async {
                    val response = client.get("/conversations/${fixture.conversationId}/stream") {
                        accept(ContentType.Text.EventStream)
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    response.bodyAsText()
                }

                withTimeout(5_000) {
                    fixture.conversationExists.subscriptionCount.first { subscribers -> subscribers > 0 }
                }
                fixture.conversationExists.value = false

                withTimeout(5_000) { responseBody.await() }
            }
        }

        assertEquals(1, fixture.releaseCount.get())
        verify(fixture.conversationRepo).observeConversationExistsById(fixture.conversationId)
        Unit
    }
}

private class ConversationRouteSessionFixture {
    val conversationId: Uuid = Uuid.random()
    private val assistantId: Uuid = Uuid.random()
    val message: UIMessage = UIMessage.user("hello")
    private val conversation = Conversation.ofId(
        id = conversationId,
        assistantId = assistantId,
        messages = listOf(MessageNode.of(message)),
    )
    val conversationExists = MutableStateFlow(true)
    val releaseCount = AtomicInteger(0)
    val chatService: ChatService = mock(ChatService::class.java)
    val conversationRepo: ConversationRepository = mock(ConversationRepository::class.java)
    private val folderRepo: FolderRepository = mock(FolderRepository::class.java)
    private val settingsStore: SettingsStore = mock(SettingsStore::class.java)
    private val filesManager: FilesManager = mock(FilesManager::class.java)

    init {
        `when`(settingsStore.settingsFlow).thenReturn(MutableStateFlow(Settings(assistantId = assistantId)))
        `when`(conversationRepo.observeConversationExistsById(conversationId)).thenReturn(conversationExists)
        `when`(chatService.errors).thenReturn(MutableStateFlow(emptyList()))
        runBlocking {
            `when`(conversationRepo.getConversationById(conversationId)).thenReturn(conversation)
            `when`(chatService.initializeConversation(conversationId)).thenReturn(Unit)
        }
        `when`(chatService.acquireConversationSessionHandle(conversationId)).thenReturn(
            ConversationSessionHandle(
                conversation = MutableStateFlow(conversation),
                generationJob = MutableStateFlow<Job?>(null),
                processingStatus = MutableStateFlow(null),
                release = { releaseCount.incrementAndGet() },
            )
        )
    }

    fun application(test: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) {
                json(JsonInstant)
            }
            install(StatusPages) {
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
            install(SSE)
            routing {
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore, filesManager)
            }
        }
        test(client)
    }
}
