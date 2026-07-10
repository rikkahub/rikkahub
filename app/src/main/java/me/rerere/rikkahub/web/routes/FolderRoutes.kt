package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.CreateFolderRequest
import me.rerere.rikkahub.web.dto.RenameFolderRequest
import me.rerere.rikkahub.web.dto.toDto
import kotlin.uuid.Uuid

internal fun Folder?.requireCurrentAssistant(assistantId: Uuid): Folder {
    val folder = this
    if (folder == null || folder.assistantId != assistantId) {
        throw NotFoundException("Folder not found")
    }
    return folder
}

fun Route.folderRoutes(
    chatService: ChatService,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
) {
    route("/folders") {
        // GET /api/folders - List folders of current assistant
        get {
            val settings = settingsStore.settingsFlow.first()
            val folders = folderRepo.getFoldersOfAssistant(settings.assistantId).first()
            call.respond(folders.map { it.toDto() })
        }

        // POST /api/folders - Create a folder under current assistant
        post {
            val request = call.receive<CreateFolderRequest>()
            val name = request.name.trim()
            if (name.isEmpty()) {
                throw BadRequestException("Folder name must not be blank")
            }

            val settings = settingsStore.settingsFlow.first()
            val folder = folderRepo.createFolder(settings.assistantId, name)
            call.respond(HttpStatusCode.Created, folder.toDto())
        }

        // POST /api/folders/{id}/rename - Rename a folder
        post("/{id}/rename") {
            val uuid = call.parameters["id"].toUuid("folder id")
            val request = call.receive<RenameFolderRequest>()
            val name = request.name.trim()
            if (name.isEmpty()) {
                throw BadRequestException("Folder name must not be blank")
            }

            val settings = settingsStore.settingsFlow.first()
            folderRepo.getFolderById(uuid).requireCurrentAssistant(settings.assistantId)
            folderRepo.renameFolder(uuid, name)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // DELETE /api/folders/{id} - Delete a folder (conversations are kept, just unfiled)
        delete("/{id}") {
            val uuid = call.parameters["id"].toUuid("folder id")
            val settings = settingsStore.settingsFlow.first()
            folderRepo.getFolderById(uuid).requireCurrentAssistant(settings.assistantId)

            // Refuse to delete while a conversation inside is still generating
            if (chatService.hasGeneratingConversationInFolder(uuid)) {
                throw ConflictException("Folder has a generating conversation")
            }

            chatService.deleteFolder(uuid)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
