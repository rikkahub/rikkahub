package me.rerere.rikkahub.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImportKind
import me.rerere.rikkahub.ui.pages.assistant.detail.parseAssistantImportFromJson
import me.rerere.rikkahub.ui.pages.assistant.detail.toSillyTavernPreset
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.base64Encode
import java.io.ByteArrayOutputStream

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
}

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
                entry.probability?.let { probability ->
                    put("probability", probability)
                    put("useProbability", true)
                }
                entry.stMetadata.forEach { (key, rawValue) ->
                    if (key in RESERVED_LOREBOOK_EXTENSION_KEYS) return@forEach
                    put(key, rawJsonValue(rawValue))
                }
                put("display_index", index)
            }
        }
    }
}

object SillyTavernCharacterCardPngSerializer : ExportSerializer<SillyTavernCharacterCardExportData> {
    override val type: String = "st_character_card_png"

    override fun export(data: SillyTavernCharacterCardExportData): ExportData {
        return ExportData(type = type, data = JsonPrimitive(getExportFileName(data)))
    }

    override fun getMimeType(data: SillyTavernCharacterCardExportData): String = "image/png"

    override fun getExportFileName(data: SillyTavernCharacterCardExportData): String {
        val name = data.assistant.stCharacterData?.name
            ?.takeIf { it.isNotBlank() }
            ?: data.assistant.name.ifBlank { "character-card" }
        return "${sanitizeExportName(name, "character-card")}.png"
    }

    override fun exportToBytes(context: Context, data: SillyTavernCharacterCardExportData): ByteArray {
        val json = SillyTavernCharacterCardSerializer.exportToJson(data).base64Encode()
        val basePng = loadBaseCardPngBytes(context, data.assistant)
        return ImageUtils.embedTavernCharacterMetaIntoPngBytes(basePng, json)
    }

    override fun import(context: Context, uri: Uri): Result<SillyTavernCharacterCardExportData> {
        return Result.failure(UnsupportedOperationException("Character card PNG serializer does not support import"))
    }
}

private val RESERVED_LOREBOOK_EXTENSION_KEYS = setOf(
    "position",
    "depth",
    "selectiveLogic",
    "scan_depth",
    "match_whole_words",
    "case_sensitive",
    "role",
    "match_persona_description",
    "match_character_description",
    "match_character_personality",
    "match_character_depth_prompt",
    "match_scenario",
    "match_creator_notes",
    "probability",
    "useProbability",
    "display_index",
)

private fun buildRegexScript(regex: AssistantRegex): JsonObject {
    return buildJsonObject {
        put("scriptName", regex.name)
        put("findRegex", regex.findRegex)
        put("replaceString", regex.replaceString)
        put("placement", buildJsonArray {
            regex.exportPlacements().forEach { add(JsonPrimitive(it)) }
        })
        put("disabled", !regex.enabled)
        put("markdownOnly", regex.visualOnly && !regex.promptOnly)
        put("promptOnly", regex.promptOnly)
        regex.minDepth?.let { put("minDepth", it) }
        regex.maxDepth?.let { put("maxDepth", it) }
        put("trimStrings", buildJsonArray {
            regex.trimStrings.forEach { add(JsonPrimitive(it)) }
        })
        put("runOnEdit", regex.runOnEdit)
        put("substituteRegex", regex.substituteRegex)
    }
}

private fun InjectionPosition.toStCharacterBookPosition(): Int {
    return when (this) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
        InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
        InjectionPosition.AUTHOR_NOTE_TOP -> 2
        InjectionPosition.AUTHOR_NOTE_BOTTOM -> 3
        InjectionPosition.AT_DEPTH -> 4
        InjectionPosition.EXAMPLE_MESSAGES_TOP -> 5
        InjectionPosition.EXAMPLE_MESSAGES_BOTTOM -> 6
        InjectionPosition.TOP_OF_CHAT,
        InjectionPosition.BOTTOM_OF_CHAT,
        -> 1
    }
}

private fun MessageRole.toStPromptRole(): Int {
    return when (this) {
        MessageRole.USER -> 1
        MessageRole.ASSISTANT -> 2
        else -> 0
    }
}

private fun sanitizeExportName(value: String, fallback: String): String {
    return value
        .trim()
        .ifBlank { fallback }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
}

private fun rawJsonValue(rawValue: String): JsonElement {
    val trimmed = rawValue.trim()
    if (trimmed.isEmpty()) return JsonPrimitive(rawValue)
    trimmed.toBooleanStrictOrNull()?.let { return JsonPrimitive(it) }
    trimmed.toIntOrNull()?.let { return JsonPrimitive(it) }
    trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
    trimmed.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    return runCatching { Json.parseToJsonElement(trimmed) }
        .getOrElse { JsonPrimitive(rawValue) }
}

private fun AssistantRegex.exportPlacements(): List<Int> {
    if (stPlacements.isNotEmpty()) return stPlacements.sorted()
    return buildList {
        if (AssistantAffectScope.USER in affectingScope) add(1)
        if (AssistantAffectScope.ASSISTANT in affectingScope) add(2)
    }.ifEmpty { listOf(2) }
}

private fun Assistant.firstAssistantPresetMessage(): String {
    return presetMessages
        .firstOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("") { it.text }
        .orEmpty()
}

private fun loadBaseCardPngBytes(context: Context, assistant: Assistant): ByteArray {
    val avatarUri = (assistant.avatar as? Avatar.Image)?.url?.takeIf { it.isNotBlank() }?.toUri()
    val bitmap = avatarUri?.let { uri ->
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    } ?: createFallbackCardBitmap(assistant)

    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Failed to encode PNG" }
        bitmap.recycle()
        output.toByteArray()
    }
}

private fun createFallbackCardBitmap(assistant: Assistant): Bitmap {
    val width = 512
    val height = 768
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Color.parseColor("#1B2838"),
            Color.parseColor("#314E68"),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9A441")
        alpha = 110
    }
    canvas.drawCircle(width * 0.78f, height * 0.22f, width * 0.22f, accentPaint)

    val label = when (val avatar = assistant.avatar) {
        is Avatar.Emoji -> avatar.content.takeIf { it.isNotBlank() }
        else -> null
    } ?: assistant.stCharacterData?.name
        ?.takeIf { it.isNotBlank() }
        ?: assistant.name
            .takeIf { it.isNotBlank() }
        ?: "ST"

    val text = label.trim().ifEmpty { "ST" }.take(20)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = if (text.length <= 2) 180f else 72f
        isFakeBoldText = true
    }
    val textBounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, textBounds)
    val baseline = height / 2f - textBounds.exactCenterY()
    canvas.drawText(text, width / 2f, baseline, textPaint)

    return bitmap
}
