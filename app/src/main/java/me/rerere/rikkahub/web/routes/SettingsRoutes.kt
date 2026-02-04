package me.rerere.rikkahub.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.distinctUntilChanged
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

fun Route.settingsRoutes(
    settingsStore: SettingsStore
) {
    route("/settings") {
        sse("/stream") {
            settingsStore.settingsFlow
                .collect { settings ->
                    val json = JsonInstant.encodeToString(settings)
                    send(data = json, event = "update")
                }
        }
    }
}
