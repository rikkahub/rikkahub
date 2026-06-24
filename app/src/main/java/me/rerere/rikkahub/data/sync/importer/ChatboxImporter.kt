package me.rerere.rikkahub.data.sync.importer

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.JsonNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.common.json.JsonInstant
import me.rerere.common.json.JsonInstantPretty
import java.io.File
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

/**
 * Chatbox exports its data as a single JSON object that is close to its local storage layout.
 *
 * Expected top-level shape:
 * - `settings`: Chatbox app settings. Provider credentials are under
 *   `settings.providers.openai`, `settings.providers.claude`, and `settings.providers.gemini`.
 * - `chat-sessions-list`: lightweight conversation index. Each item normally contains `id`, `name`, `type`,
 *   and optionally `picUrl`.
 * - `session:<id>`: full conversation payload for the matching item in `chat-sessions-list`.
 *   Important fields are `id`, `name`, `threadName`, `settings`, `messages`, and `messageForksHash`.
 *
 * Message shape inside `session:<id>.messages`:
 * - `role`: `system`, `user`, `assistant`, or `tool`.
 * - `contentParts`: ordered message parts. Supported part types here are:
 *   - `text` -> [UIMessagePart.Text]
 *   - `reasoning` -> [UIMessagePart.Reasoning]
 *   - `tool-call` -> [UIMessagePart.Tool]
 *   - `image` -> dropped intentionally; Chatbox JSON usually stores only a `storageKey`, not image bytes.
 * - `timestamp`: epoch milliseconds.
 * - `usage`: token usage in Chatbox's field names.
 *
 * Import mapping:
 * - One Chatbox `session:<id>` becomes one RikkaHub [Conversation].
 * - Leading `system` messages are merged into [Conversation.customSystemPrompt].
 * - Each remaining Chatbox message becomes one [MessageNode] with one [UIMessage].
 * - Stable UUIDs are derived from Chatbox ids so importing the same file again can skip existing conversations.
 */
object ChatboxImporter {
    fun import(file: File, assistantId: Uuid, providers: List<ProviderSetting>): ChatboxImportPayload {
        val importedProviders = importProviders(file)
        val allProviders = importedProviders + providers
        var skippedImageParts = 0
        var skippedEmptyMessages = 0
        val conversations = arrayListOf<Conversation>()

        file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            forEachSessionSync(reader) { session ->
                val result = parseSession(session, assistantId, allProviders)
                skippedImageParts += result.skippedImageParts
                skippedEmptyMessages += result.skippedEmptyMessages
                result.conversation?.let(conversations::add)
            }
        }

        return ChatboxImportPayload(
            providers = importedProviders,
            conversations = ChatboxConversationImport(
                conversations = conversations,
                skippedImageParts = skippedImageParts,
                skippedEmptyMessages = skippedEmptyMessages,
            ),
        )
    }

    fun importProviders(file: File): List<ProviderSetting> {
        return file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            readSettings(reader)
                ?.let { settings -> importProviders(JsonObject(mapOf("settings" to settings))) }
                ?: emptyList()
        }
    }

    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
        onProvidersImported: suspend (List<ProviderSetting>) -> Unit = {},
        onConversation: suspend (Conversation) -> Unit,
    ): ChatboxStreamingImportResult {
        val importedProviders = importProviders(file)
        val allProviders = importedProviders + providers
        var parsedConversations = 0
        var skippedImageParts = 0
        var skippedEmptyMessages = 0
        var hasConversationSystemPrompt = false

        onProvidersImported(importedProviders)

        file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            forEachSession(reader) { session ->
                val result = parseSession(session, assistantId, allProviders)
                skippedImageParts += result.skippedImageParts
                skippedEmptyMessages += result.skippedEmptyMessages
                result.conversation?.let { conversation ->
                    parsedConversations++
                    if (!conversation.customSystemPrompt.isNullOrBlank()) {
                        hasConversationSystemPrompt = true
                    }
                    onConversation(conversation)
                }
            }
        }

        return ChatboxStreamingImportResult(
            providers = importedProviders,
            parsedConversations = parsedConversations,
            skippedImageParts = skippedImageParts,
            skippedEmptyMessages = skippedEmptyMessages,
            hasConversationSystemPrompt = hasConversationSystemPrompt,
        )
    }

    private fun importProviders(root: JsonObject): List<ProviderSetting> {
        val importProviders = arrayListOf<ProviderSetting>()
        val settingsObj = root["settings"]?.jsonObjectOrNull ?: return emptyList()
        settingsObj["providers"]?.jsonObjectOrNull?.let { providers ->
            providers["openai"]?.jsonObjectOrNull?.let { openai ->
                val apiHost = openai["apiHost"]?.asString ?: "https://api.openai.com"
                val apiKey = openai["apiKey"]?.asString ?: ""
                val models = openai["models"]?.jsonArrayOrNull?.map { element ->
                    val model = element.jsonObject
                    val modelId = model["modelId"]?.asString ?: ""
                    val capabilities = model["capabilities"]?.jsonArrayOrNull
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    Model(
                        modelId = modelId,
                        displayName = modelId,
                        inputModalities = buildList {
                            add(Modality.TEXT)
                            if (capabilities.contains("vision")) {
                                add(Modality.IMAGE)
                            }
                        },
                        abilities = buildList {
                            if (capabilities.contains("tool_use")) {
                                add(ModelAbility.TOOL)
                            }
                            if (capabilities.contains("reasoning")) {
                                add(ModelAbility.REASONING)
                            }
                        }
                    )
                } ?: emptyList()
                if (apiKey.isNotBlank()) {
                    importProviders.add(
                        ProviderSetting.OpenAI(
                            name = "OpenAI",
                            baseUrl = "${apiHost.trimEnd('/')}/v1",
                            apiKey = apiKey,
                            models = models,
                        )
                    )
                }
            }
            providers["claude"]?.jsonObjectOrNull?.let { claude ->
                val apiHost = claude["apiHost"]?.asString ?: "https://api.anthropic.com"
                val apiKey = claude["apiKey"]?.asString ?: ""
                if (apiKey.isNotBlank()) {
                    importProviders.add(
                        ProviderSetting.Claude(
                            name = "Claude",
                            baseUrl = "${apiHost.trimEnd('/')}/v1",
                            apiKey = apiKey,
                        )
                    )
                }
            }
            providers["gemini"]?.jsonObjectOrNull?.let { gemini ->
                val apiHost = gemini["apiHost"]?.asString ?: "https://generativelanguage.googleapis.com"
                val apiKey = gemini["apiKey"]?.asString ?: ""
                if (apiKey.isNotBlank()) {
                    importProviders.add(
                        ProviderSetting.Google(
                            name = "Gemini",
                            baseUrl = "${apiHost.trimEnd('/')}/v1beta",
                            apiKey = apiKey,
                        )
                    )
                }
            }
        }
        return importProviders
    }

    private fun importConversations(
        root: JsonObject,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
    ): ChatboxConversationImport {
        var skippedImageParts = 0
        var skippedEmptyMessages = 0
        val conversations = sessionObjects(root).mapNotNull { session ->
            val result = parseSession(session, assistantId, providers)
            skippedImageParts += result.skippedImageParts
            skippedEmptyMessages += result.skippedEmptyMessages
            result.conversation
        }

        return ChatboxConversationImport(
            conversations = conversations,
            skippedImageParts = skippedImageParts,
            skippedEmptyMessages = skippedEmptyMessages,
        )
    }

    private fun sessionObjects(root: JsonObject): List<JsonObject> {
        val idsFromList = root["chat-sessions-list"]?.jsonArrayOrNull
            ?.mapNotNull { it.jsonObject["id"]?.asString }
            ?: emptyList()
        val listedSessions = idsFromList.mapNotNull { root["session:$it"]?.jsonObjectOrNull }
        val listedIds = idsFromList.toSet()
        val extraSessions = root.entries
            .asSequence()
            .filter { it.key.startsWith("session:") }
            .filter { it.key.removePrefix("session:") !in listedIds }
            .mapNotNull { it.value.jsonObjectOrNull }
            .toList()
        return listedSessions + extraSessions
    }

    private fun parseSession(
        session: JsonObject,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
    ): ChatboxSessionParseResult {
        var skippedImageParts = 0
        var skippedEmptyMessages = 0
        val sessionId = session["id"]?.asString ?: return ChatboxSessionParseResult(null, 0, 0)
        val messages = session["messages"]?.jsonArrayOrNull ?: return ChatboxSessionParseResult(null, 0, 0)
        val sessionSettings = session["settings"]?.jsonObjectOrNull
        val sessionModelId = sessionSettings?.get("modelId")?.asString
        val sessionProvider = sessionSettings?.get("provider")?.asString
        val title = session["threadName"]?.asString
            ?.takeIf { it.isNotBlank() }
            ?: session["name"]?.asString?.takeIf { it.isNotBlank() }
            ?: sessionId

        var customSystemPrompt: String? = null
        var reachedConversationMessages = false
        var minTimestamp: Long? = null
        var maxTimestamp: Long? = null
        val nodes = messages.mapNotNull { element ->
            val message = element.jsonObject
            val timestamp = message["timestamp"]?.asLong
            if (timestamp != null) {
                minTimestamp = minOf(minTimestamp ?: timestamp, timestamp)
                maxTimestamp = maxOf(maxTimestamp ?: timestamp, timestamp)
            }
            val role = message["role"]?.asString?.toMessageRole() ?: return@mapNotNull null
            if (role == MessageRole.SYSTEM && !reachedConversationMessages) {
                val systemPrompt = extractText(message).trim()
                if (systemPrompt.isNotBlank()) {
                    customSystemPrompt = listOfNotNull(customSystemPrompt, systemPrompt).joinToString("\n\n")
                }
                return@mapNotNull null
            }
            reachedConversationMessages = true

            val parseResult = parseParts(message)
            skippedImageParts += parseResult.skippedImageParts
            if (parseResult.parts.isEmpty()) {
                skippedEmptyMessages++
                return@mapNotNull null
            }

            val messageId = message["id"]?.asString ?: "${sessionId}:${message.hashCode()}"
            MessageNode(
                id = stableUuid("chatbox:node:$sessionId:$messageId"),
                messages = listOf(
                    UIMessage(
                        id = stableUuid("chatbox:message:$messageId"),
                        role = role,
                        parts = parseResult.parts,
                        createdAt = millisToLocalDateTime(timestamp),
                        modelId = resolveModelId(
                            providers = providers,
                            providerName = message["aiProvider"]?.asString ?: sessionProvider,
                            modelId = sessionModelId,
                            modelName = message["model"]?.asString
                        ),
                        usage = parseUsage(message["usage"]?.jsonObjectOrNull),
                    )
                ),
                selectIndex = 0
            )
        }

        if (nodes.isEmpty()) {
            return ChatboxSessionParseResult(null, skippedImageParts, skippedEmptyMessages)
        }

        return ChatboxSessionParseResult(
            conversation = Conversation(
                id = stableUuid("chatbox:session:$sessionId"),
                assistantId = assistantId,
                title = title,
                messageNodes = nodes,
                createAt = minTimestamp?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
                updateAt = maxTimestamp?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
                customSystemPrompt = customSystemPrompt,
            ),
            skippedImageParts = skippedImageParts,
            skippedEmptyMessages = skippedEmptyMessages,
        )
    }

    private fun readSettings(reader: Reader): JsonObject? {
        val jsonReader = JsonReader(reader).apply { isLenient = true }
        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "settings" -> return jsonReader.nextJsonElement().jsonObjectOrNull
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
        return null
    }

    private suspend fun forEachSession(
        reader: Reader,
        onSession: suspend (JsonObject) -> Unit,
    ) {
        val jsonReader = JsonReader(reader).apply { isLenient = true }
        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val name = jsonReader.nextName()
            if (name.startsWith("session:")) {
                jsonReader.nextJsonElement().jsonObjectOrNull?.let { session ->
                    onSession(session)
                }
            } else {
                jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
    }

    private fun forEachSessionSync(
        reader: Reader,
        onSession: (JsonObject) -> Unit,
    ) {
        val jsonReader = JsonReader(reader).apply { isLenient = true }
        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val name = jsonReader.nextName()
            if (name.startsWith("session:")) {
                jsonReader.nextJsonElement().jsonObjectOrNull?.let(onSession)
            } else {
                jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
    }

    private fun JsonReader.nextJsonElement(): JsonElement {
        return when (peek()) {
            JsonToken.BEGIN_OBJECT -> {
                beginObject()
                val map = linkedMapOf<String, JsonElement>()
                while (hasNext()) {
                    map[nextName()] = nextJsonElement()
                }
                endObject()
                JsonObject(map)
            }

            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                val list = arrayListOf<JsonElement>()
                while (hasNext()) {
                    list.add(nextJsonElement())
                }
                endArray()
                JsonArray(list)
            }

            JsonToken.STRING -> JsonPrimitive(nextString())
            JsonToken.NUMBER -> nextString().toJsonNumber()
            JsonToken.BOOLEAN -> JsonPrimitive(nextBoolean())
            JsonToken.NULL -> {
                nextNull()
                JsonNull
            }

            else -> {
                skipValue()
                JsonNull
            }
        }
    }

    private fun String.toJsonNumber(): JsonPrimitive {
        return toLongOrNull()?.let { JsonPrimitive(it) }
            ?: toDoubleOrNull()?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(this)
    }

    private fun parseParts(message: JsonObject): ChatboxPartParseResult {
        var skippedImageParts = 0
        val parts = message["contentParts"]?.jsonArrayOrNull
            ?.mapNotNull { part ->
                when (val type = part.jsonObject["type"]?.asString) {
                    "text" -> part.jsonObject["text"]?.asString
                        ?.takeIf { it.isNotBlank() }
                        ?.let { UIMessagePart.Text(it) }

                    "reasoning" -> part.jsonObject["text"]?.asString
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            UIMessagePart.Reasoning(
                                reasoning = it,
                                createdAt = part.jsonObject["startTime"]?.asLong
                                    ?.let(KotlinInstant::fromEpochMilliseconds)
                                    ?: KotlinInstant.fromEpochMilliseconds(message["timestamp"]?.asLong ?: 0L),
                                finishedAt = part.jsonObject["startTime"]?.asLong?.let { start ->
                                    KotlinInstant.fromEpochMilliseconds(
                                        start + (part.jsonObject["duration"]?.asLong ?: 0L)
                                    )
                                }
                            )
                        }

                    "tool-call" -> parseToolPart(part.jsonObject)
                    "image" -> {
                        skippedImageParts++
                        null
                    }

                    else -> {
                        if (type != null) {
                            UIMessagePart.Text(JsonInstantPretty.encodeToString(part))
                        } else {
                            null
                        }
                    }
                }
            }
            ?: emptyList()

        if (parts.isNotEmpty()) {
            return ChatboxPartParseResult(parts, skippedImageParts)
        }

        return ChatboxPartParseResult(
            parts = message["content"]?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(UIMessagePart.Text(it)) }
                ?: emptyList(),
            skippedImageParts = skippedImageParts
        )
    }

    private fun parseToolPart(part: JsonObject): UIMessagePart.Tool? {
        val toolCallId = part["toolCallId"]?.asString ?: return null
        val toolName = part["toolName"]?.asString ?: return null
        val args = part["args"] ?: JsonObject(emptyMap())
        val result = part["result"]
        return UIMessagePart.Tool(
            toolCallId = toolCallId,
            toolName = toolName,
            input = JsonInstant.encodeToString(args),
            output = result?.let {
                listOf(
                    UIMessagePart.Text(
                        when (it) {
                            is JsonPrimitive -> it.contentOrNull ?: it.toString()
                            else -> JsonInstantPretty.encodeToString(it)
                        }
                    )
                )
            } ?: emptyList()
        )
    }

    private fun extractText(message: JsonObject): String {
        val fromParts = message["contentParts"]?.jsonArrayOrNull
            ?.mapNotNull { part ->
                val obj = part.jsonObject
                if (obj["type"]?.asString == "text") {
                    obj["text"]?.asString
                } else {
                    null
                }
            }
            ?.joinToString("\n")
        return fromParts?.takeIf { it.isNotBlank() } ?: message["content"]?.asString.orEmpty()
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        usage ?: return null
        val promptTokens = usage["inputTokens"]?.asInt ?: 0
        val completionTokens = usage["outputTokens"]?.asInt ?: 0
        val cachedTokens = usage["cachedInputTokens"]?.asInt
            ?: usage["inputTokenDetails"]?.jsonObjectOrNull?.get("cacheReadTokens")?.asInt
            ?: 0
        val totalTokens = usage["totalTokens"]?.asInt
            ?: (promptTokens + completionTokens)
        if (promptTokens == 0 && completionTokens == 0 && cachedTokens == 0 && totalTokens == 0) {
            return null
        }
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            totalTokens = totalTokens,
        )
    }

    private fun resolveModelId(
        providers: List<ProviderSetting>,
        providerName: String?,
        modelId: String?,
        modelName: String?,
    ): Uuid? {
        val providerMatches = providers.filter { provider ->
            providerName.isNullOrBlank() ||
                provider.name.equals(providerName, ignoreCase = true) ||
                provider.providerTypeName().equals(providerName, ignoreCase = true)
        }.takeIf { it.isNotEmpty() } ?: providers

        return providerMatches
            .asSequence()
            .flatMap { it.models.asSequence() }
            .firstOrNull { model ->
                listOfNotNull(modelId, modelName).any { imported ->
                    model.modelId.equals(imported, ignoreCase = true) ||
                        model.displayName.equals(imported, ignoreCase = true) ||
                        imported.contains(model.modelId, ignoreCase = true) ||
                        model.displayName.takeIf { it.isNotBlank() }?.let {
                            imported.contains(it, ignoreCase = true)
                        } == true
                }
            }
            ?.id
    }

    private fun String.toMessageRole(): MessageRole? = when (this) {
        "system" -> MessageRole.SYSTEM
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        else -> null
    }

    private fun ProviderSetting.providerTypeName(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "gemini"
        is ProviderSetting.Claude -> "claude"
    }

    private fun millisToLocalDateTime(timestamp: Long?) =
        KotlinInstant.fromEpochMilliseconds(timestamp ?: System.currentTimeMillis())
            .toLocalDateTime(TimeZone.currentSystemDefault())

    private fun stableUuid(value: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString())

    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject

    private val JsonElement.jsonArrayOrNull: JsonArray?
        get() = this as? JsonArray

    private val JsonElement.asString: String?
        get() = (this as? JsonPrimitive)?.contentOrNull

    private val JsonElement.asLong: Long?
        get() = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private val JsonElement.asInt: Int?
        get() = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

data class ChatboxImportPayload(
    val providers: List<ProviderSetting>,
    val conversations: ChatboxConversationImport,
)

data class ChatboxConversationImport(
    val conversations: List<Conversation>,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

data class ChatboxStreamingImportResult(
    val providers: List<ProviderSetting>,
    val parsedConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val hasConversationSystemPrompt: Boolean,
)

private data class ChatboxSessionParseResult(
    val conversation: Conversation?,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

data class ChatboxPartParseResult(
    val parts: List<UIMessagePart>,
    val skippedImageParts: Int,
)
