package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `imports text reasoning searches and attachments with session metadata`() = runBlocking {
        val image = byteArrayOf(1, 2, 3)
        val backup = archive(
            session(
                """
                {"id":1,"parent_id":null,"role":"USER","fragments":[
                  {"type":"FILE","files":[{"file_name":"图片.png","export_path":"chat_sessions/s_files/图片.png","is_image":true}]},
                  {"type":"REQUEST","content":"Describe this"}
                ]},
                {"id":2,"parent_id":1,"role":"ASSISTANT","inserted_at":"2026-11-14T05:56:00Z","fragments":[
                  {"type":"THINK","content":"Thinking","elapsed_secs":1.5},
                  {"type":"TOOL_SEARCH","status":"FINISHED","queries":["query"],"results":[{"url":"https://example.com","title":"Source"}]},
                  {"type":"TOOL_OPEN","status":"TIMEOUT","url":"https://example.com","title":"Source"},
                  {"type":"RESPONSE","content":"Answer"},
                  {"type":"TIP","content":"Notice"}
                ]}
                """.trimIndent(),
                current = 2,
            ),
            attachments = mapOf("chat_sessions/s_files/图片.png" to image),
        )
        val conversations = mutableListOf<Conversation>()
        val attachments = mutableListOf<DeepSeekAttachment>()
        val assistant = Uuid.random()
        val result = DeepSeekImporter.importStreaming(
            backup,
            assistant,
            saveAttachment = {
                attachments += it
                "file:///image.png"
            },
            onConversation = { conversations += it },
        )
        assertEquals(DeepSeekImportResult(1, 0, 0, 0), result)
        assertArrayEquals(image, attachments.single().bytes)
        assertEquals("图片.png", attachments.single().fileName)
        assertEquals("image/png", attachments.single().mimeType)
        val conversation = conversations.single()
        assertEquals(assistant, conversation.assistantId)
        assertEquals("Test session", conversation.title)
        assertTrue(conversation.isPinned)
        assertEquals(Instant.parse("2026-11-14T05:55:54Z"), conversation.createAt)
        assertEquals(Instant.parse("2026-11-14T05:56:01Z"), conversation.updateAt)
        assertEquals(MessageRole.USER, conversation.currentMessages.first().role)
        assertEquals(
            "file:///image.png",
            (conversation.currentMessages.first().parts.first() as UIMessagePart.Image).url
        )
        val answer = conversation.currentMessages.last()
        val reasoning = answer.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("Thinking", reasoning.reasoning)
        assertEquals(1500, (reasoning.finishedAt!! - reasoning.createdAt).inWholeMilliseconds.toInt())
        assertEquals(listOf("Answer", "Notice"), answer.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertEquals(1, answer.annotations.size)
        val tools = answer.parts.filterIsInstance<UIMessagePart.ServerTool>()
        assertEquals(listOf(ServerToolStatus.COMPLETED, ServerToolStatus.FAILED), tools.map { it.status })
        assertTrue(tools.all { it.isFinished })
        assertTrue(answer.getTools().isEmpty())
    }

    @Test
    fun `imports only the current branch without alternatives or other continuations`() = runBlocking {
        val backup = archive(session(
            listOf(
                message(1, null, "USER", "Question"),
                message(2, 1, "ASSISTANT", "Old answer"),
                message(3, 2, "USER", "Old followup"),
                message(4, 3, "ASSISTANT", "Old conclusion"),
                message(5, 1, "ASSISTANT", "Current answer"),
                message(6, 5, "USER", "Current followup"),
                message(7, 6, "ASSISTANT", "Current conclusion"),
                message(8, 1, "ASSISTANT", "Leaf alternative"),
            ).joinToString(),
            current = 7,
        ))
        val first = mutableListOf<Conversation>()
        val assistant = Uuid.random()
        DeepSeekImporter.importStreaming(backup, assistant, onConversation = { first += it })
        assertEquals(1, first.size)
        assertEquals(
            listOf("Question", "Current answer", "Current followup", "Current conclusion"),
            first[0].currentMessages.map { it.toText() },
        )
        assertTrue(first.single().messageNodes.all { it.messages.size == 1 && it.selectIndex == 0 })
        assertEquals("Test session", first.single().title)
        val nodeIds = first.flatMap { it.messageNodes }.map { it.id }
        assertEquals(nodeIds.size, nodeIds.distinct().size)
        val messageIds = first.flatMap { it.messageNodes }.flatMap { it.messages }.map { it.id }
        assertEquals(messageIds.size, messageIds.distinct().size)

        val second = mutableListOf<Conversation>()
        DeepSeekImporter.importStreaming(backup, assistant, onConversation = { second += it })
        assertEquals(first, second)
        val skipped = DeepSeekImporter.importStreaming(
            backup,
            assistant,
            shouldImportConversation = { id -> first.none { it.id == id } },
            saveAttachment = { error("Existing conversations must not save attachments") },
            onConversation = { error("Existing conversations must not be overwritten") },
        )
        assertEquals(1, skipped.skippedExistingConversations)
        assertEquals(0, skipped.importedConversations)
    }

    @Test
    fun `does not import descendants or attachments outside the selected branch`() = runBlocking {
        val backup = archive(session(
            listOf(
                message(1, null, "USER", "Question"),
                message(2, 1, "ASSISTANT", "Selected answer"),
                message(3, 2, "USER", "Unselected continuation"),
                """{"id":4,"parent_id":1,"role":"USER","fragments":[{"type":"FILE","files":[
                    {"file_name":"old.png","export_path":"old.png","is_image":true}
                ]}]}""",
            ).joinToString(),
            current = 2,
        ), attachments = mapOf("old.png" to byteArrayOf(1)))
        val conversations = mutableListOf<Conversation>()
        val result = DeepSeekImporter.importStreaming(
            backup,
            Uuid.random(),
            saveAttachment = { error("Must not save attachments from an unselected branch") },
            onConversation = { conversations += it },
        )
        assertEquals(DeepSeekImportResult(1, 0, 0, 0), result)
        assertEquals(listOf("Question", "Selected answer"), conversations.single().currentMessages.map { it.toText() })
    }

    @Test
    fun `retains missing file names and imports documents`() = runBlocking {
        val backup = archive(session(
            """
            {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"FILE","files":[
              {"file_name":"missing.pdf","export_path":null,"is_image":false},
              {"file_name":"not-found.pdf","export_path":"missing.pdf","is_image":false},
              {"file_name":"notes.txt","export_path":"chat_sessions/s_files/notes.txt","is_image":false},
              {"file_name":"C#入门笔记.pdf","export_path":"chat_sessions/s_files/c.pdf","is_image":false}
            ]}]}
            """.trimIndent(),
            current = 1,
        ), attachments = mapOf(
            "chat_sessions/s_files/notes.txt" to "Notes".encodeToByteArray(),
            "chat_sessions/s_files/c.pdf" to "PDF".encodeToByteArray(),
        ))
        val conversations = mutableListOf<Conversation>()
        val attachments = mutableListOf<DeepSeekAttachment>()
        val result = DeepSeekImporter.importStreaming(
            backup,
            Uuid.random(),
            saveAttachment = {
                attachments += it
                "file:///${it.fileName}"
            },
            onConversation = { conversations += it },
        )
        assertEquals(2, result.missingAttachments)
        val message = conversations.single().currentMessages.single()
        assertTrue(message.toText().contains("missing.pdf"))
        assertTrue(message.toText().contains("not-found.pdf"))
        assertEquals(
            listOf("notes.txt", "C#入门笔记.pdf"),
            message.parts.filterIsInstance<UIMessagePart.Document>().map { it.fileName },
        )
        assertEquals(listOf("text/plain", "application/pdf"), attachments.map { it.mimeType })
    }

    @Test
    fun `rejects unsupported formats and invalid parent graphs`() {
        val invalidVersion = archive(session(message(1, null, "USER", "Hello"), 1), version = 2)
        val cyclic = archive(session(message(1, 2, "USER", "One") + "," + message(2, 1, "ASSISTANT", "Two"), 2))
        val missingParent = archive(session(message(1, 99, "USER", "One"), 1))
        val missingCurrent = archive(session(message(1, null, "USER", "One"), 99))
        listOf(invalidVersion, cyclic, missingCurrent).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    DeepSeekImporter.importStreaming(file, Uuid.random(), onConversation = { error("Must not save") })
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                DeepSeekImporter.importStreaming(
                    missingParent,
                    Uuid.random(),
                    onConversation = { error("Must not save") }
                )
            }
        }
    }

    @Test
    fun `skips empty sessions`() = runBlocking {
        val result = DeepSeekImporter.importStreaming(
            archive(session("", 0)),
            Uuid.random(),
            onConversation = { error("Must not save") },
        )
        assertEquals(DeepSeekImportResult(0, 0, 1, 0), result)
    }

    /** Optional local verification; real chat data is never copied into test fixtures. */
    @Test
    fun `imports only current branch textual fragments from a supplied real archive`() = runBlocking {
        val path = System.getenv("DEEPSEEK_IMPORT_TEST_ARCHIVE")
        assumeTrue(!path.isNullOrBlank())
        val file = File(path!!)
        val expectedText = mutableSetOf<String>()
        var sessionCount = 0
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filter {
                it.name.startsWith("chat_sessions/") && it.name.endsWith(".json") &&
                    '/' !in it.name.removePrefix("chat_sessions/")
            }.forEach { entry ->
                sessionCount++
                val session = JsonInstant.parseToJsonElement(
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                )
                val messages = (session.jsonObject["messages"] as JsonArray).associate {
                    val message = it.jsonObject
                    (message["id"] as JsonPrimitive).content to message
                }
                var current = (session.jsonObject["current_message_id"] as? JsonPrimitive)?.contentOrNull
                val visited = mutableSetOf<String>()
                while (current != null) {
                    check(visited.add(current))
                    val message = messages.getValue(current)
                    (message["fragments"] as JsonArray).forEach { fragment ->
                        (fragment.jsonObject["content"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() }?.let(expectedText::add)
                    }
                    current = (message["parent_id"] as? JsonPrimitive)?.contentOrNull
                }
            }
        }
        val conversations = mutableListOf<Conversation>()
        var attachments = 0
        val result = DeepSeekImporter.importStreaming(
            file,
            Uuid.random(),
            saveAttachment = {
                assertTrue(it.bytes.isNotEmpty())
                attachments++
                "file:///attachment-$attachments"
            },
            onConversation = { conversations += it },
        )
        val actualText = conversations.flatMap { it.messageNodes }.flatMap { it.messages }.flatMap { it.parts }
            .mapNotNull {
                when (it) {
                    is UIMessagePart.Text -> it.text.takeUnless { text ->
                        text.startsWith("[Attachment missing from the export or unable to be imported:")
                    }
                    is UIMessagePart.Reasoning -> it.reasoning
                    else -> null
                }
            }.toSet()
        assertEquals(sessionCount, conversations.size)
        assertTrue(conversations.all { conversation -> conversation.messageNodes.all { it.messages.size == 1 } })
        assertTrue("Only current branch textual fragments must be imported", actualText == expectedText)
        val nodeIds = conversations.flatMap { it.messageNodes }.map { it.id }
        assertEquals(nodeIds.size, nodeIds.distinct().size)
        println("DeepSeek archive: $sessionCount sessions, $result, $attachments saved attachments")
    }

    private fun message(id: Int, parent: Int?, role: String, text: String): String =
        """{"id":$id,"parent_id":$parent,"role":"$role","fragments":[{"type":"RESPONSE","content":"$text"}]}"""

    private fun session(messages: String, current: Int): String = """
        {"id":"s","title":"Test session","pinned":true,"current_message_id":$current,
         "inserted_at":"2026-11-14T05:55:54Z","updated_at":"2026-11-14T05:56:01Z","messages":[$messages]}
    """.trimIndent()

    private fun archive(session: String, version: Int = 3, attachments: Map<String, ByteArray> = emptyMap()): File {
        val file = temporaryFolder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "manifest.json" to """{"export_format_version":$version}""".encodeToByteArray(),
                "chat_sessions/s.json" to session.encodeToByteArray(),
            ) + attachments
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
