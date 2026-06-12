package me.rerere.ai.runtime.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provenance marker for hook denials (#200 T10). A hook deny reuses the EXISTING
 * `ToolApprovalState.Denied` machine (no new exec path), so the only way for the UI to tell
 * "blocked by hook" apart from a user denial is an out-of-band marker on the tool part's
 * metadata — a JsonObject the Tool schema already carries, so old persisted messages decode
 * unchanged and simply read as "not hook-denied".
 */
private const val DENIED_BY_HOOK_KEY = "deniedByHook"

fun markDeniedByHook(metadata: JsonObject?): JsonObject =
    JsonObject(metadata.orEmpty() + (DENIED_BY_HOOK_KEY to JsonPrimitive(true)))

fun isDeniedByHook(metadata: JsonObject?): Boolean =
    runCatching { metadata?.get(DENIED_BY_HOOK_KEY)?.jsonPrimitive?.booleanOrNull }
        .getOrNull() == true
