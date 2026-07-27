package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

data class LocalJsonImportResult(
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedMemories: Int,
    val hasSystemPrompt: Boolean,
)

object LocalJsonImporter {
    suspend fun import(
        file: File,
        assistantId: Uuid,
        conversationRepo: ConversationRepository,
        memoryRepo: MemoryRepository,
    ): LocalJsonImportResult {
        val root = JsonInstant.parseToJsonElement(file.readText()).jsonObject

        val aiCharacter = root["aiCharacter"]?.jsonObjectOrNull
        val conversations = root["conversations"]?.jsonArrayOrNull ?: emptyList()
        val memories = root["memories"]?.jsonArrayOrNull ?: emptyList()

        val hasSystemPrompt = aiCharacter?.get("systemPrompt")?.jsonPrimitive?.contentOrNull?.isNotBlank() == true

        var importedConversations = 0
        var skippedExistingConversations = 0

        for (convElement in conversations) {
            val conv = convElement.jsonObject
            val convId = conv["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val stableId = stableUuid("localjson:conv:$convId")

            if (conversationRepo.existsConversationById(stableId)) {
                skippedExistingConversations++
                continue
            }

            val title = conv["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: convId
            val messages = conv["messages"]?.jsonArrayOrNull ?: continue

            var minTimestamp: java.time.Instant? = null
            var maxTimestamp: java.time.Instant? = null

            val nodes = messages.mapNotNull { msgElement ->
                val msg = msgElement.jsonObject
                val speaker = msg["speaker"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val text = msg["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val timestamp = msg["timestamp"]?.jsonPrimitive?.contentOrNull?.let { parseTimestamp(it) }

                val role = when (speaker) {
                    "ai" -> MessageRole.ASSISTANT
                    "user" -> MessageRole.USER
                    else -> return@mapNotNull null
                }

                val javaInstant = timestamp ?: java.time.Instant.now()
                if (minTimestamp == null || javaInstant < minTimestamp) minTimestamp = javaInstant
                if (maxTimestamp == null || javaInstant > maxTimestamp) maxTimestamp = javaInstant

                val messageId = stableUuid("localjson:msg:$convId:${msg.hashCode()}")
                val nodeId = stableUuid("localjson:node:$convId:${msg.hashCode()}")

                MessageNode(
                    id = nodeId,
                    messages = listOf(
                        UIMessage(
                            id = messageId,
                            role = role,
                            parts = listOf(UIMessagePart.Text(text)),
                            createdAt = KotlinInstant.fromEpochMilliseconds(javaInstant.toEpochMilli())
                                .toLocalDateTime(TimeZone.currentSystemDefault()),
                        )
                    ),
                    selectIndex = 0,
                )
            }

            if (nodes.isEmpty()) continue

            conversationRepo.insertConversation(
                Conversation(
                    id = stableId,
                    assistantId = assistantId,
                    title = title,
                    messageNodes = nodes,
                    createAt = minTimestamp ?: java.time.Instant.now(),
                    updateAt = maxTimestamp ?: java.time.Instant.now(),
                )
            )
            importedConversations++
        }

        var importedMemories = 0
        for (memElement in memories) {
            val content = memElement.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            memoryRepo.addMemory(assistantId.toString(), content)
            importedMemories++
        }

        return LocalJsonImportResult(
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedMemories = importedMemories,
            hasSystemPrompt = hasSystemPrompt,
        )
    }

    fun extractSystemPrompt(file: File): String? {
        val root = JsonInstant.parseToJsonElement(file.readText()).jsonObject
        return root["aiCharacter"]?.jsonObjectOrNull
            ?.get("systemPrompt")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseTimestamp(text: String): java.time.Instant? {
        return runCatching {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        }.getOrElse {
            runCatching { java.time.Instant.parse(text) }.getOrNull()
        }
    }

    private fun stableUuid(value: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString())

    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject

    private val JsonElement.jsonArrayOrNull: JsonArray?
        get() = this as? JsonArray
}
