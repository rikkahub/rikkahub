package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImportKind
import me.rerere.rikkahub.ui.pages.assistant.detail.parseAssistantImportFromJson
import me.rerere.rikkahub.ui.pages.assistant.detail.toSillyTavernPreset
import me.rerere.rikkahub.utils.JsonInstantPretty

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
    return buildJsonObject {
        put("name", template.sourceName.ifBlank { data.displayName })
        put("scenario_format", template.scenarioFormat)
        put("personality_format", template.personalityFormat)
        put("wi_format", template.wiFormat)
        put("new_chat_prompt", template.newChatPrompt)
        put("new_group_chat_prompt", template.newGroupChatPrompt)
        put("new_example_chat_prompt", template.newExampleChatPrompt)
        put("continue_nudge_prompt", template.continueNudgePrompt)
        put("group_nudge_prompt", template.groupNudgePrompt)
        put("impersonation_prompt", template.impersonationPrompt)
        put("assistant_prefill", template.assistantPrefill)
        put("assistant_impersonation", template.assistantImpersonation)
        put("continue_prefill", template.continuePrefill)
        put("continue_postfix", template.continuePostfix)
        put("send_if_empty", template.sendIfEmpty)
        data.sampling.temperature?.let { put("temperature", it) }
        data.sampling.topP?.let { put("top_p", it) }
        data.sampling.maxTokens?.let { put("openai_max_tokens", it) }
        data.sampling.frequencyPenalty?.let { put("frequency_penalty", it) }
        data.sampling.presencePenalty?.let { put("presence_penalty", it) }
        data.sampling.minP?.let { put("min_p", it) }
        data.sampling.topK?.let { put("top_k", it) }
        data.sampling.topA?.let { put("top_a", it) }
        data.sampling.repetitionPenalty?.let { put("repetition_penalty", it) }
        data.sampling.seed?.let { put("seed", it) }
        put("enable_stop_string", data.sampling.stopSequences.isNotEmpty())
        if (data.sampling.stopSequences.isNotEmpty()) {
            put("stop_string", data.sampling.stopSequences.first())
            put("stop_strings", buildJsonArray {
                data.sampling.stopSequences.forEach { add(JsonPrimitive(it)) }
            })
        }
        if (data.sampling.openAIReasoningEffort.isNotBlank()) {
            put("reasoning_effort", data.sampling.openAIReasoningEffort)
        }
        if (data.sampling.openAIVerbosity.isNotBlank()) {
            put("verbosity", data.sampling.openAIVerbosity)
        }
        template.namesBehavior?.let { put("names_behavior", it) }
        put("use_sysprompt", template.useSystemPrompt)
        put("squash_system_messages", template.squashSystemMessages)
        putJsonArray("prompts") {
            template.prompts.forEach { prompt ->
                add(buildJsonObject {
                    put("identifier", prompt.identifier)
                    put("name", prompt.name)
                    put("role", prompt.role.name.lowercase())
                    put("content", prompt.content)
                    put("system_prompt", prompt.systemPrompt)
                    put("marker", prompt.marker)
                    put("enabled", prompt.enabled)
                    put(
                        "injection_position",
                        if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) 1 else 0
                    )
                    put("injection_depth", prompt.injectionDepth)
                    put("injection_order", prompt.injectionOrder)
                    put("injection_trigger", buildJsonArray {
                        prompt.injectionTriggers.forEach { add(JsonPrimitive(it)) }
                    })
                    put("forbid_overrides", prompt.forbidOverrides)
                })
            }
        }
        putJsonArray("prompt_order") {
            add(buildJsonObject {
                put("character_id", 100001)
                putJsonArray("order") {
                    template.resolvePromptOrder().forEach { item ->
                        add(buildJsonObject {
                            put("identifier", item.identifier)
                            put("enabled", item.enabled)
                        })
                    }
                }
            })
        }
        if (data.regexes.isNotEmpty()) {
            putJsonObject("extensions") {
                putJsonArray("regex_scripts") {
                    data.regexes.forEach { regex ->
                        add(buildRegexScript(regex))
                    }
                }
            }
        }
    }
}
