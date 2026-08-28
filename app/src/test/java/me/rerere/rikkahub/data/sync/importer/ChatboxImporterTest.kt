package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class ChatboxImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `imports backup v2 providers resources and message forks`() = runBlocking {
        val backup = temporaryFolder.newFile("chatbox.zip")
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        ZipOutputStream(backup.outputStream()).use { zip ->
            zip.writeEntry(
                "manifest.json",
                """
                {
                  "format": "chatbox-backup",
                  "formatVersion": 2,
                  "data": {"settings": {"path": "settings.json"}},
                  "sessions": [{
                    "id": "test-session",
                    "path": "sessions/test-session/session.json",
                    "meta": {"name": "Manifest title", "createdAt": 1000, "starred": true}
                  }],
                  "resources": [{
                    "id": "resource-1",
                    "path": "sessions/test-session/resources/resource-1.png",
                    "mimeType": "image/png",
                    "kind": "image",
                    "originalStorageKeys": ["picture:test"]
                  }]
                }
                """.trimIndent().encodeToByteArray(),
            )
            zip.writeEntry(
                "settings.json",
                """
                {
                  "providers": {
                    "deepseek": {
                      "apiKey": "test-key",
                      "models": [{
                        "modelId": "deepseek-test",
                        "nickname": "DeepSeek Test",
                        "capabilities": ["vision", "tool_use", "reasoning"]
                      }]
                    }
                  }
                }
                """.trimIndent().encodeToByteArray(),
            )
            zip.writeEntry(
                "sessions/test-session/session.json",
                """
                {
                  "id": "test-session",
                  "name": "Session name",
                  "threadName": "Thread title",
                  "settings": {"provider": "deepseek", "modelId": "deepseek-test"},
                  "messages": [
                    {"id": "system", "role": "system", "contentParts": [{"type": "text", "text": "Be helpful"}]},
                    {"id": "user", "role": "user", "timestamp": 2000, "contentParts": [
                      {"type": "text", "text": "Describe this"},
                      {"type": "image", "storageKey": "picture:test"}
                    ]},
                    {"id": "current", "role": "assistant", "timestamp": 3000, "aiProvider": "deepseek", "contentParts": [
                      {"type": "text", "text": "Current answer"}
                    ]}
                  ],
                  "messageForksHash": {
                    "user": {
                      "position": 1,
                      "lists": [
                        {"id": "old-fork", "messages": [{"id": "alternative", "role": "assistant", "timestamp": 2500, "contentParts": [{"type": "text", "text": "Alternative answer"}]}]},
                        {"id": "current-fork", "messages": []}
                      ]
                    }
                  }
                }
                """.trimIndent().encodeToByteArray(),
            )
            zip.writeEntry("sessions/test-session/resources/resource-1.png", imageBytes)
        }

        var importedConversation: Conversation? = null
        var savedImage: ChatboxImageResource? = null
        val result = ChatboxImporter.importStreaming(
            file = backup,
            assistantId = Uuid.random(),
            providers = emptyList(),
            saveImage = { resource ->
                savedImage = resource
                "file:///imported/resource-1.png"
            },
            onConversation = { importedConversation = it },
        )

        assertEquals(1, result.providers.size)
        assertEquals(1, result.parsedConversations)
        assertEquals(1, result.importedImageParts)
        assertEquals(0, result.skippedImageParts)
        assertArrayEquals(imageBytes, savedImage?.bytes)

        val conversation = assertNotNull(importedConversation).let { importedConversation!! }
        assertEquals("Thread title", conversation.title)
        assertEquals("Be helpful", conversation.customSystemPrompt)
        assertTrue(conversation.isPinned)
        assertEquals(2, conversation.messageNodes.size)
        assertTrue(conversation.messageNodes[0].messages.single().parts[1] is UIMessagePart.Image)

        val assistantNode = conversation.messageNodes[1]
        assertEquals(2, assistantNode.messages.size)
        assertEquals(1, assistantNode.selectIndex)
        assertEquals("Alternative answer", (assistantNode.messages[0].parts.single() as UIMessagePart.Text).text)
        assertEquals("Current answer", (assistantNode.currentMessage.parts.single() as UIMessagePart.Text).text)
        assertNotNull(assistantNode.currentMessage.modelId)
    }

    private fun ZipOutputStream.writeEntry(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }
}
