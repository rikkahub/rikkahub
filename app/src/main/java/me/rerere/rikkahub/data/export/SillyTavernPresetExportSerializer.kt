package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImportKind
import me.rerere.rikkahub.ui.pages.assistant.detail.RIKKAHUB_INLINE_PROMPT_REGEXES_KEY
import me.rerere.rikkahub.ui.pages.assistant.detail.jsonArrayOrNull
import me.rerere.rikkahub.ui.pages.assistant.detail.jsonObjectOrNull
import me.rerere.rikkahub.ui.pages.assistant.detail.parseAssistantImportFromJson
import me.rerere.rikkahub.ui.pages.assistant.detail.toSillyTavernPreset
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

object SillyTavernPresetExportSerializer : ExportSerializer<SillyTavernPreset> {
    override val type: String = "st_preset"

    override fun export(data: SillyTavernPreset): ExportData {
        return ExportData(type = type, data = buildPresetJson(data))
    }

    override fun exportToJson(data: SillyTavernPreset, json: Json): String {
        return JsonInstantPretty.encodeToString(JsonObject.serializer(), buildPresetJson(data))
    }

    override fun getExportFileName(data: SillyTavernPreset): String {
        return "${sanitizeExportName(data.displayName, "st-preset")}.json"
    }

    override fun import(context: Context, uri: Uri): Result<SillyTavernPreset> {
        return runCatching {
            val payload = parseAssistantImportFromJson(
                jsonString = readUri(context, uri),
                sourceName = getUriFileName(context, uri)?.substringBeforeLast('.').orEmpty(),
            )
            require(payload.kind == AssistantImportKind.PRESET) { "Unsupported format" }
            payload.toSillyTavernPreset()
        }
    }
}

private fun buildPresetJson(data: SillyTavernPreset): JsonObject {
    val template = data.template
    val resolvedPromptOrder = template.resolvePromptOrder()
    val inlinePromptRegexesByIdentifier = data.regexes
        .filter { it.shouldExportAsInlinePrompt() }
        .groupBy { it.sourceRef }
    val scriptRegexes = data.regexes
        .filterNot { it.shouldExportAsInlinePrompt() }
    val root = LinkedHashMap<String, JsonElement>(data.rawPresetJson)

    root["name"] = JsonPrimitive(template.sourceName.ifBlank { data.displayName })
    root["scenario_format"] = JsonPrimitive(template.scenarioFormat)
    root["personality_format"] = JsonPrimitive(template.personalityFormat)
    root["wi_format"] = JsonPrimitive(template.wiFormat)
    root["new_chat_prompt"] = JsonPrimitive(template.newChatPrompt)
    root["new_group_chat_prompt"] = JsonPrimitive(template.newGroupChatPrompt)
    root["new_example_chat_prompt"] = JsonPrimitive(template.newExampleChatPrompt)
    root["continue_nudge_prompt"] = JsonPrimitive(template.continueNudgePrompt)
    root["group_nudge_prompt"] = JsonPrimitive(template.groupNudgePrompt)
    root["impersonation_prompt"] = JsonPrimitive(template.impersonationPrompt)
    root["assistant_prefill"] = JsonPrimitive(template.assistantPrefill)
    root["assistant_impersonation"] = JsonPrimitive(template.assistantImpersonation)
    root["continue_prefill"] = JsonPrimitive(template.continuePrefill)
    root["continue_postfix"] = JsonPrimitive(template.continuePostfix)
    root["send_if_empty"] = JsonPrimitive(template.sendIfEmpty)
    data.sampling.temperature?.let { root["temperature"] = JsonPrimitive(it) }
    data.sampling.topP?.let { root["top_p"] = JsonPrimitive(it) }
    data.sampling.maxTokens?.let { root["openai_max_tokens"] = JsonPrimitive(it) }
    data.sampling.frequencyPenalty?.let { root["frequency_penalty"] = JsonPrimitive(it) }
    data.sampling.presencePenalty?.let { root["presence_penalty"] = JsonPrimitive(it) }
    data.sampling.minP?.let { root["min_p"] = JsonPrimitive(it) }
    data.sampling.topK?.let { root["top_k"] = JsonPrimitive(it) }
    data.sampling.topA?.let { root["top_a"] = JsonPrimitive(it) }
    data.sampling.repetitionPenalty?.let { root["repetition_penalty"] = JsonPrimitive(it) }
    data.sampling.seed?.let { root["seed"] = JsonPrimitive(it) }
    if (data.sampling.stopSequences.isNotEmpty()) {
        root["enable_stop_string"] = JsonPrimitive(true)
        root["stop_string"] = JsonPrimitive(data.sampling.stopSequences.first())
        root["stop_strings"] = buildJsonArray {
            data.sampling.stopSequences.forEach { add(JsonPrimitive(it)) }
        }
    } else {
        root["enable_stop_string"] = JsonPrimitive(false)
        root.remove("stop_string")
        root.remove("stop_strings")
    }
    if (data.sampling.openAIReasoningEffort.isNotBlank()) {
        root["reasoning_effort"] = JsonPrimitive(data.sampling.openAIReasoningEffort)
    }
    if (data.sampling.openAIVerbosity.isNotBlank()) {
        root["verbosity"] = JsonPrimitive(data.sampling.openAIVerbosity)
    }
    template.namesBehavior?.let { root["names_behavior"] = JsonPrimitive(it) }
    root["use_sysprompt"] = JsonPrimitive(template.useSystemPrompt)
    root["squash_system_messages"] = JsonPrimitive(template.squashSystemMessages)
    root["prompts"] = buildPresetPrompts(
        preset = data,
        inlinePromptRegexesByIdentifier = inlinePromptRegexesByIdentifier,
    )
    root["prompt_order"] = buildPresetPromptOrder(
        rawPromptOrder = data.rawPresetJson["prompt_order"]?.jsonArrayOrNull(),
        orderItems = resolvedPromptOrder,
    )

    val extensions = buildPresetExtensions(
        preset = data,
        scriptRegexes = scriptRegexes,
        hasInlinePromptRegexes = inlinePromptRegexesByIdentifier.isNotEmpty(),
        hasStopSequences = data.sampling.stopSequences.isNotEmpty(),
    )
    if (extensions != null) {
        root["extensions"] = extensions
    } else {
        root.remove("extensions")
    }

    return JsonObject(root)
}

private fun buildPresetPrompts(
    preset: SillyTavernPreset,
    inlinePromptRegexesByIdentifier: Map<String, List<AssistantRegex>>,
): JsonArray {
    val rawPrompts = preset.rawPresetJson["prompts"]
        ?.jsonArrayOrNull()
        ?.mapNotNull { it.jsonObjectOrNull() }
        .orEmpty()
    val consumedRawPromptIndexes = mutableSetOf<Int>()

    return buildJsonArray {
        preset.template.prompts.forEachIndexed { promptIndex, prompt ->
            val rawPromptIndex = rawPrompts.indexOfFirstUnusedPrompt(
                prompt = prompt,
                promptIndex = promptIndex,
                consumedIndexes = consumedRawPromptIndexes,
            )
            val rawPrompt = rawPrompts.getOrNull(rawPromptIndex)
            if (rawPromptIndex >= 0) {
                consumedRawPromptIndexes += rawPromptIndex
            }
            add(
                buildPresetPrompt(
                    prompt = prompt,
                    rawPrompt = rawPrompt,
                    inlineRegexes = inlinePromptRegexesByIdentifier[prompt.identifier].orEmpty(),
                )
            )
        }
    }
}

private fun buildPresetPrompt(
    prompt: SillyTavernPromptItem,
    rawPrompt: JsonObject?,
    inlineRegexes: List<AssistantRegex>,
): JsonObject {
    val updated = LinkedHashMap<String, JsonElement>(rawPrompt ?: emptyMap())
    updated["identifier"] = JsonPrimitive(prompt.identifier)
    updated["name"] = JsonPrimitive(prompt.name)
    updated["role"] = JsonPrimitive(prompt.role.name.lowercase())
    updated["content"] = JsonPrimitive(
        appendInlinePromptRegexes(
            content = prompt.content,
            regexes = inlineRegexes,
        )
    )
    updated["system_prompt"] = JsonPrimitive(prompt.systemPrompt)
    updated["marker"] = JsonPrimitive(prompt.marker)
    updated["enabled"] = JsonPrimitive(prompt.enabled)
    updated["injection_position"] = JsonPrimitive(
        if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) 1 else 0
    )
    updated["injection_depth"] = JsonPrimitive(prompt.injectionDepth)
    updated["injection_order"] = JsonPrimitive(prompt.injectionOrder)
    updated["injection_trigger"] = buildJsonArray {
        prompt.injectionTriggers.forEach { add(JsonPrimitive(it)) }
    }
    updated["forbid_overrides"] = JsonPrimitive(prompt.forbidOverrides)
    return JsonObject(updated)
}

private fun buildPresetPromptOrder(
    rawPromptOrder: JsonArray?,
    orderItems: List<SillyTavernPromptOrderItem>,
): JsonArray {
    val rawOrderLists = rawPromptOrder
        ?.mapNotNull { it.jsonObjectOrNull() }
        .orEmpty()
    if (rawOrderLists.isEmpty()) {
        return buildCanonicalPromptOrder(orderItems)
    }

    return JsonArray(
        rawOrderLists.map { rawOrderList ->
            val updated = LinkedHashMap<String, JsonElement>(rawOrderList)
            updated["order"] = buildPromptOrderEntries(
                rawOrderItems = rawOrderList["order"]?.jsonArrayOrNull()
                    ?.mapNotNull { it.jsonObjectOrNull() }
                    .orEmpty(),
                orderItems = orderItems,
            )
            JsonObject(updated)
        }
    )
}

private fun buildCanonicalPromptOrder(
    orderItems: List<SillyTavernPromptOrderItem>,
): JsonArray {
    return buildJsonArray {
        add(buildJsonObject {
            put("character_id", 100001)
            put("order", buildPromptOrderEntries(orderItems = orderItems))
        })
    }
}

private fun buildPromptOrderEntries(
    rawOrderItems: List<JsonObject> = emptyList(),
    orderItems: List<SillyTavernPromptOrderItem>,
): JsonArray {
    val consumedRawOrderIndexes = mutableSetOf<Int>()

    return buildJsonArray {
        orderItems.forEachIndexed { orderIndex, item ->
            val rawOrderIndex = rawOrderItems.indexOfFirstUnusedPromptOrder(
                identifier = item.identifier,
                orderIndex = orderIndex,
                consumedIndexes = consumedRawOrderIndexes,
            )
            val rawOrder = rawOrderItems.getOrNull(rawOrderIndex)
            if (rawOrderIndex >= 0) {
                consumedRawOrderIndexes += rawOrderIndex
            }

            add(buildJsonObject {
                (rawOrder ?: emptyMap()).forEach { (key, value) -> put(key, value) }
                put("identifier", item.identifier)
                put("enabled", item.enabled)
            })
        }
    }
}

private fun buildPresetExtensions(
    preset: SillyTavernPreset,
    scriptRegexes: List<AssistantRegex>,
    hasInlinePromptRegexes: Boolean,
    hasStopSequences: Boolean,
): JsonObject? {
    val updated = LinkedHashMap<String, JsonElement>(
        preset.rawPresetJson["extensions"]?.jsonObjectOrNull() ?: emptyMap()
    )

    if (scriptRegexes.isNotEmpty()) {
        updated["regex_scripts"] = buildJsonArray {
            scriptRegexes.forEach { regex ->
                add(buildRegexScript(regex))
            }
        }
    } else {
        updated.remove("regex_scripts")
    }
    if (hasInlinePromptRegexes) {
        updated[RIKKAHUB_INLINE_PROMPT_REGEXES_KEY] = JsonPrimitive(true)
    } else {
        updated.remove(RIKKAHUB_INLINE_PROMPT_REGEXES_KEY)
    }
    if (!hasStopSequences) {
        updated.clearChatSquashStopStrings()
    }

    return updated.takeIf { it.isNotEmpty() }?.let(::JsonObject)
}

private fun List<JsonObject>.indexOfFirstUnusedPrompt(
    prompt: SillyTavernPromptItem,
    promptIndex: Int,
    consumedIndexes: Set<Int>,
): Int {
    if (prompt.identifier.isNotBlank()) {
        return indexOfFirst { index, rawPrompt ->
            index !in consumedIndexes &&
                rawPrompt["identifier"]?.jsonPrimitiveOrNull?.contentOrNull == prompt.identifier
        }
    }

    return indexOfFirst { index, rawPrompt ->
        index !in consumedIndexes &&
            rawPrompt["identifier"]?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank() &&
            index >= promptIndex
    }
}

private fun List<JsonObject>.indexOfFirstUnusedPromptOrder(
    identifier: String,
    orderIndex: Int,
    consumedIndexes: Set<Int>,
): Int {
    if (identifier.isNotBlank()) {
        return indexOfFirst { index, orderItem ->
            index !in consumedIndexes &&
                orderItem["identifier"]?.jsonPrimitiveOrNull?.contentOrNull == identifier
        }
    }

    return indexOfFirst { index, orderItem ->
        index !in consumedIndexes &&
            orderItem["identifier"]?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank() &&
            index >= orderIndex
    }
}

private fun MutableMap<String, JsonElement>.clearChatSquashStopStrings() {
    val sPreset = this["SPreset"]?.jsonObjectOrNull() ?: return
    val chatSquash = sPreset["ChatSquash"]?.jsonObjectOrNull() ?: return

    val updatedChatSquash = LinkedHashMap<String, JsonElement>(chatSquash).apply {
        this["enable_stop_string"] = JsonPrimitive(false)
        remove("stop_string")
    }
    val updatedSPreset = LinkedHashMap<String, JsonElement>(sPreset).apply {
        this["ChatSquash"] = JsonObject(updatedChatSquash)
    }
    this["SPreset"] = JsonObject(updatedSPreset)
}

private inline fun <T> List<T>.indexOfFirst(predicate: (Int, T) -> Boolean): Int {
    forEachIndexed { index, item ->
        if (predicate(index, item)) return index
    }
    return -1
}
