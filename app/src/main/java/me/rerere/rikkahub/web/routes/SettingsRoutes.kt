package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.UpdateAssistantModelRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantRequest

fun Route.settingsRoutes(
    settingsStore: SettingsStore
) {
    route("/settings") {
        post("/assistant") {
            val request = call.receive<UpdateAssistantRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")

            settingsStore.updateAssistant(assistantId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }


        post("/assistant/model") {
            val request = call.receive<UpdateAssistantModelRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")
            val modelId = request.modelId.toUuid("modelId")

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            val model = settings.findModelById(modelId)
                ?: throw NotFoundException("Model not found")
            if (model.type != ModelType.CHAT) {
                throw BadRequestException("modelId must be a chat model")
            }

            settingsStore.updateAssistantModel(assistantId, modelId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        sse("/stream") {
            settingsStore.settingsFlow
                .collect { settings ->
                    val json = JsonInstant.encodeToString(settings)
                    send(data = json, event = "update")
                }
        }
    }
}
