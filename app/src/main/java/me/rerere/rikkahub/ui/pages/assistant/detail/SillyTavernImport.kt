package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.stripInlineRegexBlocks
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StDepthPrompt
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

private val ImportJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

enum class AssistantImportKind {
    PRESET,
    CHARACTER_CARD,
}

data class AssistantImportPayload(
    val kind: AssistantImportKind,
    val sourceName: String,
    val assistant: Assistant,
    val lorebooks: List<Lorebook> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
)

data class AssistantImportApplication(
    val assistant: Assistant,
    val lorebooks: List<Lorebook>,
)

internal suspend fun parseAssistantImportFromUri(
    context: Context,
    uri: Uri,
    filesManager: FilesManager,
): AssistantImportPayload {
    val sourceName = getDisplayName(context, uri)?.substringBeforeLast('.')?.ifBlank { "Imported" } ?: "Imported"
    val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
    val (jsonString, avatarUri) = withContext(Dispatchers.IO) {
        when (mime) {
            "image/png" -> {
                val result = ImageUtils.getTavernCharacterMeta(context, uri)
                result.map { base64Data ->
                    val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                    val localAvatar = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                    json to localAvatar
                }.getOrElse { throw it }
            }

            "application/json", "text/plain" -> {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                    .use { it?.readText() }
                    ?: error("Failed to read import file")
                json to null
            }

            else -> error("Unsupported file type: ${mime ?: "unknown"}")
        }
    }
    return parseAssistantImportFromJson(
        jsonString = jsonString,
        sourceName = sourceName,
        avatarUri = avatarUri,
    )
}

internal fun parseAssistantImportFromJson(
    jsonString: String,
    sourceName: String,
    avatarUri: String? = null,
): AssistantImportPayload {
    val json = ImportJson.parseToJsonElement(jsonString).jsonObject
    return when {
        json["spec"] != null -> parseCharacterCardImport(json, sourceName, avatarUri)
        json["prompts"] != null && json["prompt_order"] != null -> parsePresetImport(json, sourceName)
        else -> error("Unsupported SillyTavern import format")
    }
}

internal fun applyImportedAssistantForCreate(
    payload: AssistantImportPayload,
    existingLorebooks: List<Lorebook>,
    includeRegexes: Boolean,
): AssistantImportApplication {
    return applyImportedAssistantForCreate(
        currentAssistant = Assistant(),
        payload = payload,
        existingLorebooks = existingLorebooks,
        includeRegexes = includeRegexes,
    )
}

internal fun applyImportedAssistantForCreate(
    currentAssistant: Assistant,
    payload: AssistantImportPayload,
    existingLorebooks: List<Lorebook>,
    includeRegexes: Boolean,
): AssistantImportApplication {
    val lorebooks = mergeLorebooks(existingLorebooks, payload.lorebooks)
    val assistant = when (payload.kind) {
        AssistantImportKind.PRESET -> currentAssistant.copy(
            name = currentAssistant.name.ifBlank { payload.assistant.name },
            avatar = if (currentAssistant.avatar == Avatar.Dummy) payload.assistant.avatar else currentAssistant.avatar,
            temperature = payload.assistant.temperature ?: currentAssistant.temperature,
            topP = payload.assistant.topP ?: currentAssistant.topP,
            maxTokens = payload.assistant.maxTokens ?: currentAssistant.maxTokens,
            openAIReasoningEffort = payload.assistant.openAIReasoningEffort.ifBlank {
                currentAssistant.openAIReasoningEffort
            },
            stPromptTemplate = payload.assistant.stPromptTemplate ?: currentAssistant.stPromptTemplate,
            stCharacterData = currentAssistant.stCharacterData,
            lorebookIds = currentAssistant.lorebookIds + payload.lorebooks.map { it.id }.toSet(),
            regexes = mergeImportedRegexes(
                current = currentAssistant.regexes,
                imported = payload.regexes,
                includeImported = includeRegexes,
            ),
        )

        AssistantImportKind.CHARACTER_CARD -> currentAssistant.copy(
            name = payload.assistant.name.ifBlank { currentAssistant.name },
            avatar = (payload.assistant.avatar as? Avatar.Image) ?: currentAssistant.avatar,
            presetMessages = payload.assistant.presetMessages.ifEmpty { currentAssistant.presetMessages },
            stPromptTemplate = currentAssistant.stPromptTemplate ?: payload.assistant.stPromptTemplate,
            stCharacterData = payload.assistant.stCharacterData ?: currentAssistant.stCharacterData,
            lorebookIds = currentAssistant.lorebookIds + payload.lorebooks.map { it.id }.toSet(),
            regexes = mergeImportedRegexes(
                current = currentAssistant.regexes,
                imported = payload.regexes,
                includeImported = includeRegexes,
            ),
        )
    }
    return AssistantImportApplication(
        assistant = assistant,
        lorebooks = lorebooks,
    )
}

internal fun applyImportedAssistantToExisting(
    currentAssistant: Assistant,
    payload: AssistantImportPayload,
    existingLorebooks: List<Lorebook>,
    includeRegexes: Boolean,
): AssistantImportApplication {
    val mergedLorebooks = mergeLorebooks(existingLorebooks, payload.lorebooks)
    val nextAssistant = when (payload.kind) {
        AssistantImportKind.PRESET -> currentAssistant.copy(
            temperature = payload.assistant.temperature ?: currentAssistant.temperature,
            topP = payload.assistant.topP ?: currentAssistant.topP,
            maxTokens = payload.assistant.maxTokens ?: currentAssistant.maxTokens,
            openAIReasoningEffort = payload.assistant.openAIReasoningEffort.ifBlank {
                currentAssistant.openAIReasoningEffort
            },
            stPromptTemplate = payload.assistant.stPromptTemplate ?: currentAssistant.stPromptTemplate,
            regexes = mergeImportedRegexes(
                current = currentAssistant.regexes,
                imported = payload.regexes,
                includeImported = includeRegexes,
            ),
        )

        AssistantImportKind.CHARACTER_CARD -> currentAssistant.copy(
            name = payload.assistant.name.ifBlank { currentAssistant.name },
            avatar = (payload.assistant.avatar as? Avatar.Image) ?: currentAssistant.avatar,
            presetMessages = payload.assistant.presetMessages.ifEmpty { currentAssistant.presetMessages },
            stPromptTemplate = currentAssistant.stPromptTemplate ?: payload.assistant.stPromptTemplate,
            stCharacterData = payload.assistant.stCharacterData ?: currentAssistant.stCharacterData,
            lorebookIds = currentAssistant.lorebookIds + payload.lorebooks.map { it.id }.toSet(),
            regexes = mergeImportedRegexes(
                current = currentAssistant.regexes,
                imported = payload.regexes,
                includeImported = includeRegexes,
            ),
        )
    }
    return AssistantImportApplication(
        assistant = nextAssistant,
        lorebooks = mergedLorebooks,
    )
}

private fun parsePresetImport(
    json: JsonObject,
    sourceName: String,
): AssistantImportPayload {
    val preset = ImportJson.decodeFromJsonElement<StPresetImport>(json)
    val selectedOrder = selectPresetOrder(preset.promptOrder)
    val orderedPromptIds = selectedOrder.mapNotNull { item ->
        item.identifier.takeIf { item.enabled }
    }.ifEmpty {
        preset.prompts.filter { it.enabled ?: true }.map { it.identifier }
    }
    val promptItems = preset.prompts.map { prompt ->
        SillyTavernPromptItem(
            identifier = prompt.identifier,
            name = prompt.name.orEmpty(),
            role = prompt.role.toMessageRole(),
            content = stripInlineRegexBlocks(prompt.content.orEmpty()),
            systemPrompt = prompt.systemPrompt ?: true,
            marker = prompt.marker ?: false,
            enabled = prompt.enabled ?: true,
            injectionPosition = if ((prompt.injectionPosition ?: 0) == 1) {
                StPromptInjectionPosition.ABSOLUTE
            } else {
                StPromptInjectionPosition.RELATIVE
            },
            injectionDepth = prompt.injectionDepth ?: 4,
            injectionOrder = prompt.injectionOrder ?: 100,
            forbidOverrides = prompt.forbidOverrides ?: false,
        )
    }
    val template = SillyTavernPromptTemplate(
        sourceName = preset.name.ifBlank { sourceName },
        scenarioFormat = preset.scenarioFormat ?: "{{scenario}}",
        personalityFormat = preset.personalityFormat ?: "{{personality}}",
        wiFormat = preset.wiFormat ?: "{0}",
        mainPrompt = promptItems.find { it.identifier == "main" }?.content.orEmpty(),
        newChatPrompt = preset.newChatPrompt.orEmpty(),
        newGroupChatPrompt = preset.newGroupChatPrompt.orEmpty(),
        newExampleChatPrompt = preset.newExampleChatPrompt.orEmpty(),
        continueNudgePrompt = preset.continueNudgePrompt.orEmpty(),
        groupNudgePrompt = preset.groupNudgePrompt.orEmpty(),
        impersonationPrompt = preset.impersonationPrompt.orEmpty(),
        assistantPrefill = preset.assistantPrefill.orEmpty(),
        assistantImpersonation = preset.assistantImpersonation.orEmpty(),
        continuePrefill = preset.continuePrefill ?: false,
        continuePostfix = preset.continuePostfix.orEmpty(),
        sendIfEmpty = preset.sendIfEmpty.orEmpty(),
        namesBehavior = preset.namesBehavior,
        useSystemPrompt = preset.useSystemPrompt ?: false,
        squashSystemMessages = preset.squashSystemMessages ?: false,
        prompts = promptItems,
        orderedPromptIds = orderedPromptIds,
    )

    val regexes = buildList {
        addAll(parseRegexScripts(json["extensions"]?.jsonObject?.get("regex_scripts"), sourceName = preset.name))
        addAll(parseRegexScripts(
            json["extensions"]?.jsonObject
                ?.get("SPreset")
                ?.jsonObjectOrNull()
                ?.get("RegexBinding")
                ?.jsonObjectOrNull()
                ?.get("regexes"),
            sourceName = "${preset.name} (SPreset)"
        ))
        promptItems
            .filter { it.identifier in orderedPromptIds && it.enabled }
            .forEach { prompt ->
                val rawContent = preset.prompts
                    .firstOrNull { it.identifier == prompt.identifier }
                    ?.content
                    .orEmpty()
                addAll(parseInlinePromptRegexes(prompt.copy(content = rawContent)))
            }
    }.distinctBy {
        listOf(
            it.name,
            it.findRegex,
            it.replaceString,
            it.affectingScope.sortedBy { scope -> scope.name }.joinToString(","),
            it.visualOnly.toString(),
            it.promptOnly.toString(),
            it.minDepth?.toString().orEmpty(),
            it.maxDepth?.toString().orEmpty(),
        ).joinToString("|")
    }

    return AssistantImportPayload(
        kind = AssistantImportKind.PRESET,
        sourceName = preset.name.ifBlank { sourceName },
        assistant = Assistant(
            name = preset.name.ifBlank { sourceName },
            temperature = preset.temperature?.toFloat(),
            topP = preset.topP?.toFloat(),
            maxTokens = preset.openAIMaxTokens,
            openAIReasoningEffort = preset.reasoningEffort.orEmpty(),
            stPromptTemplate = template,
        ),
        regexes = regexes,
    )
}

private fun parseCharacterCardImport(
    json: JsonObject,
    sourceName: String,
    avatarUri: String?,
): AssistantImportPayload {
    val data = json["data"]?.jsonObject ?: error("Missing card data")
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: error("Missing card name")
    val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val exampleMessagesRaw = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val systemPromptOverride = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val postHistoryInstructions = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val alternateGreetings = data["alternate_greetings"]?.jsonArrayOrNull()
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        ?: emptyList()
    val extensions = data["extensions"]?.jsonObjectOrNull()
    val depthPrompt = extensions?.get("depth_prompt")?.jsonObjectOrNull()?.let { prompt ->
        StDepthPrompt(
            prompt = prompt["prompt"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            depth = prompt["depth"]?.jsonPrimitiveOrNull?.intOrNull ?: 4,
            role = prompt["role"]?.jsonPrimitiveOrNull?.contentOrNull.toMessageRole(),
        )
    }
    val characterData = SillyTavernCharacterData(
        sourceName = sourceName,
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        systemPromptOverride = systemPromptOverride,
        postHistoryInstructions = postHistoryInstructions,
        firstMessage = firstMessage,
        exampleMessagesRaw = exampleMessagesRaw,
        alternateGreetings = alternateGreetings,
        creatorNotes = creatorNotes,
        depthPrompt = depthPrompt,
    )
    val lorebooks = data["character_book"]?.jsonObjectOrNull()
        ?.let { listOf(parseCharacterBook(it, cardName = name)) }
        ?: emptyList()
    val regexes = parseRegexScripts(extensions?.get("regex_scripts"), sourceName = name)

    return AssistantImportPayload(
        kind = AssistantImportKind.CHARACTER_CARD,
        sourceName = sourceName,
        assistant = Assistant(
            name = name,
            avatar = avatarUri?.let { Avatar.Image(it) } ?: Avatar.Dummy,
            presetMessages = firstMessage.takeIf { it.isNotBlank() }?.let { listOf(UIMessage.assistant(it)) } ?: emptyList(),
            stPromptTemplate = defaultSillyTavernPromptTemplate(),
            stCharacterData = characterData,
            lorebookIds = lorebooks.map { it.id }.toSet(),
        ),
        lorebooks = lorebooks,
        regexes = regexes,
    )
}

internal fun defaultSillyTavernPromptTemplate(): SillyTavernPromptTemplate {
    val prompts = listOf(
        SillyTavernPromptItem(
            identifier = "main",
            name = "Main Prompt",
            role = MessageRole.SYSTEM,
            content = "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.",
            systemPrompt = true,
        ),
        SillyTavernPromptItem(identifier = "worldInfoBefore", name = "World Info (before)", marker = true),
        SillyTavernPromptItem(identifier = "worldInfoAfter", name = "World Info (after)", marker = true),
        SillyTavernPromptItem(identifier = "charDescription", name = "Char Description", marker = true),
        SillyTavernPromptItem(identifier = "charPersonality", name = "Char Personality", marker = true),
        SillyTavernPromptItem(identifier = "scenario", name = "Scenario", marker = true),
        SillyTavernPromptItem(identifier = "personaDescription", name = "Persona Description", marker = true),
        SillyTavernPromptItem(identifier = "dialogueExamples", name = "Chat Examples", marker = true),
        SillyTavernPromptItem(identifier = "chatHistory", name = "Chat History", marker = true),
        SillyTavernPromptItem(
            identifier = "jailbreak",
            name = "Post-History Instructions",
            role = MessageRole.SYSTEM,
            content = "",
            systemPrompt = true,
        ),
    )
    return SillyTavernPromptTemplate(
        sourceName = "SillyTavern Default",
        scenarioFormat = "{{scenario}}",
        personalityFormat = "{{personality}}",
        wiFormat = "{0}",
        mainPrompt = prompts.first().content,
        newChatPrompt = "[Start a new Chat]",
        newGroupChatPrompt = "[Start a new group chat. Group members: {{group}}]",
        newExampleChatPrompt = "[Example Chat]",
        continueNudgePrompt = "[Continue your last message without repeating its original content.]",
        groupNudgePrompt = "[Write the next reply only as {{char}}.]",
        impersonationPrompt = "[Write your next reply from the point of view of {{user}}, using the chat history so far as a guideline for the writing style of {{user}}. Don't write as {{char}} or system. Don't describe actions of {{char}}.]",
        continuePostfix = " ",
        prompts = prompts,
        orderedPromptIds = listOf(
            "main",
            "worldInfoBefore",
            "charDescription",
            "charPersonality",
            "scenario",
            "worldInfoAfter",
            "dialogueExamples",
            "chatHistory",
            "jailbreak",
        ),
    )
}

private fun parseCharacterBook(book: JsonObject, cardName: String): Lorebook {
    val entries = book["entries"]?.jsonArrayOrNull().orEmpty().mapIndexed { index, element ->
        val entry = element.jsonObject
        val extensions = entry["extensions"]?.jsonObjectOrNull()
        PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: entry["name"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: entry["keys"]?.jsonArrayOrNull()?.firstOrNull()?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            enabled = entry["enabled"]?.jsonPrimitiveOrNull?.booleanOrNull ?: true,
            priority = entry["insertion_order"]?.jsonPrimitiveOrNull?.intOrNull ?: 100,
            position = mapCharacterBookPosition(
                entry["position"]?.jsonPrimitiveOrNull?.contentOrNull,
                extensions?.get("position")?.jsonPrimitiveOrNull?.intOrNull,
            ),
            content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            injectDepth = extensions?.get("depth")?.jsonPrimitiveOrNull?.intOrNull ?: 4,
            role = mapExtensionPromptRole(extensions?.get("role")?.jsonPrimitiveOrNull?.intOrNull),
            keywords = entry["keys"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull } ?: emptyList(),
            secondaryKeywords = entry["secondary_keys"]?.jsonArrayOrNull()
                ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                ?: emptyList(),
            selective = entry["selective"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
            selectiveLogic = extensions?.get("selectiveLogic")?.jsonPrimitiveOrNull?.intOrNull ?: 0,
            useRegex = false,
            caseSensitive = (
                entry["case_sensitive"]?.jsonPrimitiveOrNull?.booleanOrNull
                    ?: extensions?.get("case_sensitive")?.jsonPrimitiveOrNull?.booleanOrNull
                    ?: false
                ),
            matchWholeWords = extensions?.get("match_whole_words")?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
            probability = extensions?.get("probability")?.jsonPrimitiveOrNull?.intOrNull,
            scanDepth = extensions?.get("scan_depth")?.jsonPrimitiveOrNull?.intOrNull ?: 4,
            constantActive = entry["constant"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
            matchCharacterDescription = extensions?.get("match_character_description")?.jsonPrimitiveOrNull?.booleanOrNull
                ?: false,
            matchCharacterPersonality = extensions?.get("match_character_personality")?.jsonPrimitiveOrNull?.booleanOrNull
                ?: false,
            matchScenario = extensions?.get("match_scenario")?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
            matchCreatorNotes = extensions?.get("match_creator_notes")?.jsonPrimitiveOrNull?.booleanOrNull ?: false,
            matchCharacterDepthPrompt = extensions?.get("match_character_depth_prompt")?.jsonPrimitiveOrNull?.booleanOrNull
                ?: false,
            stMetadata = buildMap {
                putIfPresent("group", extensions?.get("group"))
                putIfPresent("group_override", extensions?.get("group_override"))
                putIfPresent("group_weight", extensions?.get("group_weight"))
                putIfPresent("sticky", extensions?.get("sticky"))
                putIfPresent("cooldown", extensions?.get("cooldown"))
                putIfPresent("delay", extensions?.get("delay"))
                putIfPresent("delay_until_recursion", extensions?.get("delay_until_recursion"))
                putIfPresent("vectorized", extensions?.get("vectorized"))
                putIfPresent("automation_id", extensions?.get("automation_id"))
                putIfPresent("entry_index", JsonPrimitive(index))
            },
        )
    }
    return Lorebook(
        id = Uuid.random(),
        name = book["name"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { null } ?: "$cardName Lorebook",
        description = book["description"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        enabled = true,
        entries = entries,
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
        2, 5 -> InjectionPosition.TOP_OF_CHAT
        3, 6 -> InjectionPosition.BOTTOM_OF_CHAT
        4 -> InjectionPosition.AT_DEPTH
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

private fun selectPresetOrder(promptOrders: List<StPresetOrderList>): List<StPresetOrderItem> {
    return listOf(100001L, 100000L)
        .mapNotNull { preferred -> promptOrders.find { it.characterId == preferred && it.order.isNotEmpty() } }
        .firstOrNull()
        ?.order
        ?: promptOrders.firstOrNull { it.order.isNotEmpty() }?.order
        ?: emptyList()
}

private fun parseRegexScripts(element: JsonElement?, sourceName: String): List<AssistantRegex> {
    val scripts = element?.jsonArrayOrNull()
        ?.mapNotNull { runCatching { ImportJson.decodeFromJsonElement<StRegexScriptImport>(it) }.getOrNull() }
        ?: return emptyList()
    return scripts.mapNotNull { script ->
        mapRegexScript(
            sourceName = sourceName,
            name = script.scriptName.ifBlank { sourceName },
            findRegex = script.findRegex,
            replaceString = script.replaceString,
            placement = script.placement,
            disabled = script.disabled,
            promptOnly = script.promptOnly,
            markdownOnly = script.markdownOnly,
            minDepth = script.minDepth,
            maxDepth = script.maxDepth,
        )
    }
}

private fun parseInlinePromptRegexes(prompt: SillyTavernPromptItem): List<AssistantRegex> {
    val content = prompt.content
    if (content.isBlank()) return emptyList()

    val regex = Regex("""<regex(?:\s+order=(-?\d+))?>([\s\S]*?)</regex>""")
    return regex.findAll(content).mapIndexedNotNull { index, match ->
        val body = match.groupValues[2].trim()
        val jsonObject = runCatching {
            ImportJson.parseToJsonElement("{${body}}").jsonObject
        }.getOrNull() ?: return@mapIndexedNotNull null

        val entry = jsonObject.entries.firstOrNull() ?: return@mapIndexedNotNull null
        mapRegexScript(
            sourceName = prompt.name.ifBlank { prompt.identifier },
            name = "${prompt.name.ifBlank { prompt.identifier }} Regex ${index + 1}",
            findRegex = entry.key,
            replaceString = entry.value.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            placement = listOf(1),
            disabled = false,
            promptOnly = true,
            markdownOnly = false,
            minDepth = null,
            maxDepth = null,
            affectingScopeOverride = setOf(AssistantAffectScope.SYSTEM),
        )
    }.toList()
}

private fun mapRegexScript(
    sourceName: String,
    name: String,
    findRegex: String,
    replaceString: String,
    placement: List<Int>,
    disabled: Boolean,
    promptOnly: Boolean,
    markdownOnly: Boolean,
    minDepth: Int?,
    maxDepth: Int?,
    affectingScopeOverride: Set<AssistantAffectScope>? = null,
): AssistantRegex? {
    val normalizedPattern = normalizeImportedRegexPattern(findRegex) ?: return null
    val affectingScope = affectingScopeOverride ?: buildSet {
        if (placement.contains(1)) add(AssistantAffectScope.USER)
        if (placement.contains(2)) add(AssistantAffectScope.ASSISTANT)
    }.ifEmpty {
        setOf(AssistantAffectScope.ASSISTANT)
    }

    return AssistantRegex(
        id = Uuid.random(),
        name = name.ifBlank { sourceName },
        enabled = !disabled,
        findRegex = normalizedPattern,
        replaceString = replaceString,
        affectingScope = affectingScope,
        visualOnly = markdownOnly && !promptOnly,
        promptOnly = promptOnly,
        minDepth = minDepth,
        maxDepth = maxDepth,
    )
}

private fun mergeImportedRegexes(
    current: List<AssistantRegex>,
    imported: List<AssistantRegex>,
    includeImported: Boolean,
): List<AssistantRegex> {
    if (!includeImported || imported.isEmpty()) return current
    return (current + imported).distinctBy {
        listOf(
            it.name,
            it.findRegex,
            it.replaceString,
            it.affectingScope.sortedBy { scope -> scope.name }.joinToString(","),
            it.visualOnly.toString(),
            it.promptOnly.toString(),
            it.minDepth?.toString().orEmpty(),
            it.maxDepth?.toString().orEmpty(),
        ).joinToString("|")
    }
}

private fun normalizeImportedRegexPattern(findRegex: String): String? {
    if (findRegex.isBlank()) return null
    val match = Regex("""^/(.*?)(?<!\\)/([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
        .matchEntire(findRegex)
        ?: return findRegex

    val pattern = match.groupValues[1]
    val flags = match.groupValues[2]
    val inlineFlags = buildString {
        if ('i' in flags) append('i')
        if ('m' in flags) append('m')
        if ('s' in flags) append('s')
    }
    return if (inlineFlags.isEmpty()) pattern else "(?$inlineFlags)$pattern"
}

private fun mergeLorebooks(existing: List<Lorebook>, imported: List<Lorebook>): List<Lorebook> {
    if (imported.isEmpty()) return existing
    val usedNames = existing.map { it.name }.toMutableSet()
    val renamed = imported.map { lorebook ->
        val uniqueName = makeUniqueLorebookName(lorebook.name, usedNames)
        usedNames += uniqueName
        lorebook.copy(name = uniqueName)
    }
    return existing + renamed
}

private fun makeUniqueLorebookName(originalName: String, usedNames: Set<String>): String {
    val base = originalName.ifBlank { "Imported Lorebook" }
    if (base !in usedNames) return base

    var candidate = "$base (Imported)"
    if (candidate !in usedNames) return candidate

    var index = 2
    while (candidate in usedNames) {
        candidate = "$base (Imported $index)"
        index++
    }
    return candidate
}

private fun getDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0) cursor.getString(column) else null
    }
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

private fun String?.toMessageRole(): MessageRole {
    return when (this?.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        else -> MessageRole.SYSTEM
    }
}

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

@Serializable
private data class StPresetImport(
    val name: String = "",
    val temperature: Double? = null,
    val top_p: Double? = null,
    val openai_max_tokens: Int? = null,
    val names_behavior: Int? = null,
    val send_if_empty: String? = null,
    val assistant_prefill: String? = null,
    val assistant_impersonation: String? = null,
    val continue_prefill: Boolean? = null,
    val continue_postfix: String? = null,
    val new_chat_prompt: String? = null,
    val new_group_chat_prompt: String? = null,
    val new_example_chat_prompt: String? = null,
    val use_sysprompt: Boolean? = null,
    val squash_system_messages: Boolean? = null,
    val reasoning_effort: String? = null,
    val scenario_format: String? = null,
    val personality_format: String? = null,
    val wi_format: String? = null,
    val continue_nudge_prompt: String? = null,
    val group_nudge_prompt: String? = null,
    val impersonation_prompt: String? = null,
    val prompts: List<StPresetPromptImport> = emptyList(),
    val prompt_order: List<StPresetOrderList> = emptyList(),
) {
    val topP: Double?
        get() = top_p

    val openAIMaxTokens: Int?
        get() = openai_max_tokens

    val namesBehavior: Int?
        get() = names_behavior

    val sendIfEmpty: String?
        get() = send_if_empty

    val assistantPrefill: String?
        get() = assistant_prefill

    val assistantImpersonation: String?
        get() = assistant_impersonation

    val continuePrefill: Boolean?
        get() = continue_prefill

    val continuePostfix: String?
        get() = continue_postfix

    val newChatPrompt: String?
        get() = new_chat_prompt

    val newGroupChatPrompt: String?
        get() = new_group_chat_prompt

    val newExampleChatPrompt: String?
        get() = new_example_chat_prompt

    val useSystemPrompt: Boolean?
        get() = use_sysprompt

    val squashSystemMessages: Boolean?
        get() = squash_system_messages

    val reasoningEffort: String?
        get() = reasoning_effort

    val scenarioFormat: String?
        get() = scenario_format

    val personalityFormat: String?
        get() = personality_format

    val wiFormat: String?
        get() = wi_format

    val continueNudgePrompt: String?
        get() = continue_nudge_prompt

    val groupNudgePrompt: String?
        get() = group_nudge_prompt

    val impersonationPrompt: String?
        get() = impersonation_prompt

    val promptOrder: List<StPresetOrderList>
        get() = prompt_order
}

@Serializable
private data class StPresetPromptImport(
    val identifier: String = "",
    val name: String? = null,
    val role: String? = null,
    val content: String? = null,
    val system_prompt: Boolean? = null,
    val marker: Boolean? = null,
    val enabled: Boolean? = null,
    val injection_position: Int? = null,
    val injection_depth: Int? = null,
    val injection_order: Int? = null,
    val forbid_overrides: Boolean? = null,
) {
    val systemPrompt: Boolean?
        get() = system_prompt

    val injectionPosition: Int?
        get() = injection_position

    val injectionDepth: Int?
        get() = injection_depth

    val injectionOrder: Int?
        get() = injection_order

    val forbidOverrides: Boolean?
        get() = forbid_overrides
}

@Serializable
private data class StPresetOrderList(
    val character_id: Long? = null,
    val order: List<StPresetOrderItem> = emptyList(),
) {
    val characterId: Long?
        get() = character_id
}

@Serializable
private data class StPresetOrderItem(
    val identifier: String? = null,
    val enabled: Boolean = true,
)

@Serializable
private data class StRegexScriptImport(
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
)
