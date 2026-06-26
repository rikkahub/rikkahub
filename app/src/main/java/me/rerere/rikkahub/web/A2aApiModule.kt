package me.rerere.rikkahub.web

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.a2a.A2aTaskRegistry
import me.rerere.rikkahub.web.a2a.a2aAgentCardRoute
import me.rerere.rikkahub.web.a2a.a2aRpcRoute
import me.rerere.rikkahub.web.dto.ErrorResponse

fun Application.configureA2aApi(
    appScope: CoroutineScope,
    chatService: ChatService,
    settingsStore: SettingsStore,
    a2aTaskRegistry: A2aTaskRegistry,
    cardBaseUrl: () -> String,
) {
    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled A2A API error", cause)
            val (status, body) = mapThrowableToErrorResponse(cause)
            call.respond(status, body)
        }
    }

    routing {
        a2aAgentCardRoute(settingsStore = settingsStore, cardBaseUrl = cardBaseUrl)
        a2aRpcRoute(
            appScope = appScope,
            chatService = chatService,
            settingsStore = settingsStore,
            registry = a2aTaskRegistry,
        )
    }
}
