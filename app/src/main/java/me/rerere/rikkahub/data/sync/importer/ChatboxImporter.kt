package me.rerere.rikkahub.data.sync.importer

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

/**
 * Imports Chatbox Backup v2 archives.
 *
 * The archive layout is described by `manifest.json`. Settings live in a standalone JSON file,
 * sessions live under `sessions/<id>/session.json`, and binary resources are referenced from
 * message parts through Chatbox storage keys.
 */
object ChatboxImporter {
    private const val BACKUP_FORMAT = "chatbox-backup"
    private const val BACKUP_FORMAT_VERSION = 2
    private const val MANIFEST_PATH = "manifest.json"
    private const val DEFAULT_SETTINGS_PATH = "settings.json"
    private const val MAX_JSON_ENTRY_SIZE = 64L * 1024 * 1024
    private const val MAX_IMAGE_ENTRY_SIZE = 64L * 1024 * 1024

    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
        shouldImportConversation: suspend (Uuid) -> Boolean = { true },
        saveImage: suspend (ChatboxImageResource) -> String? = { null },
        onConversation: suspend (Conversation) -> Unit,
    ): ChatboxStreamingImportResult {
        var parsedConversations = 0
        val counters = ImportCounters()

        ZipFile(file).use { zip ->
            val manifest = zip.readJsonObject(MANIFEST_PATH, required = true)
                ?: error("Invalid Chatbox backup: $MANIFEST_PATH not found")
            validateManifest(manifest)

            val settingsPath = manifest["data"]?.jsonObjectOrNull
                ?.get("settings")?.jsonObjectOrNull
                ?.get("path")?.asString
                ?: DEFAULT_SETTINGS_PATH
            val importedProviders = zip.readJsonObject(settingsPath, required = false)
                ?.let(::parseProviders)
                .orEmpty()
                .distinctBy { it.id }
                .filterNot { imported ->
                    providers.any { existing ->
                        existing.id != imported.id && providerIdentity(existing) == providerIdentity(imported)
                    }
                }
            val allProviders = importedProviders + providers.filterNot { existing ->
                importedProviders.any { it.id == existing.id }
            }

            val resourcesByStorageKey = buildMap {
                parseResources(manifest).forEach { resource ->
                    resource.storageKeys.forEach { storageKey -> put(storageKey, resource) }
                }
            }
            val savedResourceUrls = mutableMapOf<String, String>()
            val failedResourcePaths = mutableSetOf<String>()

            suspend fun resolveImage(storageKey: String): String? {
                savedResourceUrls[storageKey]?.let { return it }
                val resource = resourcesByStorageKey[storageKey]
                if (resource == null || resource.kind != "image") return null
                resource.storageKeys.firstNotNullOfOrNull(savedResourceUrls::get)?.let { return it }
                if (resource.path in failedResourcePaths) return null
                val entry = zip.getEntry(resource.path)
                if (entry == null || entry.isDirectory || !entry.hasAllowedSize(MAX_IMAGE_ENTRY_SIZE)) {
                    failedResourcePaths += resource.path
                    return null
                }
                val bytes = zip.readEntryBytes(entry, MAX_IMAGE_ENTRY_SIZE)
                val url = saveImage(
                    ChatboxImageResource(
                        storageKey = storageKey,
                        bytes = bytes,
                        fileName = resource.path.substringAfterLast('/'),
                        mimeType = resource.mimeType.ifBlank { "image/*" },
                    )
                )
                if (url.isNullOrBlank()) {
                    failedResourcePaths += resource.path
                    return null
                }
                resource.storageKeys.forEach { savedResourceUrls[it] = url }
                return url
            }

            parseSessionEntries(manifest).forEach { sessionEntry ->
                val session = zip.readJsonObject(sessionEntry.path, required = true)
                    ?: error("Invalid Chatbox backup: ${sessionEntry.path} not found")
                val sessionId = session["id"]?.asString ?: sessionEntry.id
                if (sessionId.isBlank()) {
                    counters.skippedSessions++
                    return@forEach
                }

                val conversationId = stableUuid("chatbox:session:$sessionId")
                if (!shouldImportConversation(conversationId)) return@forEach

                val conversation = parseSession(
                    session = session,
                    sessionEntry = sessionEntry,
                    assistantId = assistantId,
                    providers = allProviders,
                    resolveImage = ::resolveImage,
                    counters = counters,
                )
                if (conversation == null) {
                    counters.skippedSessions++
                } else {
                    parsedConversations++
                    onConversation(conversation)
                }
            }

            return ChatboxStreamingImportResult(
                providers = importedProviders,
                parsedConversations = parsedConversations,
                importedImageParts = counters.importedImageParts,
                skippedImageParts = counters.skippedImageParts,
                skippedEmptyMessages = counters.skippedEmptyMessages,
                skippedForkMessages = counters.skippedForkMessages,
                skippedSessions = counters.skippedSessions,
                hasConversationSystemPrompt = counters.hasConversationSystemPrompt,
            )
        }
    }

    private fun validateManifest(manifest: JsonObject) {
        val format = manifest["format"]?.asString
        val version = manifest["formatVersion"]?.asInt
        require(format == BACKUP_FORMAT) {
            "Invalid Chatbox backup: unsupported format ${format ?: "<missing>"}"
        }
        require(version == BACKUP_FORMAT_VERSION) {
            "Unsupported Chatbox backup version ${version ?: "<missing>"}"
        }
    }

    private fun parseProviders(settings: JsonObject): List<ProviderSetting> {
        val providers = settings["providers"]?.jsonObjectOrNull ?: return emptyList()
        return providers.mapNotNull { (providerKey, element) ->
            val provider = element.jsonObjectOrNull ?: return@mapNotNull null
            val apiKey = provider["apiKey"]?.asString?.trim().orEmpty()
            if (apiKey.isBlank()) return@mapNotNull null

            val normalizedKey = providerKey.lowercase()
            val models = parseModels(normalizedKey, provider["models"]?.jsonArrayOrNull)
            val apiHost = provider["apiHost"]?.asString.orEmpty()
            val id = stableUuid("chatbox:provider:$normalizedKey")
            val name = providerDisplayName(normalizedKey)

            when (normalizedKey) {
                "claude", "anthropic" -> ProviderSetting.Claude(
                    id = id,
                    name = name,
                    baseUrl = normalizeBaseUrl(
                        apiHost = apiHost,
                        suffix = "/v1",
                        fallback = ProviderSetting.Claude().baseUrl,
                    ),
                    apiKey = apiKey,
                    models = models,
                )

                "gemini", "google" -> ProviderSetting.Google(
                    id = id,
                    name = name,
                    baseUrl = normalizeBaseUrl(
                        apiHost = apiHost,
                        suffix = "/v1beta",
                        fallback = ProviderSetting.Google().baseUrl,
                    ),
                    apiKey = apiKey,
                    models = models,
                )

                else -> {
                    val fallback = openAiCompatibleBaseUrl(normalizedKey)
                        ?: apiHost.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val usesResponseApi = provider["apiStyle"]?.asString?.contains("response", true) == true ||
                        provider["models"]?.jsonArrayOrNull?.any { model ->
                            model.jsonObjectOrNull?.get("apiStyle")?.asString?.contains("response", true) == true
                        } == true
                    ProviderSetting.OpenAI(
                        id = id,
                        name = name,
                        baseUrl = normalizeBaseUrl(apiHost, "/v1", fallback),
                        apiKey = apiKey,
                        models = models,
                        useResponseApi = usesResponseApi,
                    )
                }
            }
        }
    }

    private fun parseModels(providerKey: String, models: JsonArray?): List<Model> {
        return models.orEmpty().mapNotNull { element ->
            val model = element.jsonObjectOrNull ?: return@mapNotNull null
            val modelId = model["modelId"]?.asString?.trim().orEmpty()
            if (modelId.isBlank()) return@mapNotNull null
            val capabilities = model["capabilities"]?.jsonArrayOrNull
                ?.mapNotNull { it.asString?.lowercase() }
                .orEmpty()
            Model(
                id = stableUuid("chatbox:model:$providerKey:$modelId"),
                modelId = modelId,
                displayName = model["nickname"]?.asString?.takeIf { it.isNotBlank() } ?: modelId,
                type = when (model["type"]?.asString?.lowercase()) {
                    "image" -> ModelType.IMAGE
                    "embedding", "embeddings" -> ModelType.EMBEDDING
                    else -> ModelType.CHAT
                },
                inputModalities = buildList {
                    add(Modality.TEXT)
                    if ("vision" in capabilities) add(Modality.IMAGE)
                },
                outputModalities = buildList {
                    add(Modality.TEXT)
                    if ("image_generation" in capabilities) add(Modality.IMAGE)
                },
                abilities = buildList {
                    if ("tool_use" in capabilities) add(ModelAbility.TOOL)
                    if ("reasoning" in capabilities) add(ModelAbility.REASONING)
                },
            )
        }.distinctBy { it.modelId }
    }

    private suspend fun parseSession(
        session: JsonObject,
        sessionEntry: SessionEntry,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
        resolveImage: suspend (String) -> String?,
        counters: ImportCounters,
    ): Conversation? {
        val sessionId = session["id"]?.asString ?: sessionEntry.id
        val rawMessages = session["messages"]?.jsonArrayOrNull
            ?.mapNotNull { it.jsonObjectOrNull }
            ?: return null
        val sessionSettings = session["settings"]?.jsonObjectOrNull
        val sessionModelId = sessionSettings?.get("modelId")?.asString
        val sessionProvider = sessionSettings?.get("provider")?.asString

        val allMessageObjects = buildList {
            addAll(rawMessages)
            session["messageForksHash"]?.jsonObjectOrNull?.values?.forEach { forkElement ->
                forkElement.jsonObjectOrNull?.get("lists")?.jsonArrayOrNull?.forEach { listElement ->
                    listElement.jsonObjectOrNull?.get("messages")?.jsonArrayOrNull?.forEach { message ->
                        message.jsonObjectOrNull?.let(::add)
                    }
                }
            }
        }
        val timestamps = allMessageObjects.mapNotNull { it["timestamp"]?.asLong }
        val createTimestamp = sessionEntry.createdAt ?: timestamps.minOrNull() ?: System.currentTimeMillis()
        val updateTimestamp = timestamps.maxOrNull() ?: createTimestamp

        val forks = parseForks(session["messageForksHash"]?.jsonObjectOrNull)
        var customSystemPrompt: String? = null
        var reachedConversationMessages = false
        val nodes = arrayListOf<MessageNode>()

        rawMessages.forEachIndexed { index, message ->
            val role = message["role"]?.asString?.toMessageRole() ?: return@forEachIndexed
            if (role == MessageRole.SYSTEM && !reachedConversationMessages) {
                val systemPrompt = extractText(message).trim()
                if (systemPrompt.isNotBlank()) {
                    customSystemPrompt = listOfNotNull(customSystemPrompt, systemPrompt).joinToString("\n\n")
                }
                return@forEachIndexed
            }
            reachedConversationMessages = true

            val mainMessage = parseMessage(
                message = message,
                sessionId = sessionId,
                sessionProvider = sessionProvider,
                sessionModelId = sessionModelId,
                providers = providers,
                fallbackTimestamp = createTimestamp,
                resolveImage = resolveImage,
                counters = counters,
            )
            val anchorId = rawMessages.getOrNull(index - 1)?.get("id")?.asString
            val fork = anchorId?.let(forks::get)
            val alternatives = arrayListOf<UIMessage>()
            var selectedIndex = -1

            if (fork != null) {
                fork.lists.forEachIndexed { forkIndex, forkMessages ->
                    if (forkIndex == fork.position) {
                        mainMessage?.let {
                            selectedIndex = alternatives.size
                            alternatives += it
                        }
                    } else {
                        forkMessages.firstOrNull()?.let { forkMessage ->
                            parseMessage(
                                message = forkMessage,
                                sessionId = sessionId,
                                sessionProvider = sessionProvider,
                                sessionModelId = sessionModelId,
                                providers = providers,
                                fallbackTimestamp = createTimestamp,
                                resolveImage = resolveImage,
                                counters = counters,
                            )?.let(alternatives::add)
                        }
                    }
                    counters.skippedForkMessages += (forkMessages.size - 1).coerceAtLeast(0)
                }
            }

            if (mainMessage != null && selectedIndex < 0) {
                selectedIndex = alternatives.size
                alternatives += mainMessage
            }
            val distinctAlternatives = alternatives.distinctBy { it.id }
            if (distinctAlternatives.isEmpty()) return@forEachIndexed
            val selectedMessageId = mainMessage?.id
            selectedIndex = distinctAlternatives.indexOfFirst { it.id == selectedMessageId }
                .takeIf { it >= 0 }
                ?: 0
            val rawMessageId = message["id"]?.asString ?: "$sessionId:$index"
            nodes += MessageNode(
                id = stableUuid("chatbox:node:$sessionId:$rawMessageId"),
                messages = distinctAlternatives,
                selectIndex = selectedIndex,
            )
        }

        if (nodes.isEmpty()) return null
        if (!customSystemPrompt.isNullOrBlank()) counters.hasConversationSystemPrompt = true

        val title = session["threadName"]?.asString?.takeIf { it.isNotBlank() }
            ?: session["name"]?.asString?.takeIf { it.isNotBlank() }
            ?: sessionEntry.name?.takeIf { it.isNotBlank() }
            ?: sessionId
        return Conversation(
            id = stableUuid("chatbox:session:$sessionId"),
            assistantId = assistantId,
            title = title,
            messageNodes = nodes,
            isPinned = session["starred"]?.asBoolean ?: sessionEntry.starred,
            createAt = Instant.ofEpochMilli(createTimestamp),
            updateAt = Instant.ofEpochMilli(updateTimestamp),
            customSystemPrompt = customSystemPrompt,
        )
    }

    private suspend fun parseMessage(
        message: JsonObject,
        sessionId: String,
        sessionProvider: String?,
        sessionModelId: String?,
        providers: List<ProviderSetting>,
        fallbackTimestamp: Long,
        resolveImage: suspend (String) -> String?,
        counters: ImportCounters,
    ): UIMessage? {
        val role = message["role"]?.asString?.toMessageRole() ?: return null
        val parts = parseParts(message, fallbackTimestamp, resolveImage, counters)
        if (parts.isEmpty()) {
            counters.skippedEmptyMessages++
            return null
        }
        val messageId = message["id"]?.asString ?: "$sessionId:${message.hashCode()}"
        return UIMessage(
            id = stableUuid("chatbox:message:$sessionId:$messageId"),
            role = role,
            parts = parts,
            createdAt = millisToLocalDateTime(message["timestamp"]?.asLong ?: fallbackTimestamp),
            modelId = resolveModelId(
                providers = providers,
                providerName = message["aiProvider"]?.asString ?: sessionProvider,
                modelId = sessionModelId,
                modelName = message["model"]?.asString,
            ),
            usage = parseUsage(message["usage"]?.jsonObjectOrNull),
        )
    }

    private suspend fun parseParts(
        message: JsonObject,
        fallbackTimestamp: Long,
        resolveImage: suspend (String) -> String?,
        counters: ImportCounters,
    ): List<UIMessagePart> {
        val parts = arrayListOf<UIMessagePart>()
        message["contentParts"]?.jsonArrayOrNull?.forEach { element ->
            val part = element.jsonObjectOrNull ?: return@forEach
            when (val type = part["type"]?.asString) {
                "text" -> part["text"]?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parts += UIMessagePart.Text(it) }

                "reasoning" -> part["text"]?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { reasoning ->
                        val messageTimestamp = message["timestamp"]?.asLong ?: fallbackTimestamp
                        val startTime = part["startTime"]?.asLong ?: messageTimestamp
                        parts += UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = KotlinInstant.fromEpochMilliseconds(startTime),
                            finishedAt = part["duration"]?.asLong?.let { duration ->
                                KotlinInstant.fromEpochMilliseconds(startTime + duration)
                            },
                        )
                    }

                "tool-call" -> parseToolPart(part)?.let(parts::add)
                "image" -> {
                    val directUrl = part["url"]?.asString?.takeIf { it.isNotBlank() }
                    val storageKey = part["storageKey"]?.asString
                    val imageUrl = directUrl ?: storageKey?.let { resolveImage(it) }
                    if (imageUrl.isNullOrBlank()) {
                        counters.skippedImageParts++
                    } else {
                        counters.importedImageParts++
                        parts += UIMessagePart.Image(imageUrl)
                    }
                }

                else -> if (type != null) {
                    parts += UIMessagePart.Text(JsonInstantPretty.encodeToString(element))
                }
            }
        }

        if (parts.isNotEmpty()) return parts
        return message["content"]?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(UIMessagePart.Text(it)) }
            .orEmpty()
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
            }.orEmpty(),
        )
    }

    private fun extractText(message: JsonObject): String {
        val fromParts = message["contentParts"]?.jsonArrayOrNull
            ?.mapNotNull { part ->
                part.jsonObjectOrNull?.takeIf { it["type"]?.asString == "text" }?.get("text")?.asString
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
        val totalTokens = usage["totalTokens"]?.asInt ?: (promptTokens + completionTokens)
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

        return providerMatches.asSequence()
            .flatMap { it.models.asSequence() }
            .firstOrNull { model ->
                listOfNotNull(modelId, modelName).any { imported ->
                    model.modelId.equals(imported, ignoreCase = true) ||
                        model.displayName.equals(imported, ignoreCase = true) ||
                        imported.contains(model.modelId, ignoreCase = true) ||
                        model.displayName.takeIf { it.isNotBlank() }
                            ?.let { imported.contains(it, ignoreCase = true) } == true
                }
            }
            ?.id
    }

    private fun parseSessionEntries(manifest: JsonObject): List<SessionEntry> {
        return manifest["sessions"]?.jsonArrayOrNull.orEmpty().mapNotNull { element ->
            val entry = element.jsonObjectOrNull ?: return@mapNotNull null
            val path = entry["path"]?.asString ?: return@mapNotNull null
            require(isSafeArchivePath(path)) { "Invalid Chatbox backup entry path: $path" }
            val meta = entry["meta"]?.jsonObjectOrNull
            SessionEntry(
                id = entry["id"]?.asString.orEmpty(),
                path = path,
                name = meta?.get("name")?.asString,
                createdAt = meta?.get("createdAt")?.asLong,
                starred = meta?.get("starred")?.asBoolean ?: false,
            )
        }
    }

    private fun parseResources(manifest: JsonObject): List<ResourceEntry> {
        return manifest["resources"]?.jsonArrayOrNull.orEmpty().mapNotNull { element ->
            val resource = element.jsonObjectOrNull ?: return@mapNotNull null
            val path = resource["path"]?.asString ?: return@mapNotNull null
            require(isSafeArchivePath(path)) { "Invalid Chatbox backup resource path: $path" }
            ResourceEntry(
                path = path,
                mimeType = resource["mimeType"]?.asString.orEmpty(),
                kind = resource["kind"]?.asString?.lowercase().orEmpty(),
                storageKeys = resource["originalStorageKeys"]?.jsonArrayOrNull
                    ?.mapNotNull { it.asString }
                    .orEmpty(),
            )
        }
    }

    private fun parseForks(forks: JsonObject?): Map<String, MessageFork> {
        return forks.orEmpty().mapNotNull { (anchorId, element) ->
            val fork = element.jsonObjectOrNull ?: return@mapNotNull null
            val lists = fork["lists"]?.jsonArrayOrNull?.map { listElement ->
                listElement.jsonObjectOrNull?.get("messages")?.jsonArrayOrNull
                    ?.mapNotNull { it.jsonObjectOrNull }
                    .orEmpty()
            }.orEmpty()
            anchorId to MessageFork(
                position = (fork["position"]?.asInt ?: lists.lastIndex).coerceAtLeast(0),
                lists = lists,
            )
        }.toMap()
    }

    private fun ZipFile.readJsonObject(path: String, required: Boolean): JsonObject? {
        require(isSafeArchivePath(path)) { "Invalid Chatbox backup entry path: $path" }
        val entry = getEntry(path)
        if (entry == null) {
            if (required) error("Invalid Chatbox backup: $path not found")
            return null
        }
        require(!entry.isDirectory && entry.hasAllowedSize(MAX_JSON_ENTRY_SIZE)) {
            "Invalid Chatbox backup JSON entry: $path"
        }
        val json = readEntryBytes(entry, MAX_JSON_ENTRY_SIZE).toString(StandardCharsets.UTF_8)
        return JsonInstant.parseToJsonElement(json).jsonObject
    }

    private fun ZipFile.readEntryBytes(entry: ZipEntry, maxSize: Long): ByteArray {
        require(entry.hasAllowedSize(maxSize)) { "Chatbox backup entry is too large: ${entry.name}" }
        val initialSize = entry.size.coerceAtMost(8192).toInt()
        return getInputStream(entry).use { input ->
            ByteArrayOutputStream(initialSize).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= maxSize) { "Chatbox backup entry is too large: ${entry.name}" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun ZipEntry.hasAllowedSize(maxSize: Long): Boolean = size in 0..maxSize

    private fun isSafeArchivePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) return false
        return path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun normalizeBaseUrl(apiHost: String, suffix: String, fallback: String): String {
        val host = apiHost.trim().trimEnd('/')
        if (host.isBlank()) return fallback
        return if (host.endsWith(suffix)) host else "$host$suffix"
    }

    private fun openAiCompatibleBaseUrl(providerKey: String): String? = when (providerKey) {
        "openai" -> ProviderSetting.OpenAI().baseUrl
        "deepseek" -> "https://api.deepseek.com/v1"
        "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        "moonshot", "moonshot-cn" -> "https://api.moonshot.cn/v1"
        "openrouter" -> "https://openrouter.ai/api/v1"
        "siliconflow" -> "https://api.siliconflow.cn/v1"
        "groq" -> "https://api.groq.com/openai/v1"
        "xai" -> "https://api.x.ai/v1"
        "mistral" -> "https://api.mistral.ai/v1"
        "perplexity" -> "https://api.perplexity.ai"
        else -> null
    }

    private fun providerDisplayName(providerKey: String): String = when (providerKey) {
        "openai" -> "OpenAI"
        "claude", "anthropic" -> "Claude"
        "gemini", "google" -> "Gemini"
        "deepseek" -> "DeepSeek"
        "qwen" -> "Qwen"
        "moonshot", "moonshot-cn" -> "Moonshot CN"
        "openrouter" -> "OpenRouter"
        "siliconflow" -> "SiliconFlow"
        "groq" -> "Groq"
        "xai" -> "xAI"
        "mistral" -> "Mistral"
        "perplexity" -> "Perplexity"
        else -> providerKey
    }

    private fun providerIdentity(provider: ProviderSetting): String = when (provider) {
        is ProviderSetting.OpenAI -> "openai|${provider.baseUrl.trimEnd('/')}|${provider.apiKey}"
        is ProviderSetting.Google -> "google|${provider.baseUrl.trimEnd('/')}|${provider.apiKey}"
        is ProviderSetting.Claude -> "claude|${provider.baseUrl.trimEnd('/')}|${provider.apiKey}"
    }

    private fun ProviderSetting.providerTypeName(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "gemini"
        is ProviderSetting.Claude -> "claude"
    }

    private fun String.toMessageRole(): MessageRole? = when (this) {
        "system" -> MessageRole.SYSTEM
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        else -> null
    }

    private fun millisToLocalDateTime(timestamp: Long) =
        KotlinInstant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())

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

    private val JsonElement.asBoolean: Boolean?
        get() = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}

data class ChatboxImageResource(
    val storageKey: String,
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
)

data class ChatboxStreamingImportResult(
    val providers: List<ProviderSetting>,
    val parsedConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
    val hasConversationSystemPrompt: Boolean,
)

private data class SessionEntry(
    val id: String,
    val path: String,
    val name: String?,
    val createdAt: Long?,
    val starred: Boolean,
)

private data class ResourceEntry(
    val path: String,
    val mimeType: String,
    val kind: String,
    val storageKeys: List<String>,
)

private data class MessageFork(
    val position: Int,
    val lists: List<List<JsonObject>>,
)

private data class ImportCounters(
    var importedImageParts: Int = 0,
    var skippedImageParts: Int = 0,
    var skippedEmptyMessages: Int = 0,
    var skippedForkMessages: Int = 0,
    var skippedSessions: Int = 0,
    var hasConversationSystemPrompt: Boolean = false,
)
