package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.StDepthPrompt
import me.rerere.rikkahub.data.model.StLorebookEntryExtension
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

internal fun parseCharacterCardImport(
    json: JsonObject,
    sourceName: String,
    avatarUri: String?,
): AssistantImportPayload {
    val data = json["data"]?.jsonObject ?: error("Missing card data")
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: error("Missing card name")
    val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val characterData = parseCharacterData(data, sourceName)
    val lorebooks = data["character_book"]?.jsonObjectOrNull()
        ?.let { listOf(parseCharacterBook(it, cardName = name)) }
        ?: emptyList()
    val regexes = parseRegexScripts(
        element = data["extensions"]?.jsonObjectOrNull()?.get("regex_scripts"),
        sourceName = name,
    )

    return AssistantImportPayload(
        kind = AssistantImportKind.CHARACTER_CARD,
        sourceName = sourceName,
        assistant = Assistant(
            name = name,
            avatar = avatarUri?.let { Avatar.Image(it) } ?: Avatar.Dummy,
            presetMessages = firstMessage.takeIf { it.isNotBlank() }?.let { listOf(UIMessage.assistant(it)) } ?: emptyList(),
            stCharacterData = characterData,
            lorebookIds = lorebooks.map { it.id }.toSet(),
        ),
        presetTemplate = defaultSillyTavernPromptTemplate(),
        lorebooks = lorebooks,
        regexes = regexes,
    )
}

private fun parseCharacterData(
    data: JsonObject,
    sourceName: String,
): SillyTavernCharacterData {
    val extensions = data["extensions"]?.jsonObjectOrNull()
    val depthPrompt = extensions?.get("depth_prompt")?.jsonObjectOrNull()?.let { prompt ->
        StDepthPrompt(
            prompt = prompt["prompt"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            depth = prompt["depth"]?.jsonPrimitiveOrNull?.intOrNull ?: 4,
            role = prompt["role"]?.jsonPrimitiveOrNull?.contentOrNull.toMessageRole(),
        )
    }

    return SillyTavernCharacterData(
        sourceName = sourceName,
        name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        version = data["character_version"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        systemPromptOverride = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        exampleMessagesRaw = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        alternateGreetings = data["alternate_greetings"]?.jsonArrayOrNull()
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            ?: emptyList(),
        creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        depthPrompt = depthPrompt,
    )
}

private fun parseCharacterBook(book: JsonObject, cardName: String): Lorebook {
    val bookScanDepth = book["scan_depth"]?.jsonPrimitiveOrNull?.intOrNull
    val tokenBudget = book["token_budget"]?.jsonPrimitiveOrNull?.intOrNull
    val recursiveScanning = book["recursive_scanning"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
    val entries = book["entries"]?.jsonArrayOrNull().orEmpty().mapIndexed { index, element ->
        parseCharacterBookEntry(
            entry = element.jsonObject,
            bookScanDepth = bookScanDepth,
            entryIndex = index,
        )
    }
    return Lorebook(
        id = Uuid.random(),
        name = book["name"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { null } ?: "$cardName Lorebook",
        description = book["description"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        enabled = true,
        recursiveScanning = recursiveScanning,
        tokenBudget = tokenBudget,
        entries = entries,
    )
}

private fun parseCharacterBookEntry(
    entry: JsonObject,
    bookScanDepth: Int?,
    entryIndex: Int,
): PromptInjection.RegexInjection {
    val extensions = entry["extensions"]?.jsonObjectOrNull()
    val keywords = entry["keys"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull } ?: emptyList()
    val secondaryKeywords = entry["secondary_keys"]?.jsonArrayOrNull()
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        ?: emptyList()
    val useRegex = entry["use_regex"]?.jsonPrimitiveOrNull?.booleanOrNull
        ?: (keywords.any(::isSlashDelimitedRegex) || secondaryKeywords.any(::isSlashDelimitedRegex))
    val useProbability = extensions?.get("useProbability")?.jsonPrimitiveOrNull?.booleanOrNull ?: true
    val probability = extensions?.get("probability")?.jsonPrimitiveOrNull?.intOrNull

    return PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: entry["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: keywords.firstOrNull().orEmpty(),
        enabled = entry["enabled"]?.jsonPrimitiveOrNull?.booleanOrNull ?: true,
        priority = entry["insertion_order"]?.jsonPrimitiveOrNull?.intOrNull ?: 100,
        position = mapCharacterBookPosition(
            position = entry["position"]?.jsonPrimitiveOrNull?.contentOrNull,
            extensionPosition = extensions?.get("position")?.jsonPrimitiveOrNull?.intOrNull,
        ),
        content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        injectDepth = extensions?.get("depth")?.jsonPrimitiveOrNull?.intOrNull ?: 4,
        role = mapExtensionPromptRole(extensions?.get("role")?.jsonPrimitiveOrNull?.intOrNull),
        keywords = keywords,
        secondaryKeywords = secondaryKeywords,
        selective = entry["selective"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
        selectiveLogic = extensions?.get("selectiveLogic")?.jsonPrimitiveOrNull?.intOrNull ?: 0,
        useRegex = useRegex,
        caseSensitive = entry["case_sensitive"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: extensions?.get("case_sensitive")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false,
        matchWholeWords = extensions?.get("match_whole_words")?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
        probability = probability?.takeIf { useProbability },
        scanDepth = extensions?.get("scan_depth")?.jsonPrimitiveOrNull?.intOrNull ?: bookScanDepth ?: 4,
        constantActive = entry["constant"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
        matchCharacterDescription = extensions?.get("match_character_description")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false,
        matchCharacterPersonality = extensions?.get("match_character_personality")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false,
        matchPersonaDescription = extensions?.get("match_persona_description")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false,
        matchScenario = extensions?.get("match_scenario")?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
        matchCreatorNotes = extensions?.get("match_creator_notes")?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
        matchCharacterDepthPrompt = extensions?.get("match_character_depth_prompt")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false,
        stMetadata = buildCharacterBookLorebookMetadata(extensions, useProbability, probability, entryIndex),
    )
}

private fun mapCharacterBookPosition(position: String?, extensionPosition: Int?): InjectionPosition {
    return when (extensionPosition ?: when (position) {
        "before_char" -> 0
        "after_char" -> 1
        else -> 1
    }) {
        0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
        2 -> InjectionPosition.AUTHOR_NOTE_TOP
        3 -> InjectionPosition.AUTHOR_NOTE_BOTTOM
        4 -> InjectionPosition.AT_DEPTH
        5 -> InjectionPosition.EXAMPLE_MESSAGES_TOP
        6 -> InjectionPosition.EXAMPLE_MESSAGES_BOTTOM
        7 -> InjectionPosition.OUTLET
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }
}

private fun mapExtensionPromptRole(role: Int?): MessageRole {
    return when (role) {
        1 -> MessageRole.USER
        2 -> MessageRole.ASSISTANT
        else -> MessageRole.SYSTEM
    }
}

private fun buildCharacterBookLorebookMetadata(
    extensions: JsonObject?,
    useProbability: Boolean,
    probability: Int?,
    entryIndex: Int,
): Map<String, String> {
    return StLorebookEntryExtension(
        group = extensions.stringValue("group"),
        groupOverride = extensions.booleanValue("group_override"),
        groupWeight = extensions.intValue("group_weight"),
        useGroupScoring = extensions.booleanValue("use_group_scoring"),
        sticky = extensions.intValue("sticky"),
        cooldown = extensions.intValue("cooldown"),
        delay = extensions.intValue("delay"),
        delayUntilRecursion = extensions.jsonValue("delay_until_recursion"),
        triggers = extensions.stringListValue("triggers"),
        outletName = extensions.stringValue("outlet_name"),
        useProbability = useProbability,
        ignoreBudget = extensions.booleanValue("ignore_budget"),
        excludeRecursion = extensions.booleanValue("exclude_recursion"),
        preventRecursion = extensions.booleanValue("prevent_recursion"),
        vectorized = extensions.booleanValue("vectorized"),
        automationId = extensions.stringValue("automation_id"),
        extra = buildMap {
            extensions?.forEach { (key, value) ->
                if (key in HANDLED_CHARACTER_BOOK_EXTENSION_KEYS) return@forEach
                putIfPresent(key, value)
            }
            putIfPresent("display_index", extensions?.get("display_index"))
            putIfPresent("probability", probability?.let(::JsonPrimitive))
            putIfPresent("entry_index", JsonPrimitive(entryIndex))
        },
    ).toMetadataMap()
}

private fun buildMap(builder: MutableMap<String, String>.() -> Unit): Map<String, String> {
    return linkedMapOf<String, String>().apply(builder)
}

private fun MutableMap<String, String>.putIfPresent(key: String, value: JsonElement?) {
    when (value) {
        null, JsonNull -> Unit
        is JsonPrimitive -> value.contentOrNull?.let { put(key, it) }
        else -> put(key, value.toString())
    }
}

private fun JsonObject?.booleanValue(key: String): Boolean {
    return this?.get(key)?.jsonPrimitiveOrNull?.booleanOrNull ?: false
}

private fun JsonObject?.intValue(key: String): Int? {
    return this?.get(key)?.jsonPrimitiveOrNull?.intOrNull
}

private fun JsonObject?.stringValue(key: String): String {
    return this?.get(key)?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
}

private fun JsonObject?.jsonValue(key: String): String {
    val element = this?.get(key) ?: return ""
    return (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
}

private fun JsonObject?.stringListValue(key: String): List<String> {
    val element = this?.get(key) ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        else -> Regex("[,\\n]")
            .split((element as? JsonPrimitive)?.contentOrNull ?: element.toString())
            .map { value ->
                value
                    .trim()
                    .removePrefix("[")
                    .removeSuffix("]")
                    .removePrefix("\"")
                    .removeSuffix("\"")
            }
    }.mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .distinct()
}

private val HANDLED_CHARACTER_BOOK_EXTENSION_KEYS = setOf(
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
    "exclude_recursion",
    "prevent_recursion",
    "group",
    "group_override",
    "group_weight",
    "use_group_scoring",
    "sticky",
    "cooldown",
    "delay",
    "delay_until_recursion",
    "triggers",
    "ignore_budget",
    "outlet_name",
    "vectorized",
    "automation_id",
    "display_index",
)
