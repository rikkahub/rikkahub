package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPresetSampling
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode

internal val ImportJson = Json {
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
    val presetTemplate: me.rerere.rikkahub.data.model.SillyTavernPromptTemplate? = null,
    val lorebooks: List<Lorebook> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val avatarImportSourceUri: String? = null,
)

data class AssistantImportApplication(
    val assistant: Assistant,
    val lorebooks: List<Lorebook>,
    val sharedRegexes: List<AssistantRegex> = emptyList(),
)

internal fun AssistantImportPayload.toSillyTavernPreset(): SillyTavernPreset {
    require(kind == AssistantImportKind.PRESET) {
        "Only preset payloads can be converted into SillyTavern presets"
    }
    return SillyTavernPreset(
        template = presetTemplate ?: defaultSillyTavernPromptTemplate(),
        regexes = regexes,
        sampling = SillyTavernPresetSampling(
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            frequencyPenalty = assistant.frequencyPenalty,
            presencePenalty = assistant.presencePenalty,
            minP = assistant.minP,
            topK = assistant.topK,
            topA = assistant.topA,
            repetitionPenalty = assistant.repetitionPenalty,
            seed = assistant.seed,
            stopSequences = assistant.stopSequences,
            openAIReasoningEffort = assistant.openAIReasoningEffort,
            openAIVerbosity = assistant.openAIVerbosity,
        ),
    )
}

internal suspend fun parseAssistantImportFromUri(
    context: Context,
    uri: Uri,
    filesManager: FilesManager,
): AssistantImportPayload {
    val sourceName = getDisplayName(context, uri)?.substringBeforeLast('.')?.ifBlank { "Imported" } ?: "Imported"
    val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
    val (jsonString, avatarImportSourceUri) = withContext(Dispatchers.IO) {
        when (mime) {
            "image/png" -> {
                val result = ImageUtils.getTavernCharacterMeta(context, uri)
                result.map { rawCharacterMeta ->
                    val json = decodeImportedCharacterCardJson(rawCharacterMeta)
                    json to uri.toString()
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
        avatarImportSourceUri = avatarImportSourceUri,
    )
}

internal fun decodeImportedCharacterCardJson(rawCharacterMeta: String): String {
    val trimmedMeta = rawCharacterMeta.trim()
    if (trimmedMeta.isEmpty()) error("Empty character data")
    return if (trimmedMeta.startsWith("{") || trimmedMeta.startsWith("[")) {
        trimmedMeta
    } else {
        trimmedMeta.base64Decode()
    }
}

internal fun parseAssistantImportFromJson(
    jsonString: String,
    sourceName: String,
    avatarImportSourceUri: String? = null,
): AssistantImportPayload {
    val json = ImportJson.parseToJsonElement(jsonString).jsonObject
    return when {
        json["spec"] != null -> parseCharacterCardImport(json, sourceName, avatarImportSourceUri)
        json["prompts"] != null && json["prompt_order"] != null -> parsePresetImport(json, sourceName)
        else -> error("Unsupported SillyTavern import format")
    }
}

internal suspend fun AssistantImportPayload.materializeImportedAvatar(
    filesManager: FilesManager,
): AssistantImportPayload {
    val sourceUri = avatarImportSourceUri ?: return this
    val localAvatarUri = withContext(Dispatchers.IO) {
        filesManager.createChatFilesByContents(listOf(Uri.parse(sourceUri))).firstOrNull()?.toString()
    } ?: error("Failed to import avatar")
    return withMaterializedImportedAvatar(localAvatarUri)
}

internal fun AssistantImportPayload.withMaterializedImportedAvatar(
    localAvatarUri: String,
): AssistantImportPayload {
    return copy(
        assistant = assistant.copy(avatar = Avatar.Image(localAvatarUri)),
        avatarImportSourceUri = null,
    )
}

internal fun applyImportedAssistantForCreate(
    payload: AssistantImportPayload,
    existingLorebooks: List<Lorebook>,
    existingSharedRegexes: List<AssistantRegex> = emptyList(),
    includeRegexes: Boolean,
): AssistantImportApplication {
    return applyImportedAssistantForCreate(
        currentAssistant = Assistant(),
        payload = payload,
        existingLorebooks = existingLorebooks,
        existingSharedRegexes = existingSharedRegexes,
        includeRegexes = includeRegexes,
    )
}

internal fun applyImportedAssistantForCreate(
    currentAssistant: Assistant,
    payload: AssistantImportPayload,
    existingLorebooks: List<Lorebook>,
    existingSharedRegexes: List<AssistantRegex> = emptyList(),
    includeRegexes: Boolean,
): AssistantImportApplication {
    require(payload.kind == AssistantImportKind.CHARACTER_CARD) {
        "Preset imports must be handled from the global ST preset page"
    }
    val lorebooks = mergeLorebooks(existingLorebooks, payload.lorebooks)
    val assistant = currentAssistant.copy(
        name = payload.assistant.name.ifBlank { currentAssistant.name },
        avatar = (payload.assistant.avatar as? Avatar.Image) ?: currentAssistant.avatar,
        presetMessages = payload.assistant.presetMessages.ifEmpty { currentAssistant.presetMessages },
        stCharacterData = payload.assistant.stCharacterData ?: currentAssistant.stCharacterData,
        lorebookIds = currentAssistant.lorebookIds + payload.lorebooks.map { it.id }.toSet(),
        regexes = mergeImportedRegexes(
            current = currentAssistant.regexes,
            imported = payload.regexes,
            includeImported = includeRegexes,
        ),
    )
    return AssistantImportApplication(
        assistant = assistant,
        lorebooks = lorebooks,
        sharedRegexes = existingSharedRegexes,
    )
}

internal fun applyImportedAssistantToExisting(
    currentAssistant: Assistant,
    payload: AssistantImportPayload,
    existingLorebooks: List<Lorebook>,
    existingSharedRegexes: List<AssistantRegex> = emptyList(),
    includeRegexes: Boolean,
): AssistantImportApplication {
    require(payload.kind == AssistantImportKind.CHARACTER_CARD) {
        "Preset imports must be handled from the global ST preset page"
    }
    val mergedLorebooks = mergeLorebooks(existingLorebooks, payload.lorebooks)
    val nextAssistant = currentAssistant.copy(
        name = payload.assistant.name.ifBlank { currentAssistant.name },
        avatar = (payload.assistant.avatar as? Avatar.Image) ?: currentAssistant.avatar,
        presetMessages = payload.assistant.presetMessages.ifEmpty { currentAssistant.presetMessages },
        stCharacterData = payload.assistant.stCharacterData ?: currentAssistant.stCharacterData,
        lorebookIds = currentAssistant.lorebookIds + payload.lorebooks.map { it.id }.toSet(),
        regexes = mergeImportedRegexes(
            current = currentAssistant.regexes,
            imported = payload.regexes,
            includeImported = includeRegexes,
        ),
    )
    return AssistantImportApplication(
        assistant = nextAssistant,
        lorebooks = mergedLorebooks,
        sharedRegexes = existingSharedRegexes,
    )
}

internal fun mergeImportedRegexes(
    current: List<AssistantRegex>,
    imported: List<AssistantRegex>,
    includeImported: Boolean,
): List<AssistantRegex> {
    if (!includeImported || imported.isEmpty()) return current
    return (current + imported).distinctBy(::regexDedupKey)
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
