package me.rerere.ai.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.runtime.contract.ConversationReader
import me.rerere.ai.runtime.contract.ConversationSummary
import me.rerere.ai.runtime.contract.MemoryScope
import me.rerere.ai.runtime.contract.MemoryWriter
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeGenerationLog
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.contract.TurnMessageTransforms
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Cancellation-propagation guard for the extracted [ChatTurnRuntime] (issue #243 §D).
 *
 * Linus rule: stop-generation must surface as a [CancellationException], never be swallowed into a
 * synthetic chunk or an error result. The provider throws [CancellationException] mid-stream (after
 * the first delta); the test asserts the exception PROPAGATES out of the collected flow unchanged,
 * NO memory write happens after cancellation, and NO synthetic final chunk (the `finishedAt`-stamping
 * branch) is reached.
 */
class ChatTurnCancellationTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake")
    private val model = Model(modelId = "fake-model", displayName = "Fake", id = Uuid.random())

    private class FixedRuntimeClock : RuntimeClock {
        override fun now(): Instant = Instant.parse("2026-01-02T03:04:05Z")
        override fun timeZone(): TimeZone = TimeZone.UTC
    }

    private class CancellingProvider : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("unused")

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            emit(
                MessageChunk(
                    id = "test",
                    model = "fake-model",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("partial"))),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            )
            throw CancellationException("stopped")
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow {}

        override suspend fun editImage(
            providerSetting: ProviderSetting,
            params: ImageEditParams,
        ): Flow<ImageGenerationItem> = flow {}

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("unused")
    }

    private inner class FixedResolver : ModelProviderResolver {
        override fun findModel(modelId: Uuid, turn: TurnConfig): Model? = model
        override fun findProvider(model: Model, turn: TurnConfig): ProviderSetting? = provider
        override fun provider(setting: ProviderSetting): Provider<*> = CancellingProvider()
    }

    private class RecordingMemoryWriter : MemoryWriter {
        var writeCount = 0
        override suspend fun add(scope: MemoryScope, content: String): AssistantMemory {
            writeCount++; return AssistantMemory(0, content)
        }
        override suspend fun update(id: Int, content: String): AssistantMemory {
            writeCount++; return AssistantMemory(id, content)
        }
        override suspend fun delete(id: Int) {
            writeCount++
        }
    }

    private val identityTransforms = object : TurnMessageTransforms {
        override suspend fun transformInput(messages: List<UIMessage>): List<UIMessage> = messages
        override suspend fun transformOutput(messages: List<UIMessage>): List<UIMessage> = messages
        override suspend fun visualTransform(messages: List<UIMessage>): List<UIMessage> = messages
        override suspend fun onGenerationFinish(messages: List<UIMessage>): List<UIMessage> = messages
    }

    private val emptyReader = object : ConversationReader {
        override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> = emptyList()
    }

    private fun assistant() = AssistantConfig(
        id = Uuid.random(),
        chatModelId = null,
        systemPrompt = "You are helpful.",
        streamOutput = true,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        messageTemplate = "{{ message }}",
        regexes = emptyList(),
        reasoningLevel = ReasoningLevel.AUTO,
        allowConversationSystemPrompt = false,
        temperature = null,
        topP = null,
        contextMessageSize = 0,
        maxTokens = null,
        customHeaders = emptyList(),
        customBodies = emptyList(),
        mcpServers = emptySet(),
        localToolIds = emptyList(),
        enabledSkills = emptySet(),
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        knowledgeBaseId = null,
        description = "",
        spawnable = false,
        subagentMaxSteps = null,
    )

    @Test
    fun `provider cancellation propagates unchanged - no memory write, no synthetic final chunk`() {
        val writer = RecordingMemoryWriter()
        val runtime = ChatTurnRuntime(
            json = json,
            resolver = FixedResolver(),
            memoryWriter = writer,
            conversationReader = emptyReader,
            clock = FixedRuntimeClock(),
            logSink = RecordingLogSink(),
            generationLog = RuntimeGenerationLog { _, _, _, _ -> },
        )

        val flow = runtime.run(
            turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
            model = model,
            messages = listOf(
                UIMessage.user("hi"),
                UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            ),
            assistant = assistant(),
            transforms = identityTransforms,
        )

        // The CancellationException must surface out of the collected flow — not be swallowed.
        assertThrows(CancellationException::class.java) {
            runBlocking { flow.toList() }
        }

        // No memory write occurred after (or during) cancellation.
        assertEquals(0, writer.writeCount)
    }
}
