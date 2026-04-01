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
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
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
    } else if (data.rawPresetJson.isEmpty()) {
        root["enable_stop_string"] = JsonPrimitive(false)
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
    root["prompt_order"] = data.rawPresetJson["prompt_order"]?.jsonArrayOrNull()
        ?: buildCanonicalPromptOrder(template.resolvePromptOrder())

    val extensions = buildPresetExtensions(
        preset = data,
        scriptRegexes = scriptRegexes,
        hasInlinePromptRegexes = inlinePromptRegexesByIdentifier.isNotEmpty(),
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
        preset.template.prompts.forEach { prompt ->
            val rawPromptIndex = rawPrompts.indexOfFirstUnusedPrompt(prompt.identifier, consumedRawPromptIndexes)
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

        rawPrompts.forEachIndexed { index, rawPrompt ->
            if (index !in consumedRawPromptIndexes) {
                add(rawPrompt)
            }
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

private fun buildCanonicalPromptOrder(
    orderItems: List<me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem>,
): JsonArray {
    return buildJsonArray {
        add(buildJsonObject {
            put("character_id", 100001)
            putJsonArray("order") {
                orderItems.forEach { item ->
                    add(buildJsonObject {
                        put("identifier", item.identifier)
                        put("enabled", item.enabled)
                    })
                }
            }
        })
    }
}

private fun buildPresetExtensions(
    preset: SillyTavernPreset,
    scriptRegexes: List<AssistantRegex>,
    hasInlinePromptRegexes: Boolean,
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
    }
    if (hasInlinePromptRegexes) {
        updated[RIKKAHUB_INLINE_PROMPT_REGEXES_KEY] = JsonPrimitive(true)
    }

    return updated.takeIf { it.isNotEmpty() }?.let(::JsonObject)
}

private fun List<JsonObject>.indexOfFirstUnusedPrompt(
    identifier: String,
    consumedIndexes: Set<Int>,
): Int {
    if (identifier.isBlank()) return -1
    return indexOfFirst { index, prompt ->
        index !in consumedIndexes &&
            prompt["identifier"]?.jsonPrimitiveOrNull?.contentOrNull == identifier
    }
}

private inline fun <T> List<T>.indexOfFirst(predicate: (Int, T) -> Boolean): Int {
    forEachIndexed { index, item ->
        if (predicate(index, item)) return index
    }
    return -1
}
