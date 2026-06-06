package me.rerere.rikkahub.data.ai

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.service.RikkaAccessibilityService
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"

/**
 * Keys whose string values are sensitive enough that the raw value MUST NOT land in
 * logcat. Tool args land in `Log.i` for debugging; without redaction, `save_ssh_host`'s
 * `private_key` / `password` and `telegram_set_token`'s `token` would print verbatim
 * — readable on debug builds, by other apps holding READ_LOGS on OEM-bugged ROMs, and
 * by `bugreport`/`dumpsys`. The match is case-insensitive against the key name and
 * applies regardless of nesting depth.
 */
private val SECRET_KEY_PATTERN: Regex =
    Regex("(?:^|_)(password|passphrase|secret|token|apikey|api[_-]?key|privatekey|private[_-]?key|key)$",
        RegexOption.IGNORE_CASE)

/**
 * Walk [element] and replace any string primitive whose KEY matches [SECRET_KEY_PATTERN]
 * with the string `"***"`. Numbers, booleans, nulls, and non-secret strings pass through
 * unchanged. Used only for the per-step "executing tool with args" log line.
 */
private fun redactSecrets(element: JsonElement, key: String? = null): JsonElement {
    val isSecret = key != null && SECRET_KEY_PATTERN.containsMatchIn(key)
    return when (element) {
        is JsonPrimitive ->
            if (isSecret && element.isString) JsonPrimitive("***") else element
        is JsonObject -> JsonObject(element.mapValues { (k, v) -> redactSecrets(v, k) })
        is JsonArray -> buildJsonArray { element.forEach { add(redactSecrets(it, key)) } }
    }
}

/**
 * Replace older tool-result `Image` parts with a small text elision so the same JPEGs
 * aren't re-encoded into base64 on every subsequent step. We keep the
 * [IMAGE_KEEP_LAST_N_TOOL_RESULTS] most-recent tool-result-bearing assistant messages
 * verbatim and elide everything older. User uploads (`role=USER`) are NEVER elided —
 * those are real input the model needs to reason over. Assistant-generated images
 * (model image-gen output) are also kept verbatim as those are visible product, not
 * intermediate reasoning state.
 */
private fun List<UIMessage>.ageOldToolImages(): List<UIMessage> {
    var toolResultsWithImagesSeen = 0
    return this.asReversed().map { msg ->
        if (msg.role == MessageRole.USER) return@map msg
        val hasImageInTool = msg.parts.any { p ->
            p is UIMessagePart.Tool && p.output.any { it is UIMessagePart.Image }
        }
        if (!hasImageInTool) return@map msg
        toolResultsWithImagesSeen++
        if (toolResultsWithImagesSeen <= IMAGE_KEEP_LAST_N_TOOL_RESULTS) return@map msg
        val newParts = msg.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                val newOutput = part.output.map { o ->
                    if (o is UIMessagePart.Image) {
                        UIMessagePart.Text(
                            "[image elided — original at ${o.url}; superseded by newer screenshots]"
                        )
                    } else o
                }
                part.copy(output = newOutput)
            } else part
        }
        msg.copy(parts = newParts)
    }.asReversed()
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

private const val TAG_GH_LOOP = "GenHandlerLoop"

/**
 * If the model calls the same tool with the same exact JSON args this many times within a
 * single user turn, we refuse the next execution and inject a "loop_detected" envelope. The
 * threshold is INCLUSIVE of the prior occurrences, so a value of 3 means: first call runs,
 * second call runs, third call runs — fourth identical call is blocked. Picked low enough
 * that runaway loops can't drain the user's API tokens but high enough to allow legitimate
 * retries (a notification key going stale between read and dismiss, etc.).
 */
private const val LOOP_GUARD_REPEAT_THRESHOLD = 3

// The per-turn wall-clock budget was hardcoded here (most recently 10 min). It now lives in
// ToolRuntimeLimits.turnBudgetMs (default 10 min), user-configurable via Settings -> Termux;
// every read site below uses that holder directly.

/**
 * Max number of times the loop guard can trip in a single turn before we force-end the
 * turn entirely. Prevents the "model keeps trying different tools, each gets loop-detected"
 * pattern that produced the 27-step / 141K-token disaster: one trip means the model is
 * confused; six trips means it's not coming back.
 */
private const val MAX_LOOP_GUARD_TRIPS_PER_TURN = 6

/**
 * Number of most-recent tool-result-bearing messages whose `Image` parts are kept
 * verbatim in the prompt. Older tool-result images are replaced with a small text
 * elision so the same JPEG isn't re-encoded into base64 on every step. Without this
 * a screen-automation turn that takes 5 screenshots makes the provider re-pay
 * ~1–2MB × 5 base64 encode + upload on every subsequent step.
 *
 * 2 is the smallest value that lets the model do "look at this screenshot, decide
 * action; take new screenshot, compare" — needs both the previous and the current
 * screenshot in context. Anything older has been superseded.
 */
private const val IMAGE_KEEP_LAST_N_TOOL_RESULTS = 2

/**
 * Some read-only tools measure a real-time signal where re-calling after a TTL is
 * legitimate (battery drains, screens change, sensors update). For these, the loop guard
 * lets identical calls through if the most recent identical call is older than the TTL.
 * Without this, asking the model "what's the battery now?" after a previous reading just
 * regurgitates the stale value and the user has no idea.
 *
 * Tools NOT in this map are treated as side-effecting / idempotent-input: re-calling with
 * identical args is a loop, not a refresh. Add new freshness-sensitive tools here.
 */
private val FRESHNESS_TTL_MS_BY_TOOL: Map<String, Long> = mapOf(
    "get_battery_status" to 30_000L,
    "get_audio_info" to 30_000L,
    "get_telephony_info" to 30_000L,
    "get_wifi_info" to 30_000L,
    "get_storage_info" to 60_000L,
    "get_brightness" to 10_000L,
    "get_volume" to 10_000L,
    "get_location" to 30_000L,
    "get_time_info" to 5_000L,
    "read_sensor" to 5_000L,
    "take_screenshot" to 5_000L,
    "read_window_tree" to 5_000L,
    "list_active_notifications" to 5_000L,
    "list_jobs" to 60_000L,
)

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val systemPromptBuilder: SystemPromptBuilder,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 32,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        // Returns true when the user has pre-approved [toolName] for this turn (e.g.
        // "Allow for this chat" or "Always Allow" granted earlier). When true, the loop
        // below skips the Pending flip and lets the tool execute. ChatService injects the
        // closure that reads ToolApprovalAllowList + ToolApprovalPreferences. Default
        // returns false so callers that don't care still get vanilla approval gating.
        isToolAutoApproved: suspend (toolName: String) -> Boolean = { false },
        // Optional per-call addendum appended to the system prompt. Used by surfaces that
        // need the model to know runtime context (e.g. "you're talking via Telegram, the
        // chat_id is 12345") without polluting the user message body — without this the
        // preamble is replayed in user history every turn, burning ~80 tokens × N turns.
        systemAddendum: String? = null,
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // Replay safety: scan the input messages for tools that were Approved + began
        // execution but never produced output (process killed mid-execute). Without this
        // pass, the loop below would treat them as "Approved, ready to run" and execute
        // them AGAIN on replay — could double-charge a remote, duplicate a message send,
        // re-overwrite a file. Flip them to Denied so the model sees a deterministic
        // envelope and decides whether to retry deliberately.
        var messages: List<UIMessage> = messages.map { msg ->
            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isInterruptedAttempt) {
                    Log.w(TAG, "replay: ${part.toolName} (${part.toolCallId}) had executionStartedAt set with empty output → Denied(interrupted_unknown_outcome)")
                    part.copy(approvalState = ToolApprovalState.Denied(
                        "interrupted_unknown_outcome: a previous attempt to execute this tool started " +
                            "but did not complete (process killed mid-execute). The side effect MAY OR " +
                            "MAY NOT have happened. Verify the target state before retrying — do not " +
                            "blindly re-run the same call."
                    ))
                } else part
            }
            if (newParts == msg.parts) msg else msg.copy(parts = newParts)
        }

        val turnStartMs = android.os.SystemClock.elapsedRealtime()
        var loopGuardTripCount = 0

        for (stepIndex in 0 until maxSteps) {
            // Wall-clock cap: any single user turn that has been running longer than the
            // budget is force-ended, regardless of whether the model wants more steps.
            // This is the second line of defence after maxSteps; without it a model that
            // discovers many distinct tool calls (each within the loop guard) can still
            // run for hours.
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs
            if (elapsedMs > ToolRuntimeLimits.turnBudgetMs) {
                Log.w(TAG, "generateText: wall-clock cap (${ToolRuntimeLimits.turnBudgetMs}ms) hit at step #$stepIndex; force-ending turn")
                break
            }
            // Repeated loop-guard trips mean the model is flailing: it bumps into the
            // guard, picks a different tool, that one also gets guarded, and so on. After
            // N trips we just stop — the model is not going to recover, and every extra
            // step is paid for in tokens.
            if (loopGuardTripCount >= MAX_LOOP_GUARD_TRIPS_PER_TURN) {
                Log.w(TAG, "generateText: loop-guard tripped $loopGuardTripCount times this turn; force-ending")
                break
            }

            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            // Mixed-state guard: if the last message has tools STILL in Pending (waiting
            // on user approval keyboard) but nothing canResumeExecution, the existing
            // path would call generateInternal and start a brand-new assistant turn,
            // orphaning the Pending tool. Bail out instead and let handleToolApproval
            // re-enter when the user taps the keyboard.
            if (pendingTools.isEmpty()) {
                val lastHasPending = messages.lastOrNull()?.parts?.any { p ->
                    p is UIMessagePart.Tool && p.isPending
                } == true
                if (lastHasPending) {
                    Log.i(TAG, "generateText: last message has Pending tools; waiting for approval, not regenerating")
                    break
                }
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                try {
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        systemAddendum = systemAddendum,
                        messages = messages,
                        onUpdateMessages = {
                            messages = it.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                            emit(
                                GenerationChunk.Messages(
                                    messages.visualTransforms(
                                        transformers = outputTransformers,
                                        context = context,
                                        model = model,
                                        assistant = assistant,
                                        settings = settings
                                    )
                                )
                            )
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = toolsInternal,
                        memories = memories ?: emptyList(),
                        stream = assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationModeInjectionIds = conversationModeInjectionIds,
                        conversationLorebookIds = conversationLorebookIds,
                    )
                } catch (t: Throwable) {
                    // CancellationException is honoured verbatim — stopGeneration has its
                    // own cancelToolByUser path that marks tools cancelled. We only need
                    // to handle non-cancel failures here.
                    if (t !is CancellationException) {
                        // Server 5xx, JSON parse failure, OOM during chunk-merge, etc. Without
                        // this transition, any tool already at Auto/Pending in the just-built
                        // assistant message is stranded — the next user turn replays the
                        // conversation with tool parts in an in-between state and downstream
                        // filtering misbehaves. We mark them Denied with a generation_failed
                        // envelope so the shape is deterministic on replay.
                        val lastMsg = messages.lastOrNull()
                        if (lastMsg != null) {
                            val newParts = lastMsg.parts.map { part ->
                                if (part is UIMessagePart.Tool &&
                                    (part.approvalState is ToolApprovalState.Auto ||
                                        part.approvalState is ToolApprovalState.Pending)) {
                                    part.copy(approvalState = ToolApprovalState.Denied(
                                        "generation_failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                                    ))
                                } else part
                            }
                            messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                            emit(GenerationChunk.Messages(messages))
                        }
                    }
                    throw t
                }
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Imperative loop (was .map) so we can call the suspending
                // [isToolAutoApproved] FRESH per-tool, not from a frozen pre-resolved set.
                // Without this, a grant landing between the pre-resolve and the .map
                // (user taps Always-Allow on tool X mid-iteration) gets ignored — X
                // flips to Pending and a duplicate prompt is emitted even though X is
                // now persisted-approved.
                var hasPendingApproval = false
                val updatedTools = ArrayList<UIMessagePart.Tool>(tools.size)
                for (tool in tools) {
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    // HARDLINE check: certain command patterns (rm -rf /, mkfs, shutdown,
                    // fork bomb, …) are blocked unconditionally — even "Always Allow"
                    // can't override. We check BEFORE the auto-approval lookup so a
                    // permanently-allowed termux/ssh tool still can't smuggle one of
                    // these through. Result: tool is marked Denied with the hardline
                    // reason, the regular Denied branch downstream emits an error
                    // envelope to the model without executing.
                    val hardlineReason = me.rerere.rikkahub.data.ai.tools
                        .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                    val transformed = when {
                        hardlineReason != null && tool.approvalState is ToolApprovalState.Auto -> {
                            Log.w(TAG, "hardline-blocked ${tool.toolName}: $hardlineReason")
                            tool.copy(approvalState = ToolApprovalState.Denied(
                                "blocked by safety floor (hardline): $hardlineReason. " +
                                    "This command cannot run via the agent under any " +
                                    "circumstances. If the user genuinely needs it, they " +
                                    "should run it themselves in a terminal outside the agent."
                            ))
                        }
                        // Tool needs approval and state is Auto:
                        toolDef?.needsApproval == true && tool.approvalState is ToolApprovalState.Auto -> {
                            // Fresh per-tool auto-approval check (was a frozen pre-
                            // resolved set). Costs a DataStore.first() per tool but tools
                            // are typically <5 per turn so the latency is negligible, and
                            // freshness matters for the YOLO toggle / mid-iteration grants.
                            if (isToolAutoApproved(tool.toolName)) {
                                tool  // leave as Auto so the executor runs it without prompting
                            } else {
                                hasPendingApproval = true
                                tool.copy(approvalState = ToolApprovalState.Pending)
                            }
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                    updatedTools.add(transformed)
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool.
                        //
                        // Defence-in-depth HARDLINE re-check: the primary check at line ~442
                        // only runs when approvalState is Auto (the generation step that just
                        // proposed the tool). On the resume path (pendingTools branch above)
                        // tools arrive here with state=Approved and skip that block entirely.
                        // Re-check here so that a hardline-matched tool persisted in Approved
                        // state from an old DB row (pre-hardline schema, direct DB edit) can
                        // never execute via the resume path.
                        val resumeHardlineReason = me.rerere.rikkahub.data.ai.tools
                            .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                        if (resumeHardlineReason != null) {
                            Log.w(TAG, "generateText: resume-path hardline re-check blocked ${tool.toolName}: $resumeHardlineReason")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive(
                                                "blocked by safety floor (hardline): $resumeHardlineReason. " +
                                                    "This command cannot run via the agent under any circumstances."
                                            ))
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }

                        // Loop-guard: check whether the model has already called this exact
                        // tool with the same args multiple times in this turn. Refuse a
                        // repeat run and inject a "loop_detected" envelope so the model has
                        // to pivot to a different approach. Cost safety net.
                        val signature = tool.toolName + "::" + tool.input
                        // "This turn" = since the most recent user message. Earlier
                        // identical calls in PREVIOUS turns aren't the model flailing
                        // now — they're history, and counting them produces a confusing
                        // "you already called this 3 times in this turn" envelope after
                        // a single fresh call.
                        val turnStartIndex = messages.indexOfLast { it.role == MessageRole.USER }
                        val turnSlice = messages.subList(
                            (turnStartIndex + 1).coerceAtLeast(0),
                            messages.size
                        )
                        // Pair each prior matching tool with its parent message so we can
                        // tell HOW LONG AGO the most recent identical call ran (needed for
                        // the freshness-TTL bypass below).
                        val priorMatching = turnSlice.flatMap { msg ->
                            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                                .filter { it.isExecuted && it.toolName + "::" + it.input == signature }
                                .map { it to msg }
                        }
                        val priorOccurrences = priorMatching.size
                        // Freshness-TTL bypass: read-only tools that measure a real-time
                        // signal (battery, screenshot, sensors) get a TTL window after
                        // which an identical call is treated as a refresh, not a loop.
                        // Without this, the model's "/battery" gets loop_detected and the
                        // user sees the stale earlier reading served as if fresh.
                        val ttlMs = FRESHNESS_TTL_MS_BY_TOOL[tool.toolName]
                        val mostRecentCallEpochMs = if (ttlMs != null && priorMatching.isNotEmpty()) {
                            priorMatching.maxOf { (_, msg) ->
                                val ts = msg.finishedAt ?: msg.createdAt
                                ts.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                            }
                        } else 0L
                        val ageMs = if (mostRecentCallEpochMs > 0L) {
                            System.currentTimeMillis() - mostRecentCallEpochMs
                        } else Long.MAX_VALUE
                        val ttlBypass = ttlMs != null && ageMs >= ttlMs
                        if (priorOccurrences >= LOOP_GUARD_REPEAT_THRESHOLD && !ttlBypass) {
                            loopGuardTripCount++
                            Log.w(TAG, "generateText: loop-guard tripped on $signature (${priorOccurrences + 1} repeat, trip #$loopGuardTripCount this turn); injecting bail-out envelope")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("loop_detected"))
                                                put(
                                                    "recovery", JsonPrimitive(
                                                        "You have already called ${tool.toolName} with identical arguments " +
                                                            "${priorOccurrences} time(s) in this turn without making progress. " +
                                                            "Stop retrying. Either: (a) change the args meaningfully, (b) try a " +
                                                            "different tool that addresses the underlying request, or (c) hand " +
                                                            "back to the user with what you have so far. Examples: for 'search " +
                                                            "X in chrome' use open_url(\"https://www.google.com/search?q=X\") " +
                                                            "instead of fighting Chrome's URL bar via set_text; for terminal " +
                                                            "tasks use termux_run_command instead of typing into Termux."
                                                    )
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                            // Skip the actual execution. The next generation step will see
                            // this envelope and (if the model is well-prompted by the skill
                            // docs) will pivot to a different approach.
                            return@forEach
                        }
                        // Pre-parse args BEFORE the runCatching block so we can surface a
                        // clean structured envelope when the LLM provider truncates the
                        // streaming response mid-string (max_tokens hit, network drop, etc.).
                        // Without this, kotlinx.serialization's raw exception message —
                        // which includes the entire failed input — lands in the LLM-facing
                        // `detail` field, can be thousands of tokens, and the model often
                        // retries the same too-big call.
                        val parsedArgs = runCatching {
                            json.parseToJsonElement(tool.input.ifBlank { "{}" })
                        }
                        if (parsedArgs.isFailure) {
                            val cause = parsedArgs.exceptionOrNull()
                            Log.w(TAG, "tool ${tool.toolName} args failed to parse (likely truncated stream)", cause)
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("invalid_tool_args"))
                                            put(
                                                "detail",
                                                JsonPrimitive(
                                                    (cause?.message ?: cause?.javaClass?.simpleName ?: "json_parse_failed")
                                                        .take(200)
                                                ),
                                            )
                                            put(
                                                "recovery",
                                                JsonPrimitive(
                                                    "Tool args JSON failed to parse — most often the provider's " +
                                                        "stream was cut off mid-string by max_tokens or a network drop. " +
                                                        "Retry with a shorter call. For long payloads (e.g. a 4000-char " +
                                                        "message), split into multiple smaller tool calls or shrink the " +
                                                        "content."
                                                ),
                                            )
                                            put(
                                                "exception",
                                                JsonPrimitive(cause?.javaClass?.simpleName ?: "JsonParseException"),
                                            )
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = parsedArgs.getOrThrow()
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: ${redactSecrets(args)}")
                            // Mark the tool as "execution started" BEFORE actually running.
                            // ChatService persists this when it sees the chunk so a process
                            // kill between mark-and-output leaves a clear breadcrumb on disk:
                            // on replay we'll see Approved + executionStartedAt + empty output
                            // and refuse to silently re-run. The mark survives via the
                            // existing emit-and-persist plumbing — see ChatService chunk
                            // handler's needsImmediatePersist branch.
                            val markedTool = tool.copy(executionStartedAt = System.currentTimeMillis())
                            run {
                                val lastMsg = messages.lastOrNull()
                                if (lastMsg != null) {
                                    val markedParts = lastMsg.parts.map { p ->
                                        if (p is UIMessagePart.Tool && p.toolCallId == tool.toolCallId) markedTool else p
                                    }
                                    messages = messages.dropLast(1) + lastMsg.copy(parts = markedParts)
                                    emit(GenerationChunk.Messages(messages))
                                }
                            }
                            // Hard-cap individual tool execution at the remaining wall-clock
                            // budget so a single tool with its OWN long timeout (camera 5min,
                            // ssh_exec timeout_seconds=300) can't carry the turn past the
                            // global ${ToolRuntimeLimits.turnBudgetMs}ms cap. If the budget is
                            // already blown when we start the tool, return a structured
                            // wall-clock envelope instead of even attempting.
                            val remainingMs = ToolRuntimeLimits.turnBudgetMs -
                                (android.os.SystemClock.elapsedRealtime() - turnStartMs)
                            val result = if (remainingMs <= 0L) {
                                Log.w(TAG, "generateText: ${toolDef.name} skipped — wall-clock budget already exceeded")
                                listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                                    put("error", JsonPrimitive("tool_cancelled_wall_clock"))
                                    put("detail", JsonPrimitive("turn budget exceeded before tool started"))
                                })))
                            } else {
                                withTimeoutOrNull(remainingMs) { toolDef.execute(args) }
                                    ?: run {
                                        Log.w(TAG, "generateText: ${toolDef.name} cancelled — wall-clock budget exhausted mid-execution")
                                        listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("tool_cancelled_wall_clock"))
                                            put(
                                                "detail",
                                                JsonPrimitive("tool execution exceeded the ${ToolRuntimeLimits.turnBudgetMs / 1000}s turn budget")
                                            )
                                        })))
                                    }
                            }
                            executedTools += markedTool.copy(output = result)
                        }.onFailure {
                            // Stack trace stays in logcat for debugging; the JSON envelope
                            // sent BACK to the LLM gets just the exception's message and a
                            // short class hint. Stuffing the full multi-frame R8-obfuscated
                            // trace into `error` (the prior behaviour) burned hundreds of
                            // tokens per failure, confused the model, and surfaced
                            // user-visible "java.lang.IllegalStateException at ..." walls
                            // for what was usually a one-line "name is required" problem.
                            Log.w(TAG, "tool ${tool.toolName} threw", it)
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("tool_failed"))
                                                put(
                                                    "detail",
                                                    // Cap at 500 chars so a tool that throws with
                                                    // a giant message (e.g. an OkHttp body dump or
                                                    // an echoed input arg) doesn't ship 8000+
                                                    // tokens back to the LLM on every failure.
                                                    JsonPrimitive((it.message ?: it.javaClass.simpleName).take(500)),
                                                )
                                                // Class name as a separate hint so the LLM can
                                                // distinguish validation (IllegalStateException /
                                                // IllegalArgumentException) from runtime issues.
                                                put(
                                                    "exception",
                                                    JsonPrimitive(it.javaClass.simpleName),
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }
        .onStart {
            // Reset per-turn navigation tracking and surface the overlay so the user
            // sees that automation is happening even when the agent runs from Telegram.
            AgentTurnTracker.reset()
            AgentOverlay.show(context)
        }
        .onCompletion {
            AgentOverlay.hide(context)
            handleAutoReturnAfterTurn()
        }
        .flowOn(Dispatchers.IO)

    /**
     * If the agent navigated away from RikkaHub during this turn (launch_app / open_url) and
     * the user is still on that destination, bring RikkaHub back to the foreground so the
     * user is not stranded inside Chrome / Termux / etc. If the user manually switched apps
     * mid-turn, we skip the auto-return and surface a Toast explaining the safety behavior.
     */
    private fun handleAutoReturnAfterTurn() {
        if (!AgentTurnTracker.didNavigateAway()) return
        // Only auto-return when the agent actually drove the destination app via screen
        // automation (tap, click_node, set_text, swipe, scroll, global_action). A pure
        // "open Chrome and stay there" request is just launch_app + a text reply — yanking
        // the user back to RikkaHub in that case defeats the purpose of the request.
        if (!AgentTurnTracker.didAutomate()) return
        val destination = AgentTurnTracker.lastDestination()
        val currentForeground = RikkaAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString()

        val userSwitchedAway = destination != null
            && currentForeground != null
            && currentForeground != destination
            && currentForeground != context.packageName

        if (userSwitchedAway) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "RikkaHub: skipped auto-return because you switched apps. (Safety feature)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // startActivity throws ActivityNotFoundException / SecurityException —
            // both Exception. Catching Throwable here would also swallow JVM errors
            // (OOM, StackOverflowError); let those propagate.
            Log.w(TAG, "auto-return launch failed", e)
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        systemAddendum: String? = null,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
    ) {
        val internalMessages = buildList {
            // Conversation-level system prompt override (upstream): when the assistant
            // allows it and the conversation supplies one, it replaces the assistant prompt.
            val effectiveSystemPrompt =
                if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            val memoryPrompt = if (assistant.enableMemory) {
                buildMemoryPrompt(memories = memories)
            } else ""
            val recentChatsPrompt = if (assistant.enableRecentChatsReference) {
                buildRecentChatsPrompt(assistant, conversationRepo)
            } else ""
            val toolPrompts = tools.map { tool -> tool.systemPrompt(model, messages) }
            val system = systemPromptBuilder.build(
                assistantPrompt = effectiveSystemPrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = recentChatsPrompt,
                toolPrompts = toolPrompts,
                systemAddendum = systemAddendum,
            )
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageSize).ageOldToolImages())
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = true
                )
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
