package me.rerere.rikkahub.ui.pages.backup

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.sync.importer.ChatboxStreamingImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid
import java.io.File

private class FakeChatboxImportRunner(
    private val importedProviders: List<ProviderSetting>,
    private val conversations: List<Conversation>,
    private val failAfterProviders: Throwable? = null,
) : ChatboxImportRunner {
    override suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        providers: List<ProviderSetting>,
        onProvidersImported: suspend (List<ProviderSetting>) -> Unit,
        onConversation: suspend (Conversation) -> Unit,
    ): ChatboxStreamingImportResult {
        onProvidersImported(importedProviders)

        if (failAfterProviders != null) {
            throw failAfterProviders
        }

        for (conversation in conversations) {
            onConversation(conversation)
        }

        return ChatboxStreamingImportResult(
            providers = importedProviders,
            parsedConversations = conversations.size,
            skippedImageParts = 0,
            skippedEmptyMessages = 0,
            hasConversationSystemPrompt = conversations.any { !it.customSystemPrompt.isNullOrBlank() },
        )
    }
}

private fun makeConversation(
    assistantId: Uuid,
    customSystemPrompt: String? = null,
): Conversation {
    return Conversation.ofId(
        id = Uuid.random(),
        assistantId = assistantId,
        messages = emptyList(),
        newConversation = true,
    ).copy(customSystemPrompt = customSystemPrompt)
}

class BackupVMChatboxImportTest {

    @Test
    fun `restoreOrchestrator persists providers before inserting conversations`() = runBlocking {
        val assistantId = Uuid.random()
        val importedProvider = ProviderSetting.OpenAI(apiKey = "imported")
        val conversation = makeConversation(
            assistantId = assistantId,
            customSystemPrompt = null,
        )
        val eventLog = mutableListOf<String>()

        runChatboxImport(
            importer = FakeChatboxImportRunner(
                importedProviders = listOf(importedProvider),
                conversations = listOf(conversation),
            ),
            file = File.createTempFile("chatbox-import-order", ".json"),
            assistantId = assistantId,
            providers = emptyList(),
            persistProviders = { providers ->
                eventLog += "persist ${providers.size}"
            },
            enableSystemPromptGate = {
                eventLog += "gate"
            },
            insertConversation = { conv ->
                eventLog += "insert ${conv.id}"
            },
            conversationExists = { false },
            isSystemPromptEnabled = false,
        )

        assertEquals(
            listOf("persist 1", "insert ${conversation.id}"),
            eventLog,
        )
    }

    @Test
    fun `restoreOrchestrator enables system prompt gate before prompt conversation insert`() = runBlocking {
        val assistantId = Uuid.random()
        val conversation = makeConversation(
            assistantId = assistantId,
            customSystemPrompt = "with custom system prompt",
        )
        val eventLog = mutableListOf<String>()

        runChatboxImport(
            importer = FakeChatboxImportRunner(
                importedProviders = emptyList(),
                conversations = listOf(conversation),
            ),
            file = File.createTempFile("chatbox-import-gate", ".json"),
            assistantId = assistantId,
            providers = emptyList(),
            persistProviders = {},
            enableSystemPromptGate = {
                eventLog += "gate"
            },
            insertConversation = {
                eventLog += "insert"
            },
            conversationExists = { false },
            isSystemPromptEnabled = false,
        )

        assertEquals(
            listOf("gate", "insert"),
            eventLog,
        )
    }

    @Test
    fun `restoreOrchestrator does not set system prompt gate for blank prompts`() = runBlocking {
        val assistantId = Uuid.random()
        val conversation = makeConversation(
            assistantId = assistantId,
            customSystemPrompt = " ",
        )
        val eventLog = mutableListOf<String>()

        runChatboxImport(
            importer = FakeChatboxImportRunner(
                importedProviders = emptyList(),
                conversations = listOf(conversation),
            ),
            file = File.createTempFile("chatbox-import-no-gate", ".json"),
            assistantId = assistantId,
            providers = emptyList(),
            persistProviders = {},
            enableSystemPromptGate = {
                eventLog += "gate"
            },
            insertConversation = {
                eventLog += "insert"
            },
            conversationExists = { false },
            isSystemPromptEnabled = false,
        )

        assertEquals(listOf("insert"), eventLog)
        assertFalse(eventLog.contains("gate"))
    }

    @Test
    fun `restoreOrchestrator keeps zero inserts when import fails before conversations`() = runBlocking {
        val assistantId = Uuid.random()
        val existing = ProviderSetting.OpenAI(apiKey = "persist-only")
        var persistedProviders = 0
        var inserted = 0

        val failure = runCatching {
            runChatboxImport(
                importer = FakeChatboxImportRunner(
                    importedProviders = listOf(existing),
                    conversations = listOf(makeConversation(assistantId)),
                    failAfterProviders = IllegalStateException("fail before conversations"),
                ),
                file = File.createTempFile("chatbox-import-fail", ".json"),
                assistantId = assistantId,
                providers = emptyList(),
                persistProviders = { providers ->
                    persistedProviders += providers.size
                },
                enableSystemPromptGate = {
                    throw AssertionError("should not enable gate")
                },
                insertConversation = { inserted++ },
                conversationExists = { false },
                isSystemPromptEnabled = false,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, inserted)
        assertEquals(1, persistedProviders)

        // Expect import failure before first onConversation in this test seam.
        assertEquals("fail before conversations", failure?.message)
    }
}
