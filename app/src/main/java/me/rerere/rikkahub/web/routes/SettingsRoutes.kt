package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.UpdateAssistantModelRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantRequest
import me.rerere.rikkahub.web.dto.UpdateAssistantThinkingBudgetRequest
import me.rerere.rikkahub.web.dto.UpdateBuiltInToolRequest
import me.rerere.rikkahub.web.dto.UpdateSearchEnabledRequest
import me.rerere.rikkahub.web.dto.UpdateSearchServiceRequest
import java.util.Locale

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

        post("/assistant/thinking-budget") {
            val request = call.receive<UpdateAssistantThinkingBudgetRequest>()
            val assistantId = request.assistantId.toUuid("assistantId")
            val thinkingBudget = request.thinkingBudget

            val settings = settingsStore.settingsFlow.value
            if (settings.assistants.none { it.id == assistantId }) {
                throw NotFoundException("Assistant not found")
            }

            settingsStore.updateAssistantThinkingBudget(assistantId, thinkingBudget)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/enabled") {
            val request = call.receive<UpdateSearchEnabledRequest>()

            settingsStore.update { settings ->
                settings.copy(enableWebSearch = request.enabled)
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/service") {
            val request = call.receive<UpdateSearchServiceRequest>()

            settingsStore.update { settings ->
                if (settings.searchServices.isEmpty()) {
                    throw BadRequestException("No search services configured")
                }
                if (request.index !in settings.searchServices.indices) {
                    throw BadRequestException("search service index out of range")
                }
                settings.copy(searchServiceSelected = request.index)
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/model/built-in-tool") {
            val request = call.receive<UpdateBuiltInToolRequest>()
            val modelId = request.modelId.toUuid("modelId")
            val targetTool = parseBuiltInTool(request.tool)

            settingsStore.update { settings ->
                val model = settings.findModelById(modelId)
                    ?: throw NotFoundException("Model not found")
                if (model.type != ModelType.CHAT) {
                    throw BadRequestException("modelId must be a chat model")
                }

                val updatedModel = model.copy(
                    tools = if (request.enabled) {
                        model.tools + targetTool
                    } else {
                        model.tools - targetTool
                    }
                )

                settings.copy(
                    providers = settings.providers.map { provider ->
                        provider.editModel(updatedModel)
                    }
                )
            }

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

private fun parseBuiltInTool(tool: String): BuiltInTools {
    return when (tool.trim().lowercase(Locale.ROOT)) {
        "search" -> BuiltInTools.Search
        "url_context", "url-context", "urlcontext" -> BuiltInTools.UrlContext
        else -> throw BadRequestException("Unsupported built-in tool")
    }
}
