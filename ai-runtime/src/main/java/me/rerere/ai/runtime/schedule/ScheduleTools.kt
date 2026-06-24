package me.rerere.ai.runtime.schedule

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.contract.TaskSchedulePort
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * Neutral `schedule_create` / `schedule_list` / `schedule_delete` tool factories over the
 * per-conversation schedule store (SPEC.md M4). Backed ONLY by [TaskSchedulePort]: parsing/wire-
 * shaping happens here, every mutation goes through the port, and the binding repository behind it
 * enforces ALL schedule invariants (target spawnable, per-conversation/per-user caps, minimum
 * recurring interval, prompt bound, conversation scoping) for tools and UI alike — nothing is
 * validated tool-handler-only except the input format itself.
 *
 * Approval surface, mirroring the spec's tool table: `schedule_create` and `schedule_delete` mutate
 * persistent firing state, so both are `needsApproval = true`; `schedule_list` is a read-only
 * inspection and is not gated.
 *
 * Error model, mirroring [me.rerere.ai.runtime.board.buildBoardTools]: malformed INPUT (missing
 * required arg, bad uuid, unknown enum value) throws via [error] and rides the existing
 * tool-error-as-output path; domain REJECTIONS the repository reports (cap breach, unspawnable
 * target, cross-conversation id) come back as structured `{ok:false, error}` results so the model
 * can react without aborting the turn.
 */
fun buildScheduleTools(port: TaskSchedulePort): List<Tool> = listOf(
    Tool(
        name = "schedule_create",
        needsApproval = true,
        description = """
            Schedule a prompt to run against a target assistant at a future time, in this conversation.
            - `targetAssistant`: assistant UUID of the spawnable assistant the scheduled run targets
              (required). This is not the assistant's display name; if you only know a name, ask the user
              to pick/confirm the target or use a surfaced UUID from existing schedule output.
            - `prompt`: the message sent to the target assistant when the schedule fires (required).
            - `kind`: `one_shot` (fires once) or `recurring` (fires on a repeating cadence) (required).
            - `firstFireAt`: epoch millis (wall clock) of the first or only fire (required).
            - `timeZoneId`: IANA zone id (e.g. "America/New_York"); recurrence is computed in this zone (required).
            - `recurrenceSpec`: required iff `kind` is `recurring` — `{ "every": N,
              "unit": "MINUTES"|"HOURS"|"DAYS", "timeOfDay"?: "HH:mm" }`. MINUTES/HOURS are fixed
              elapsed-time intervals from `firstFireAt`; DAYS is calendar-based in `timeZoneId`, and
              `timeOfDay` pins the local HH:mm fire time.
            The repository enforces target-spawnable, active-schedule caps, minimum recurring interval,
            prompt length, and valid timezone/spec. Rejections return `{ok:false,error}` with the exact
            reason instead of aborting the turn. Returns the created schedule.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("targetAssistant", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "UUID of the spawnable assistant to run, not the assistant display name"
                        )
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Message sent to the target assistant when the schedule fires")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { WIRE_KIND.keys.forEach { add(it) } })
                        put("description", "one_shot fires once; recurring fires on a repeating cadence")
                    })
                    put("firstFireAt", buildJsonObject {
                        put("type", "integer")
                        put("description", "Epoch millis (wall clock) of the first or only fire")
                    })
                    put("timeZoneId", buildJsonObject {
                        put("type", "string")
                        put("description", "IANA zone id; recurrence is computed in this zone")
                    })
                    put("recurrenceSpec", buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "Required iff kind is recurring: { every, unit (MINUTES|HOURS|DAYS), " +
                                "timeOfDay? }. timeOfDay is local HH:mm and applies to DAYS."
                        )
                    })
                },
                required = listOf("targetAssistant", "prompt", "kind", "firstFireAt", "timeZoneId")
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val draft = ScheduleDraft(
                targetAssistantId = params.requireUuid("targetAssistant"),
                prompt = params.requireString("prompt"),
                kind = params.requireKind("kind"),
                firstFireAt = params.requireLong("firstFireAt"),
                timeZoneId = params.requireString("timeZoneId"),
                recurrenceSpec = params.optionalJsonString("recurrenceSpec"),
            )
            port.create(draft).toToolOutput()
        }
    ),
    Tool(
        name = "schedule_list",
        description = """
            List the schedules in this conversation, including each schedule's target assistant, kind,
            next fire time, and whether it is currently enabled.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { }, required = null)
        },
        execute = {
            val schedules = port.list()
            buildJsonObject {
                put("ok", true)
                putJsonArray("schedules") { schedules.forEach { add(it.toJson()) } }
            }.asToolText()
        }
    ),
    Tool(
        name = "schedule_delete",
        needsApproval = true,
        description = """
            Delete a schedule in this conversation by id. A schedule that does not belong to this
            conversation is rejected, never silently deleted.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "The schedule id (uuid)")
                    })
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val id = args.jsonObject.requireUuid("id")
            port.delete(id).toToolOutput()
        }
    ),
)

/** Wire names of [ScheduleKind], stable lower_snake — the model-facing vocabulary. */
private val WIRE_KIND: Map<String, ScheduleKind> = mapOf(
    "one_shot" to ScheduleKind.ONE_SHOT,
    "recurring" to ScheduleKind.RECURRING,
)

private val KIND_WIRE: Map<ScheduleKind, String> =
    WIRE_KIND.entries.associate { (wire, kind) -> kind to wire }

private fun JsonObject.requireString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("$name is required")

private fun JsonObject.requireLong(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: error("$name is required and must be an integer")

private fun JsonObject.requireUuid(name: String): Uuid =
    runCatching { Uuid.parse(requireString(name)) }
        .getOrElse { error("$name is not a valid uuid: ${this[name]?.jsonPrimitive?.contentOrNull}") }

private fun JsonObject.requireKind(name: String): ScheduleKind {
    val wire = requireString(name)
    return WIRE_KIND[wire] ?: error("unknown kind: $wire, must be one of ${WIRE_KIND.keys}")
}

/** The recurrence spec rides the neutral port as already-serialized JSON; serialize the object back. */
private fun JsonObject.optionalJsonString(name: String): String? =
    this[name]?.takeIf { it is JsonObject }?.toString()

private fun ScheduleSnapshot.toJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("targetAssistant", targetAssistantId.toString())
    put("prompt", prompt)
    put("owner", owner.name)
    put("kind", KIND_WIRE.getValue(kind))
    put("firstFireAt", firstFireAt)
    put("nextFireAt", nextFireAt)
    put("timeZoneId", timeZoneId)
    recurrenceSpec?.let { put("recurrenceSpec", it) }
    put("enabled", enabled)
    lastFiredAt?.let { put("lastFiredAt", it) }
}

private fun acceptedJson(snapshot: ScheduleSnapshot): JsonObject = buildJsonObject {
    put("ok", true)
    put("schedule", snapshot.toJson())
}

private fun rejectionJson(reason: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", reason)
}

private fun ScheduleMutationResult.toToolOutput(): List<UIMessagePart> = when (this) {
    is ScheduleMutationResult.Accepted -> acceptedJson(snapshot)
    is ScheduleMutationResult.Rejected -> rejectionJson(reason)
}.asToolText()

private fun JsonObject.asToolText(): List<UIMessagePart> = listOf(UIMessagePart.Text(toString()))
