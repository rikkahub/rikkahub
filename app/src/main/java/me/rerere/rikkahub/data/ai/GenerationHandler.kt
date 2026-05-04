package me.rerere.rikkahub.data.ai

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.service.RikkaAccessibilityService
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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

private const val TAG = "GenerationHandler"

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

/**
 * Wall-clock budget for a single user turn. The maxSteps cap is per-step, but a stuck
 * agent loop with diverse-args tool calls can still chew through hours and 100k+ tokens.
 * 5 minutes is generous for legitimate multi-tool turns (download + install + screenshot
 * + verify) and brutal for runaway loops.
 */
private const val TURN_WALL_CLOCK_BUDGET_MS = 5L * 60L * 1000L

/**
 * Max number of times the loop guard can trip in a single turn before we force-end the
 * turn entirely. Prevents the "model keeps trying different tools, each gets loop-detected"
 * pattern that produced the 27-step / 141K-token disaster: one trip means the model is
 * confused; six trips means it's not coming back.
 */
private const val MAX_LOOP_GUARD_TRIPS_PER_TURN = 6

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
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        val turnStartMs = android.os.SystemClock.elapsedRealtime()
        var loopGuardTripCount = 0

        for (stepIndex in 0 until maxSteps) {
            // Wall-clock cap: any single user turn that has been running longer than the
            // budget is force-ended, regardless of whether the model wants more steps.
            // This is the second line of defence after maxSteps; without it a model that
            // discovers many distinct tool calls (each within the loop guard) can still
            // run for hours.
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs
            if (elapsedMs > TURN_WALL_CLOCK_BUDGET_MS) {
                Log.w(TAG, "generateText: wall-clock cap (${TURN_WALL_CLOCK_BUDGET_MS}ms) hit at step #$stepIndex; force-ending turn")
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
                if (assistant?.enableMemory == true) {
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

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
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
                )
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

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval == true && tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
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
                        // Auto or Approved - execute the tool, but first check whether the
                        // model has already called this exact tool with these exact args
                        // multiple times in this turn. If so, refuse to spend another round-
                        // trip and return a structured "you are looping" envelope so the
                        // model has to either change strategy or hand back to the user.
                        // This is the cost safety net: without it a stuck model can chew
                        // through an entire model context (and the user's tokens) on the
                        // same screen.
                        val signature = tool.toolName + "::" + tool.input
                        // Pair each prior matching tool with its parent message so we can
                        // tell HOW LONG AGO the most recent identical call ran (needed for
                        // the freshness-TTL bypass below).
                        val priorMatching = messages.flatMap { msg ->
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
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            executedTools += tool.copy(output = result)
                        }.onFailure {
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
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
        } catch (t: Throwable) {
            Log.w(TAG, "auto-return launch failed", t)
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
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
    ) {
        val internalMessages = buildList {
            val system = buildString {
                // 如果助手有系统提示，则添加到消息中
                if (assistant.systemPrompt.isNotBlank()) {
                    append(assistant.systemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant, conversationRepo))
                }

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageSize))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
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
