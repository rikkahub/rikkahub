package me.rerere.rikkahub.skills

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * Phase 19B — run a single skill against a user-supplied prompt in isolation, harvest the
 * model's final text reply, return.
 *
 * The runner:
 *  1. reads the skill's body via [SkillManager.readSkillBody] (or the test-injected
 *     [skillBodyReader] seam)
 *  2. creates a fresh, ephemeral conversation under the user's currently-selected assistant
 *  3. registers the conversation id in [HeadlessConversations] BEFORE [ChatService.sendMessage]
 *     fires (so the sub-agent recursion guard sees this run as headless and per-tool approval
 *     auto-grants for tools the user has Always-Allowed)
 *  4. dispatches the test prompt + the skill body inlined as the user message
 *  5. waits up to [timeoutMs] (default 2 min) for the generation flow to settle
 *  6. harvests the last assistant message's text + image parts
 *  7. unregisters the conversation, deletes it from Room, drops the session
 *
 * The skill body is inlined directly into the test prompt (rather than relying on the
 * `use_skill` tool surface) so the tester works regardless of whether the skill is in
 * `assistant.enabledSkills` — it's a pure "what would this prompt + this body produce"
 * smoke test.
 *
 * Hard timeout: 2 minutes by default (overridable for tests). If the generation hasn't
 * settled by then, the run returns [TestRunState.Error] with code `tester_timeout` and
 * lets the underlying generation continue (cancellation is best-effort — ChatService's
 * own session lifecycle eventually releases it).
 *
 * Testability seams: the [Driver] interface abstracts everything the runner needs from
 * the `ChatService` + `ConversationRepository` + `SettingsStore` triplet. JVM tests
 * supply a fake driver; production wires through [defaultDriver] which delegates to the
 * Koin-provided real services. This avoids pulling Robolectric or a mocking framework
 * into the test module just for tester coverage.
 */
class SkillTestRunner(
    private val driver: Driver,
    private val skillBodyReader: (String) -> String?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** Production Koin convenience constructor — equivalent to passing [defaultDriver]. */
    constructor(
        chatService: ChatService,
        skillManager: SkillManager,
        conversationRepo: ConversationRepository,
        settingsStore: SettingsStore,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) : this(
        driver = defaultDriver(chatService, conversationRepo, settingsStore),
        skillBodyReader = { name -> skillManager.readSkillBody(name) },
        timeoutMs = timeoutMs,
    )

    companion object {
        private const val TAG = "SkillTestRunner"
        const val DEFAULT_TIMEOUT_MS: Long = 2 * 60 * 1_000L

        /** Wire the production [Driver] over the real ChatService + Repository + Settings. */
        fun defaultDriver(
            chatService: ChatService,
            conversationRepo: ConversationRepository,
            settingsStore: SettingsStore,
        ): Driver = object : Driver {
            override suspend fun currentAssistantId(): Uuid =
                settingsStore.settingsFlow.first().getCurrentAssistant().id

            override suspend fun startConversation(conv: Conversation) {
                conversationRepo.insertConversation(conv)
                chatService.initializeConversation(conv.id)
            }

            override fun send(conv: Conversation, parts: List<UIMessagePart>) {
                chatService.sendMessage(conv.id, parts)
            }

            override suspend fun awaitGenerationDone(conversationId: Uuid, timeoutMs: Long): Boolean {
                val completed: Unit? = withTimeoutOrNull(timeoutMs) {
                    chatService.getGenerationJobStateFlow(conversationId).first { it == null }
                    Unit
                }
                return completed != null
            }

            override suspend fun harvest(conversationId: Uuid): HarvestResult {
                val conv = conversationRepo.getConversationById(conversationId)
                    ?: return HarvestResult("", emptyList())
                val selected = conv.messageNodes.mapNotNull { node ->
                    node.messages.getOrNull(node.selectIndex)
                }
                val lastAssistant = selected.lastOrNull {
                    it.role.name.equals("assistant", ignoreCase = true)
                } ?: return HarvestResult("", emptyList())
                val text = lastAssistant.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                val images = lastAssistant.parts
                    .filterIsInstance<UIMessagePart.Image>()
                    .map { it.url }
                return HarvestResult(text, images)
            }

            override suspend fun cleanup(conv: Conversation) {
                runCatching { chatService.dropSession(conv.id) }
                runCatching { conversationRepo.deleteConversation(conv) }
            }
        }
    }

    /**
     * Narrow seam over ChatService + ConversationRepository + SettingsStore. Tests supply a
     * fake; production uses [defaultDriver]. Allows pure-JVM coverage without Robolectric.
     */
    interface Driver {
        suspend fun currentAssistantId(): Uuid
        suspend fun startConversation(conv: Conversation)
        fun send(conv: Conversation, parts: List<UIMessagePart>)
        /** Returns true if the generation finished, false if [timeoutMs] elapsed first. */
        suspend fun awaitGenerationDone(conversationId: Uuid, timeoutMs: Long): Boolean
        suspend fun harvest(conversationId: Uuid): HarvestResult
        suspend fun cleanup(conv: Conversation)
    }

    data class HarvestResult(val text: String, val imageUrls: List<String>)

    sealed class TestRunState {
        data object Idle : TestRunState()
        data class Running(val elapsedMs: Long) : TestRunState()
        data class Done(val text: String, val imageUrls: List<String>) : TestRunState()
        data class Error(val error: String, val detail: String?) : TestRunState()
    }

    /**
     * Run the skill once. Returns a cold [Flow] that emits [TestRunState.Running] when the
     * generation kicks off, then exactly one terminal state ([TestRunState.Done] or
     * [TestRunState.Error]). The flow terminates after the terminal state is emitted.
     */
    fun runOnce(skillName: String, prompt: String): Flow<TestRunState> = flow {
        val skillBody = skillBodyReader(skillName)
        if (skillBody.isNullOrBlank()) {
            emit(TestRunState.Error("missing_skill", "skill body could not be read"))
            return@flow
        }
        if (prompt.isBlank()) {
            emit(TestRunState.Error("empty_prompt", "prompt is empty"))
            return@flow
        }

        emit(TestRunState.Running(0L))

        val assistantId = driver.currentAssistantId()
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = assistantId,
            newConversation = true,
        ).copy(title = "[Skill test] $skillName")

        // Persist + register-as-headless BEFORE send. Same pattern as
        // SubAgentEngine.executeRun — the order matters because a tool callback
        // checking HeadlessConversations.isHeadless during dispatch would otherwise
        // race the mark.
        var registered = false
        try {
            driver.startConversation(conv)
            HeadlessConversations.mark(conv.id)
            registered = true

            val composed = buildString {
                appendLine("You are running the skill below in test mode. Apply it to the user prompt and respond as the skill instructs.")
                appendLine()
                appendLine("---- SKILL ($skillName) ----")
                appendLine(skillBody)
                appendLine("---- END SKILL ----")
                appendLine()
                appendLine("User prompt:")
                append(prompt)
            }
            driver.send(conv, listOf(UIMessagePart.Text(composed)))

            val finishedInTime = driver.awaitGenerationDone(conv.id, timeoutMs)
            if (!finishedInTime) {
                emit(TestRunState.Error("tester_timeout", "exceeded ${timeoutMs / 1_000}s cap"))
                return@flow
            }

            val harvested = driver.harvest(conv.id)
            if (harvested.text.isBlank() && harvested.imageUrls.isEmpty()) {
                emit(TestRunState.Error("no_response", "the model returned no text or image parts"))
            } else {
                emit(TestRunState.Done(harvested.text, harvested.imageUrls))
            }
        } catch (t: Throwable) {
            // Log via a runCatching so JVM tests (which don't stub android.util.Log) don't
            // explode. The error envelope below carries the same info to the UI.
            runCatching { Log.w(TAG, "runOnce failed for $skillName", t) }
            emit(TestRunState.Error(t::class.simpleName ?: "unknown", t.message))
        } finally {
            if (registered) {
                HeadlessConversations.unmark(conv.id)
            }
            runCatching { driver.cleanup(conv) }
        }
    }
}
