package me.rerere.rikkahub.voiceagent.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

data class VoiceDiagnosticEvent(
    val name: String,
    val detail: String,
    val at: Instant = Clock.System.now(),
)

class VoiceDiagnostics {
    private val _events = MutableStateFlow<List<VoiceDiagnosticEvent>>(emptyList())
    private val listeners = mutableSetOf<(VoiceDiagnosticEvent) -> Unit>()
    val events: StateFlow<List<VoiceDiagnosticEvent>> = _events.asStateFlow()

    fun record(event: VoiceDiagnosticEvent) {
        _events.update { it + event }
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            listener(event)
        }
    }

    fun record(name: String, detail: String = "") {
        record(VoiceDiagnosticEvent(name = name, detail = detail))
    }

    fun addListener(listener: (VoiceDiagnosticEvent) -> Unit): () -> Unit {
        synchronized(listeners) {
            listeners += listener
        }
        return {
            synchronized(listeners) {
                listeners -= listener
            }
        }
    }
}
