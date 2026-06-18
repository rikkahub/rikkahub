package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessagePart.Tool
import me.rerere.ai.ui.UIMessagePart.Text

// Part-metadata marker keys for a synthetic agent-event message (#290): the visible USER message the
// agent-event drain appends at a turn-end seam. These keys let FTS indexing, message stats and the
// same-role sanitizer recognise and EXCLUDE a synthetic message so it never pollutes search/stats nor
// merges into a real user turn (the SYNTHETIC_DISTINCTNESS invariant). They live in the model layer so
// db/fts, db/dao, model and service all share one source of truth (no service -> db/model back-dep).
const val SYNTHETIC_KIND_METADATA_KEY = "rikkahubSyntheticKind"
const val AGENT_EVENT_SYNTHETIC_KIND = "agent_event"
const val AGENT_EVENT_ID_METADATA_KEY = "agentEventId"
const val AGENT_EVENT_KIND_METADATA_KEY = "agentEventKind"

/**
 * True if [this] message is a synthetic agent-event message — any Text part carries the synthetic-kind
 * marker. Such messages are display-only context injected by the agent-event drain (#290) and must be
 * excluded from FTS, stats and same-role collapse.
 */
fun UIMessage.isSyntheticAgentEvent(): Boolean = syntheticAgentEventId() != null

/**
 * Returns the synthetic event id when this message is an agent-event replay marker.
 */
fun UIMessage.syntheticAgentEventId(): String? =
    syntheticAgentEventMarker()?.second

fun UIMessage.syntheticAgentEventMarker(): Pair<String, String>? {
    for (part in parts) {
        val metadata = when (part) {
            is Tool -> part.metadata
            is Text -> part.metadata
            else -> null
        }
        if (metadata == null) continue
        if ((metadata[SYNTHETIC_KIND_METADATA_KEY] as? JsonPrimitive)?.contentOrNull != AGENT_EVENT_SYNTHETIC_KIND) continue
        val eventId = (metadata[AGENT_EVENT_ID_METADATA_KEY] as? JsonPrimitive)?.contentOrNull ?: continue
        val eventKind = (metadata[AGENT_EVENT_KIND_METADATA_KEY] as? JsonPrimitive)?.contentOrNull ?: continue
        return eventKind to eventId
    }
    return null
}
