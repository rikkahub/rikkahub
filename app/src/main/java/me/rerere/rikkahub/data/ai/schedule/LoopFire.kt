package me.rerere.rikkahub.data.ai.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * The agent-event a CONVERSATION_EVENT schedule (#364 `/loop`) enqueues when it fires: the loop's
 * prompt, to be injected as a USER-role turn into the bound conversation at its next idle turn-end.
 * The in-session counterpart to [me.rerere.rikkahub.data.ai.subagent.SubagentCompletion] — but where a
 * subagent completion REPORTS a finished detached run, a loop fire DRIVES a new turn, so the drain
 * always continues generation on it (no anchor to resolve, no selected-branch guard).
 *
 * [dedupeKey] is the firing schedule's per-fire `runId`, so AT_MOST_ONCE is inherited from the queue's
 * unique enqueue + claim/consume transaction: a duplicate worker that re-fires the same window (its
 * claim lost) never double-injects, and a replayed enqueue collapses to one delivery.
 *
 * The payload is opaque to the queue (the drain only renders [promptOf] as text), so the shape here is
 * the delivery contract, not a queue concern.
 */
object LoopFire {
    const val KIND = "loop.fire"

    private val json = Json { ignoreUnknownKeys = true }

    /** Build the durable payload for one loop fire. PURE so the shape is unit-testable. */
    fun payloadJson(prompt: String, scheduleId: Uuid): String = buildJsonObject {
        put("prompt", prompt)
        put("scheduleId", scheduleId.toString())
    }.toString()

    /**
     * The prompt to inject, extracted from a loop-fire payload. A malformed/blank payload yields null
     * so the drain can terminalize it as FAILED rather than inject an empty user turn.
     */
    fun promptOf(payloadJson: String): String? =
        runCatching {
            json.parseToJsonElement(payloadJson).jsonObject["prompt"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
