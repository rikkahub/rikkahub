package me.rerere.rikkahub.web.routes

import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.ApiException
import me.rerere.rikkahub.web.dto.ErrorResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.uuid.Uuid

class FolderRoutesTest {
    @Test
    fun `current assistant can rename its folder`() = runBlocking {
        val currentAssistantId = Uuid.random()
        val folder = Folder(assistantId = currentAssistantId, name = "Before")
        val fixture = FolderRouteFixture(currentAssistantId, folder)

        fixture.application { client ->
            val response = client.post("/folders/${folder.id}/rename") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"After"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }

        verify(fixture.folderRepo).renameFolder(folder.id, "After")
    }

    @Test
    fun `current assistant can delete its folder`() = runBlocking {
        val currentAssistantId = Uuid.random()
        val folder = Folder(assistantId = currentAssistantId, name = "Current")
        val fixture = FolderRouteFixture(currentAssistantId, folder)

        fixture.application { client ->
            val response = client.delete("/folders/${folder.id}")

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

        verify(fixture.chatService).hasGeneratingConversationInFolder(folder.id)
        verify(fixture.chatService).deleteFolder(folder)
    }

    @Test
    fun `other assistant folder cannot be renamed and remains unchanged`() = runBlocking {
        val currentAssistantId = Uuid.random()
        val foreignFolder = Folder(assistantId = Uuid.random(), name = "Foreign")
        val fixture = FolderRouteFixture(currentAssistantId, foreignFolder)

        fixture.application { client ->
            val response = client.post("/folders/${foreignFolder.id}/rename") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Changed"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        verify(fixture.folderRepo, never()).renameFolder(foreignFolder.id, "Changed")
        assertEquals("Foreign", foreignFolder.name)
    }

    @Test
    fun `other assistant folder cannot be deleted or queried for generation status`() = runBlocking {
        val currentAssistantId = Uuid.random()
        val foreignFolder = Folder(assistantId = Uuid.random(), name = "Foreign")
        val fixture = FolderRouteFixture(currentAssistantId, foreignFolder)

        fixture.application { client ->
            val response = client.delete("/folders/${foreignFolder.id}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        verify(fixture.chatService, never()).hasGeneratingConversationInFolder(foreignFolder.id)
        verify(fixture.chatService, never()).deleteFolder(foreignFolder)
        assertEquals("Foreign", foreignFolder.name)
    }
}

private class FolderRouteFixture(currentAssistantId: Uuid, folder: Folder) {
    val chatService: ChatService = mock(ChatService::class.java)
    val folderRepo: FolderRepository = mock(FolderRepository::class.java)
    private val settingsStore: SettingsStore = mock(SettingsStore::class.java)

    init {
        `when`(settingsStore.settingsFlow).thenReturn(MutableStateFlow(Settings(assistantId = currentAssistantId)))
        runBlocking {
            `when`(folderRepo.getFolderById(folder.id)).thenReturn(folder)
        }
    }

    fun application(test: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) {
                json(JsonInstant)
            }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
                }
            }
            routing {
                folderRoutes(chatService, folderRepo, settingsStore)
            }
        }
        test(client)
    }
}
