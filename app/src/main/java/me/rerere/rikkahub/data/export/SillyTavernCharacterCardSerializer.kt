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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.stExtension
import me.rerere.rikkahub.utils.JsonInstantPretty

data class SillyTavernCharacterCardExportData(
    val assistant: Assistant,
    val lorebooks: List<Lorebook>,
)

object SillyTavernCharacterCardSerializer : ExportSerializer<SillyTavernCharacterCardExportData> {
    override val type: String = "st_character_card"

    override fun export(data: SillyTavernCharacterCardExportData): ExportData {
        return ExportData(type = type, data = buildCharacterCardJson(data))
    }

    override fun exportToJson(data: SillyTavernCharacterCardExportData, json: Json): String {
        return JsonInstantPretty.encodeToString(JsonObject.serializer(), buildCharacterCardJson(data))
    }

    override fun getExportFileName(data: SillyTavernCharacterCardExportData): String {
        val name = data.assistant.stCharacterData?.name
            ?.takeIf { it.isNotBlank() }
            ?: data.assistant.name.ifBlank { "character-card" }
        return "${sanitizeExportName(name, "character-card")}.json"
    }

    override fun import(context: Context, uri: Uri): Result<SillyTavernCharacterCardExportData> {
        return Result.failure(UnsupportedOperationException("Character card export serializer does not support import"))
    }
}

private fun buildCharacterCardJson(data: SillyTavernCharacterCardExportData): JsonObject {
    val assistant = data.assistant
    val character = assistant.stCharacterData
    val cardName = character?.name?.takeIf { it.isNotBlank() } ?: assistant.name.ifBlank { "Assistant" }
    val systemPrompt = character?.systemPromptOverride
        ?.takeIf { it.isNotBlank() }
        ?: assistant.systemPrompt
    val characterBook = buildCharacterBook(
        assistantName = cardName,
        lorebooks = data.lorebooks,
    )

    return buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        putJsonObject("data") {
            put("name", cardName)
            put("description", character?.description.orEmpty())
            put("personality", character?.personality.orEmpty())
            put("scenario", character?.scenario.orEmpty())
            put("first_mes", character?.firstMessage?.ifBlank { null } ?: assistant.firstAssistantPresetMessage())
            put("mes_example", character?.exampleMessagesRaw.orEmpty())
            put("creator_notes", character?.creatorNotes.orEmpty())
            put("system_prompt", systemPrompt)
            put("post_history_instructions", character?.postHistoryInstructions.orEmpty())
            put("alternate_greetings", buildJsonArray {
                character?.alternateGreetings.orEmpty().forEach { add(JsonPrimitive(it)) }
            })
            characterBook?.let { put("character_book", it) }
            putJsonArray("tags") {}
            put("creator", "Rikkahub")
            put("character_version", character?.version?.ifBlank { "1.0" } ?: "1.0")
            putJsonObject("extensions") {
                character?.depthPrompt?.let { depthPrompt ->
                    putJsonObject("depth_prompt") {
                        put("prompt", depthPrompt.prompt)
                        put("depth", depthPrompt.depth)
                        put("role", depthPrompt.role.name.lowercase())
                    }
                }
                if (assistant.regexes.isNotEmpty()) {
                    putJsonArray("regex_scripts") {
                        assistant.regexes.forEach { regex ->
                            add(buildRegexScript(regex))
                        }
                    }
                }
            }
        }
    }
}

private fun buildCharacterBook(
    assistantName: String,
    lorebooks: List<Lorebook>,
): JsonObject? {
    if (lorebooks.isEmpty()) return null
    val multiBook = lorebooks.size > 1
    val mergedName = if (multiBook) {
        "$assistantName Lorebooks"
    } else {
        lorebooks.first().name.ifBlank { "$assistantName Lorebook" }
    }
    val mergedDescription = if (multiBook) {
        lorebooks.mapNotNull { it.name.takeIf(String::isNotBlank) }.distinct().joinToString(" / ")
    } else {
        lorebooks.first().description
    }
    return buildJsonObject {
        put("name", mergedName)
        put("description", mergedDescription)
        put("recursive_scanning", lorebooks.any { it.recursiveScanning })
        lorebooks.mapNotNull { it.tokenBudget }.maxOrNull()?.let { put("token_budget", it) }
        putJsonObject("extensions") {}
        putJsonArray("entries") {
            lorebooks.forEach { lorebook ->
                lorebook.entries.forEachIndexed { index, entry ->
                    add(buildCharacterBookEntry(lorebook, entry, index, multiBook))
                }
            }
        }
    }
}

private fun buildCharacterBookEntry(
    lorebook: Lorebook,
    entry: PromptInjection.RegexInjection,
    index: Int,
    multiBook: Boolean,
): JsonObject {
    val comment = buildString {
        if (multiBook && lorebook.name.isNotBlank()) {
            append('[')
            append(lorebook.name)
            append("] ")
        }
        append(entry.name)
    }.trim()
    val extension = entry.stExtension()
    val probability = entry.exportProbabilityValue()
    val useProbability = extension.useProbability ?: (probability != null)

    return buildJsonObject {
        put("keys", buildJsonArray {
            entry.keywords.forEach { add(JsonPrimitive(it)) }
        })
        put("content", entry.content)
        put("enabled", entry.enabled)
        put("insertion_order", entry.priority)
        put("position", if (entry.position == InjectionPosition.BEFORE_SYSTEM_PROMPT) "before_char" else "after_char")
        put("use_regex", entry.useRegex)
        put("constant", entry.constantActive)
        put("selective", entry.selective)
        if (entry.caseSensitive) {
            put("case_sensitive", true)
        }
        if (comment.isNotBlank()) {
            put("comment", comment)
            put("name", entry.name.ifBlank { comment })
        }
        if (entry.secondaryKeywords.isNotEmpty()) {
            put("secondary_keys", buildJsonArray {
                entry.secondaryKeywords.forEach { add(JsonPrimitive(it)) }
            })
        }
        putJsonObject("extensions") {
            put("position", entry.position.toStCharacterBookPosition())
            put("depth", entry.injectDepth)
            put("selectiveLogic", entry.selectiveLogic)
            put("scan_depth", entry.scanDepth)
            put("match_whole_words", entry.matchWholeWords)
            put("case_sensitive", entry.caseSensitive)
            put("role", entry.role.toStPromptRole())
            put("match_persona_description", entry.matchPersonaDescription)
            put("match_character_description", entry.matchCharacterDescription)
            put("match_character_personality", entry.matchCharacterPersonality)
            put("match_character_depth_prompt", entry.matchCharacterDepthPrompt)
            put("match_scenario", entry.matchScenario)
            put("match_creator_notes", entry.matchCreatorNotes)
            probability?.let {
                put("probability", it)
            }
            if (extension.useProbability != null || probability != null) {
                put("useProbability", useProbability)
            }
            extension.toMetadataMap().forEach { (key, rawValue) ->
                if (key in RESERVED_LOREBOOK_EXTENSION_KEYS) return@forEach
                put(key, rawJsonValue(rawValue))
            }
            put("display_index", index)
        }
    }
}
