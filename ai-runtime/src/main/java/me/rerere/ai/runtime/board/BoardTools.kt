package me.rerere.ai.runtime.board

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.BoardItemSnapshot
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.TaskBoardPort
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * Neutral `task_create` / `task_get` / `task_list` / `task_update` tool factories over the
 * per-conversation work-item board (SPEC.md M1/M3, spec assumption 5). Backed ONLY by
 * [TaskBoardPort]: parsing/wire-shaping happens here, every read and mutation goes through the
 * port, and the binding repository behind it enforces ALL board invariants for tools and UI
 * alike (maintainer decision #4) — nothing is validated tool-handler-only except the input
 * format itself.
 *
 * Error model, mirroring `buildMemoryTools`: malformed INPUT (missing required arg, bad uuid,
 * unknown enum value) throws via [error] and rides the existing tool-error-as-output path;
 * domain REJECTIONS the repository reports (cycle, illegal transition, unknown id) come back as
 * structured `{ok:false, error}` results so the model can react without aborting the turn.
 */
fun buildBoardTools(board: TaskBoardPort): List<Tool> = listOf(
    Tool(
        name = "task_create",
        description = """
            Create a work item on this conversation's shared task board.
            The board is visible to the user and to every agent working in this conversation; use it to
            break work into trackable items and to coordinate who does what.
            - `subject`: short imperative summary of the work item (required).
            - `description`: details, acceptance criteria, context (optional).
            - `activeForm`: present-continuous label shown while the item is in progress, e.g. "Writing the report" (optional).
            - `blockedBy`: ids of EXISTING items that must be completed first (optional). Inserting a dependency that would form a cycle is rejected.
            New items start in status `pending`. Returns the created item with its id.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("subject", buildJsonObject {
                        put("type", "string")
                        put("description", "Short imperative summary of the work item")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Details, acceptance criteria, context")
                    })
                    put("activeForm", buildJsonObject {
                        put("type", "string")
                        put("description", "Present-continuous label shown while in progress")
                    })
                    put("blockedBy", uuidArraySchema("Ids of existing items that must be completed first"))
                },
                required = listOf("subject")
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val draft = WorkItemDraft(
                subject = params.requireString("subject"),
                description = params.optionalString("description") ?: "",
                activeForm = params.optionalString("activeForm"),
                blockedBy = params.uuidList("blockedBy"),
            )
            board.create(draft).toToolOutput()
        }
    ),
    Tool(
        name = "task_get",
        description = """
            Read one work item from this conversation's task board by id, including its status,
            owner, and the ids of the items blocking it.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "The work item id (uuid)")
                    })
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val id = args.jsonObject.requireUuid("id")
            val payload = when (val snapshot = board.get(id)) {
                null -> rejectionJson("work item not found: $id")
                else -> acceptedJson(snapshot)
            }
            payload.asToolText()
        }
    ),
    Tool(
        name = "task_list",
        description = """
            List the work items on this conversation's task board.
            Optional `status` filter: any of `pending`, `in_progress`, `completed`, `deleted`.
            Without a filter, every item is returned.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("status", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                WIRE_STATUS.keys.forEach { add(it) }
                            })
                        })
                        put("description", "Only return items in these statuses; omit for all items")
                    })
                },
                required = null
            )
        },
        execute = { args ->
            val statuses = args.jsonObject.statusFilter("status")
            val items = board.list(statuses)
            buildJsonObject {
                put("ok", true)
                putJsonArray("items") { items.forEach { add(it.toJson()) } }
            }.asToolText()
        }
    ),
    Tool(
        name = "task_update",
        description = """
            Update a work item on this conversation's task board. Only the provided fields change:
            omit a field to leave it unchanged; send an empty string only to intentionally clear it.
            - `subject` / `description` / `activeForm`: edit the item's text fields.
            - `action`: change the item's status — one of
              `claim` (pending -> in_progress, take ownership), `complete` (in_progress -> completed),
              `release` (in_progress -> pending, give the item back), `reopen` (completed -> pending),
              `delete` (remove the item; its dependents unblock).
              Illegal transitions are rejected with the reason.
            - `blockedBy`: ADDITIONAL blocker ids to add; cycle-forming edges are rejected.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "The work item id (uuid)")
                    })
                    put("subject", buildJsonObject {
                        put("type", "string")
                        put("description", "New subject")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "New description")
                    })
                    put("activeForm", buildJsonObject {
                        put("type", "string")
                        put("description", "New present-continuous label")
                    })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            WIRE_ACTION.keys.forEach { add(it) }
                        })
                        put("description", "Status change to apply: claim, complete, release, reopen, or delete")
                    })
                    put("blockedBy", uuidArraySchema("Additional blocker ids to add"))
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val patch = WorkItemPatch(
                id = params.requireUuid("id"),
                subject = params.optionalString("subject"),
                description = params.optionalString("description"),
                activeForm = params.optionalString("activeForm"),
                action = params.optionalString("action")?.let { wire ->
                    WIRE_ACTION[wire] ?: error("unknown action: $wire, must be one of ${WIRE_ACTION.keys}")
                },
                addBlockedBy = params.uuidList("blockedBy"),
            )
            board.update(patch).toToolOutput()
        }
    ),
)

/** Wire names of [WorkItemStatus], stable lower_snake — the model- and persistence-facing vocabulary. */
private val WIRE_STATUS: Map<String, WorkItemStatus> = mapOf(
    "pending" to WorkItemStatus.Pending,
    "in_progress" to WorkItemStatus.InProgress,
    "completed" to WorkItemStatus.Completed,
    "deleted" to WorkItemStatus.Deleted,
)

private val STATUS_WIRE: Map<WorkItemStatus, String> =
    WIRE_STATUS.entries.associate { (wire, status) -> status to wire }

/** Wire names of [WorkItemAction] — explicit intents, never raw target statuses (decision #4). */
private val WIRE_ACTION: Map<String, WorkItemAction> = mapOf(
    "claim" to WorkItemAction.Claim,
    "complete" to WorkItemAction.Complete,
    "release" to WorkItemAction.Release,
    "reopen" to WorkItemAction.Reopen,
    "delete" to WorkItemAction.Delete,
)

private fun uuidArraySchema(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "string") })
    put("description", description)
}

private fun JsonObject.requireString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("$name is required")

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.requireUuid(name: String): Uuid = parseUuid(requireString(name), name)

private fun JsonObject.uuidList(name: String): List<Uuid> =
    this[name]?.jsonArray?.map { parseUuid(it.jsonPrimitive.content, name) } ?: emptyList()

private fun parseUuid(value: String, field: String): Uuid =
    runCatching { Uuid.parse(value) }.getOrElse { error("$field is not a valid uuid: $value") }

private fun JsonObject.statusFilter(name: String): Set<WorkItemStatus>? =
    this[name]?.jsonArray?.mapTo(mutableSetOf()) { element ->
        val wire = element.jsonPrimitive.content
        WIRE_STATUS[wire] ?: error("unknown status: $wire, must be one of ${WIRE_STATUS.keys}")
    }

private fun BoardItemSnapshot.toJson(): JsonObject = buildJsonObject {
    put("id", item.id.toString())
    put("subject", item.subject)
    put("description", item.description)
    item.activeForm?.let { put("activeForm", it) }
    put("status", STATUS_WIRE.getValue(item.status))
    item.ownerName?.let { put("owner", it) }
    putJsonArray("blockedBy") { blockedBy.forEach { add(it.toString()) } }
}

private fun acceptedJson(snapshot: BoardItemSnapshot): JsonObject = buildJsonObject {
    put("ok", true)
    put("item", snapshot.toJson())
}

private fun rejectionJson(reason: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", reason)
}

private fun BoardMutationResult.toToolOutput(): List<UIMessagePart> = when (this) {
    is BoardMutationResult.Accepted -> acceptedJson(snapshot)
    is BoardMutationResult.Rejected -> rejectionJson(reason)
}.asToolText()

private fun JsonObject.asToolText(): List<UIMessagePart> = listOf(UIMessagePart.Text(toString()))
