package me.rerere.ai.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
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
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Issue #356 finding #3: a single assistant message can carry both an approval-gated tool and an
 * `Auto` (no-approval) tool. The approval barrier pauses the turn; after the user approves, the
 * resume branch must execute BOTH the now-approved tool AND the `Auto` sibling. The old resume
 * predicate filtered on `canResumeExecution`, which is false for `Auto`
 * ([ToolApprovalState.Auto] is deliberately not "resumable"), so the `Auto` sibling was dropped and
 * left permanently unexecuted. The fix filters on `!isExecuted && approvalState !is Pending`.
 *
 * This drives the real turn loop: it hands [ChatTurnRuntime.run] a resume-state message (the
 * approval tool already `Approved`, the sibling still `Auto`, neither executed), which puts the loop
 * straight into the resume branch. A regression that restores the `canResumeExecution` filter leaves
 * the `Auto` sibling with empty output and reddens the sibling-executed assertion.
 */
class ChatTurnResumeAutoSiblingTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "fake")
    private val model = Model(modelId = "fake-model", displayName = "Fake", id = Uuid.random())

    private class FixedRuntimeClock : RuntimeClock {
        override fun now(): Instant = Instant.parse("2026-01-02T03:04:05Z")
        override fun timeZone(): TimeZone = TimeZone.UTC
    }

    /** Emits one scripted continuation (the model's reply to the tool results), then stops. */
    private class ContinuationProvider(private val reply: List<MessageChunk>) : Provider<ProviderSetting> {
        var generationCalls = 0
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
        ): Flow<MessageChunk> {
            generationCalls++
            return flow { reply.forEach { emit(it) } }
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

    private inner class FixedResolver(private val impl: Provider<ProviderSetting>) : ModelProviderResolver {
        override fun findModel(modelId: Uuid, turn: TurnConfig): Model? = model
        override fun findProvider(model: Model, turn: TurnConfig): ProviderSetting? = provider
        override fun provider(setting: ProviderSetting): Provider<*> = impl
    }

    private val noopMemoryWriter = object : MemoryWriter {
        override suspend fun add(scope: MemoryScope, content: String): AssistantMemory = AssistantMemory(0, content)
        override suspend fun update(id: Int, content: String): AssistantMemory = AssistantMemory(id, content)
        override suspend fun delete(id: Int) {}
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

    private fun assistant(): AssistantConfig = AssistantConfig(
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

    private fun runtime(impl: Provider<ProviderSetting>): ChatTurnRuntime = ChatTurnRuntime(
        json = json,
        resolver = FixedResolver(impl),
        memoryWriter = noopMemoryWriter,
        conversationReader = emptyReader,
        clock = FixedRuntimeClock(),
        logSink = RecordingLogSink(),
        generationLog = RuntimeGenerationLog { _, _, _, _ -> },
        hookDispatcher = null,
    )

    private fun textChunk(text: String): MessageChunk = MessageChunk(
        id = "test",
        model = "fake-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun toolCall(id: String, name: String, approvalState: ToolApprovalState): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = name,
            input = "{}",
            output = emptyList(),
            approvalState = approvalState,
        ).also { it.finished = true }

    @Test
    fun `resume executes the approved tool and its Auto sibling exactly once`() {
        var dangerCalls = 0
        var autoCalls = 0
        val tools = listOf(
            Tool(name = "dangerous", description = "", needsApproval = true, execute = {
                dangerCalls++; listOf(UIMessagePart.Text("danger done"))
            }),
            Tool(name = "get_time", description = "", needsApproval = false, execute = {
                autoCalls++; listOf(UIMessagePart.Text("12:00"))
            }),
        )
        // Resume-state seed: the approval tool already approved by the user, the Auto sibling still
        // Auto, neither executed — exactly the message the loop re-enters after an approval pause.
        val resumeAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                toolCall("call_danger", "dangerous", ToolApprovalState.Approved),
                toolCall("call_time", "get_time", ToolApprovalState.Auto),
            ),
        )
        val seed = listOf(UIMessage.user("do both"), resumeAssistant)
        val captured = ContinuationProvider(listOf(textChunk("all done")))

        val messages = runBlocking {
            runtime(captured).run(
                turn = TurnConfig(defaultModelId = model.id, providers = listOf(provider), assistants = emptyList()),
                model = model,
                messages = seed,
                assistant = assistant(),
                transforms = identityTransforms,
                tools = tools,
            ).toList().filterIsInstance<GenerationChunk.Messages>().last().messages
        }

        val resultTools = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
        val danger = resultTools.single { it.toolName == "dangerous" }
        val auto = resultTools.single { it.toolName == "get_time" }

        // The approved tool executes (both old and new code did this).
        assertTrue("approved tool executed", danger.isExecuted)
        assertEquals("approved tool executed exactly once", 1, dangerCalls)
        // The Auto sibling executes too — the fix. Old canResumeExecution filter dropped it.
        assertTrue("Auto sibling executed on resume (issue #356 #3)", auto.isExecuted)
        assertEquals("Auto sibling executed exactly once", 1, autoCalls)
        assertEquals("12:00", (auto.output.single() as UIMessagePart.Text).text)
    }
}
