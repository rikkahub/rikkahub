package me.rerere.rikkahub.web.a2a

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.ConversationDto
import me.rerere.rikkahub.web.dto.MessageNodeDto
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.routes.singleNodeDiffOrNull
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val EVENT_STATUS_NAME = "task-status-update"
private const val EVENT_ARTIFACT_NAME = "task-artifact-update"

// How long a synchronous `message/send` blocks waiting for the turn to reach a
// terminal/input-required state before returning the still-working task. Matches
// the Hermes A2A plugin's inbound reply timeout (300s).
internal const val A2A_SYNC_REPLY_TIMEOUT_MS = 300_000L

internal enum class A2aAccessResult {
    ALLOWED,
    DISABLED,
    FORBIDDEN,
}

internal fun evaluateA2aAccess(
    enabled: Boolean,
    serverLocalhostOnly: Boolean,
    tokenBlank: Boolean,
    bearerMatch: Boolean,
): A2aAccessResult = when {
    !enabled -> A2aAccessResult.DISABLED
    serverLocalhostOnly && tokenBlank -> A2aAccessResult.ALLOWED
    tokenBlank -> A2aAccessResult.FORBIDDEN
    bearerMatch -> A2aAccessResult.ALLOWED
    else -> A2aAccessResult.FORBIDDEN
}

internal fun a2aBearerMatches(authorizationHeader: String?, expectedToken: String): Boolean {
    val token = extractA2aBearerToken(authorizationHeader) ?: return false
    return secureA2aEquals(token, expectedToken)
}

internal fun classifyA2aTransition(
    jobPresent: Boolean,
    prevJobPresent: Boolean,
    doneForContext: Boolean,
    newError: Boolean,
    cancelRequested: Boolean,
    pendingApproval: Boolean,
    currentState: A2aTaskState = A2aTaskState.SUBMITTED,
): A2aTaskState? {
    if (currentState.isTerminal()) return null
    if (jobPresent && !prevJobPresent && currentState != A2aTaskState.WORKING) {
        return A2aTaskState.WORKING
    }
    if (!jobPresent && newError) {
        return A2aTaskState.FAILED
    }
    if (!jobPresent && doneForContext && !newError && !pendingApproval) {
        return A2aTaskState.COMPLETED
    }
    if (!jobPresent && cancelRequested && !pendingApproval) {
        return A2aTaskState.CANCELED
    }
    if (!jobPresent && pendingApproval) {
        return A2aTaskState.INPUT_REQUIRED
    }
    return null
}

internal fun classifyA2aTerminalState(
    cancelRequested: Boolean,
    newError: Boolean,
    pendingApproval: Boolean,
): A2aTaskState = when {
    cancelRequested -> A2aTaskState.CANCELED
    newError -> A2aTaskState.FAILED
    pendingApproval -> A2aTaskState.INPUT_REQUIRED
    else -> A2aTaskState.COMPLETED
}

internal suspend fun reconcileTerminalFromJobCompletion(
    entry: A2aTaskEntry,
    completedJob: Job,
    reconciledCompletionJobs: MutableSet<Job>,
    classifyTerminalState: () -> A2aTaskState,
    emitTerminalTransition: suspend (A2aTaskState) -> Unit,
) {
    val shouldReconcile = synchronized(reconciledCompletionJobs) {
        reconciledCompletionJobs.add(completedJob)
    }
    if (!shouldReconcile) return
    if (entry.job !== completedJob) return
    if (entry.state.isTerminal()) return

    emitTerminalTransition(classifyTerminalState())
}

internal fun conversationHasPendingA2aApproval(conversation: Conversation): Boolean =
    conversation.currentMessages.any { message ->
        message.parts.any { it is UIMessagePart.Tool && it.approvalState is ToolApprovalState.Pending }
    }

internal data class A2aArtifactDelta(
    val nodeId: Uuid,
    val text: String,
    val fullText: String,
    val append: Boolean,
)

internal interface A2aMessageFlowClient {
    suspend fun sendMessageReturningJob(
        conversationId: Uuid,
        content: List<UIMessagePart>,
    ): Job

    suspend fun handleToolApprovalReturningJob(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): Job?

    suspend fun initializeConversationForSkill(contextId: Uuid, assistantId: Uuid)
}

    private fun ChatService.toA2aMessageFlowClient(): A2aMessageFlowClient = object : A2aMessageFlowClient {
    override suspend fun sendMessageReturningJob(conversationId: Uuid, content: List<UIMessagePart>): Job =
        this@toA2aMessageFlowClient.sendMessageReturningJob(conversationId, content)

    override suspend fun handleToolApprovalReturningJob(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): Job? = this@toA2aMessageFlowClient.handleToolApprovalReturningJob(
        conversationId,
        toolCallId,
        approved,
        reason,
        answer,
    )

    override suspend fun initializeConversationForSkill(contextId: Uuid, assistantId: Uuid) =
        this@toA2aMessageFlowClient.initializeConversationForSkill(contextId, assistantId)
    }

internal fun classifyA2aArtifactDelta(
    previousConversationDto: ConversationDto?,
    currentDto: ConversationDto,
    lastSentTextByNode: Map<Uuid, String>,
): A2aArtifactDelta? {
    val diff = previousConversationDto?.singleNodeDiffOrNull(currentDto) ?: return null
    val nodeId = runCatching { Uuid.parse(diff.node.id) }.getOrNull() ?: return null
    val assistantText = extractAssistantTextFromMessageNode(diff.node) ?: return null

    val previousText = lastSentTextByNode[nodeId]
    val isAppend = previousText != null && assistantText.startsWith(previousText)
    val payloadText = if (isAppend) {
        assistantText.substring(previousText.length)
    } else {
        assistantText
    }

    if (payloadText.isEmpty()) return null

    return A2aArtifactDelta(
        nodeId = nodeId,
        text = payloadText,
        fullText = assistantText,
        append = isAppend,
    )
}

fun Route.a2aAgentCardRoute(
    settingsStore: SettingsStore,
) {
    // The current A2A spec serves the card at /.well-known/agent-card.json, but
    // many clients (incl. the Hermes A2A plugin) still fetch the older
    // /.well-known/agent.json. Serve both so any A2A-compliant peer discovers us
    // — and so a peer's POST lands on the card-advertised `$base/a2a` URL.
    get("/.well-known/agent-card.json") { respondA2aAgentCard(call, settingsStore) }
    get("/.well-known/agent.json") { respondA2aAgentCard(call, settingsStore) }
}

private suspend fun respondA2aAgentCard(call: ApplicationCall, settingsStore: SettingsStore) {
    val settings = settingsStore.settingsFlow.value
    val tokenBlank = settings.a2aServerToken.isBlank()
    val bearerMatch = a2aBearerMatches(call.request.headers[HttpHeaders.Authorization], settings.a2aServerToken)
    when (
        evaluateA2aAccess(
            enabled = settings.a2aEnabled,
            serverLocalhostOnly = settings.a2aServerLocalhostOnly,
            tokenBlank = tokenBlank,
            bearerMatch = bearerMatch,
        )
    ) {
        A2aAccessResult.DISABLED -> call.respond(HttpStatusCode.NotFound)
        A2aAccessResult.FORBIDDEN -> call.respond(HttpStatusCode.Forbidden)
        A2aAccessResult.ALLOWED -> {
            val baseUrl = buildA2aBaseUrl(call.request)
            call.respond(settings.toA2aAgentCard(baseUrl = baseUrl, bearerRequired = !tokenBlank))
        }
    }
}

fun Route.a2aRpcRoute(
    appScope: CoroutineScope,
    chatService: ChatService,
    settingsStore: SettingsStore,
    registry: A2aTaskRegistry,
    syncReplyTimeoutMs: Long = A2A_SYNC_REPLY_TIMEOUT_MS,
) {
    post("/a2a") {
        val settings = settingsStore.settingsFlow.value
        val tokenBlank = settings.a2aServerToken.isBlank()
        val bearerMatch = a2aBearerMatches(call.request.headers[HttpHeaders.Authorization], settings.a2aServerToken)
        when (
            evaluateA2aAccess(
                enabled = settings.a2aEnabled,
                serverLocalhostOnly = settings.a2aServerLocalhostOnly,
                tokenBlank = tokenBlank,
                bearerMatch = bearerMatch,
            )
        ) {
            A2aAccessResult.DISABLED -> {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }

            A2aAccessResult.FORBIDDEN -> {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

            A2aAccessResult.ALLOWED -> {
                // Continue.
            }
        }

        val request = runCatching { readBoundedA2aJsonRpcRequest(call) }
            .getOrElse {
                if (it is A2aRpcRequestBodyTooLargeException) {
                    call.respondText(
                        text = it.message ?: "",
                        status = HttpStatusCode.PayloadTooLarge,
                    )
                    return@post
                }
                call.respond(jsonRpcFailure(null, -32700, "Parse error", it.message))
                return@post
            }

        if (request.jsonrpc != "2.0") {
            call.respond(jsonRpcFailure(request.id, -32600, "Invalid request", "jsonrpc 2.0 required"))
            return@post
        }

        when (request.method) {
            "message/send" -> call.respond(
                dispatchJsonRpc(request.id) {
                    val entry = startOrResumeA2aTask(
                        appScope = appScope,
                        paramsJson = request.params,
                        chatService = chatService,
                        settingsStore = settingsStore,
                        registry = registry,
                    )
                    // A2A `message/send` is the synchronous variant: spec clients
                    // (e.g. the Hermes plugin) read the reply straight from this
                    // response and never poll tasks/get. Block until the turn
                    // reaches a terminal/input-required state (or the bound), then
                    // return the task carrying the agent's reply. On timeout the
                    // working task is returned and a polling client can follow up.
                    awaitA2aTerminalOrTimeout(entry, syncReplyTimeoutMs)
                    messageSendSuccess(request.id, entry, chatService)
                }
            )

            "message/stream" -> {
                val entryResult = dispatchJsonRpcValue(request.id) {
                    startOrResumeA2aTask(
                        appScope = appScope,
                        paramsJson = request.params,
                        chatService = chatService,
                        settingsStore = settingsStore,
                        registry = registry,
                    )
                }
                val entry = when (entryResult) {
                    is JsonRpcValueResult.Success -> entryResult.value
                    is JsonRpcValueResult.Failure -> {
                        call.respond(entryResult.failure)
                        return@post
                    }
                }

                call.respondOutputStream(contentType = ContentType.Text.EventStream) {
                    val writer = OutputStreamWriter(this)
                    streamA2aTaskEvents(
                        writer = writer,
                        requestId = request.id,
                        registry = registry,
                        taskEntry = entry,
                        getCurrentConversation = { chatService.getConversationFlow(entry.contextId).value },
                    )
                }
            }

            "tasks/get" -> call.respond(
                dispatchJsonRpc(request.id) {
                    val params = decodeTasksGetParams(request.params)
                    val task = registry.get(params.id) ?: throw NotFoundException("Task not found")
                    JsonRpcSuccess(
                        id = request.id,
                        result = JsonInstant.encodeToJsonElement(
                            TasksGetResult.serializer(),
                            TasksGetResult(task = task.toA2aTask(chatService.getConversationFlow(task.contextId).value)),
                        ),
                    )
                }
            )

            "tasks/cancel" -> call.respond(
                dispatchJsonRpc(request.id) {
                    val params = decodeTasksCancelParams(request.params)
                    val task = registry.requestCancelWithStop(params.id) { contextId, job ->
                        chatService.stopGeneration(contextId, job)
                    } ?: throw NotFoundException("Task not found")
                    val contextConversation = chatService.getConversationFlow(task.contextId).value
                    val transitioned = registry.transition(
                        task.taskId,
                        A2aTaskState.CANCELED,
                        terminal = true,
                        statusConversation = contextConversation,
                        markTerminalDelivered = false,
                    )
                    val updated = transitioned ?: task
                    if (transitioned?.state == A2aTaskState.CANCELED) {
                        emitFinalA2aTaskArtifact(
                            registry = registry,
                            entry = updated,
                            conversation = contextConversation,
                        )
                        registry.markTerminalStatusDelivered(updated.taskId)
                        registry.evictExpiredAndOverflow()
                        registry.cancelCollector(updated.taskId)
                    }
                    JsonRpcSuccess(
                        id = request.id,
                        result = JsonInstant.encodeToJsonElement(
                            TasksCancelResult.serializer(),
                            TasksCancelResult(task = updated.toA2aTask(chatService.getConversationFlow(updated.contextId).value)),
                        ),
                    )
                }
            )

            else -> call.respond(jsonRpcFailure(request.id, -32601, "Method not found"))
        }
    }
}

/**
 * Resolve the conversation/context id for an inbound task.
 *
 * Spec-compliant A2A clients carry the context inside the message
 * (`params.message.contextId`) and omit it entirely on the first turn. We accept
 * the rikkahub-native top-level `params.contextId` first, fall back to the
 * in-message id, and mint a fresh conversation when neither is present. A
 * supplied-but-malformed id is surfaced rather than silently replaced, so
 * multi-turn continuity never breaks without the caller knowing.
 */
internal fun resolveA2aContextId(params: MessageSendParams): Uuid {
    val raw = params.contextId ?: params.message.contextId
    return if (raw == null) {
        Uuid.random()
    } else {
        runCatching { Uuid.parse(raw) }
            .getOrElse { throw BadRequestException("contextId must be a UUID") }
    }
}

internal suspend fun startOrResumeA2aTask(
    appScope: CoroutineScope,
    params: MessageSendParams,
    messageFlowClient: A2aMessageFlowClient,
    registry: A2aTaskRegistry,
    getConversation: (Uuid) -> Conversation,
    resolveSpawnableSkill: (String?) -> Assistant,
    onAccepted: suspend (A2aTaskEntry) -> Unit = {},
): A2aTaskEntry {
    if (params.approval != null) {
        val taskId = params.taskId ?: throw BadRequestException("approval requires taskId")
        val entry = registry.get(taskId) ?: throw NotFoundException("task not found")
        val conversation = getConversation(entry.contextId)
        validatePendingApproval(conversation, params.approval)
        val job = messageFlowClient.handleToolApprovalReturningJob(
            conversationId = entry.contextId,
            toolCallId = params.approval.toolCallId,
            approved = params.approval.approved,
            reason = params.approval.reason,
            answer = params.approval.answer,
        )
        if (job != null) {
            when (registry.attachJob(entry.taskId, job)) {
                is A2aAttachResult.Accepted -> {
                    // The HITL turn resumed, so the task is no longer resting on
                    // its approval prompt. Move it out of INPUT_REQUIRED before
                    // returning, or a synchronous message/send would treat the
                    // stale resting state as "done" and return the old prompt
                    // instead of blocking for the resumed generation.
                    registry.transition(
                        entry.taskId,
                        A2aTaskState.WORKING,
                        statusConversation = getConversation(entry.contextId),
                    )
                    onAccepted(entry)
                }
                is A2aAttachResult.Rejected -> {
                    job.cancel()
                }
            }
        }
        return entry
    }

    val contextId = resolveA2aContextId(params)
    val assistant = resolveSpawnableSkill(params.skillId)

    return when (val admission = registry.admit(contextId, assistant.id, params.message.messageId)) {
        is A2aAdmission.Duplicate -> admission.existing
        is A2aAdmission.CapacityExceeded -> throw A2aCapacityExceededException("server at capacity, retry later")
        is A2aAdmission.Conflict -> {
            val existing = registry.get(admission.activeTaskId)
                ?: throw BadRequestException("conversation has active initializing task")
            if (existing.state == A2aTaskState.INPUT_REQUIRED) {
                throw BadRequestException("task requires approval")
            }
            throw BadRequestException("conversation has active task")
        }
        is A2aAdmission.Accepted -> {
            try {
                messageFlowClient.initializeConversationForSkill(contextId, assistant.id)
                val job = messageFlowClient.sendMessageReturningJob(
                    conversationId = contextId,
                    content = params.message.toUiTextParts(),
                )
                when (registry.attachJob(admission.entry.taskId, job)) {
                    is A2aAttachResult.Accepted -> {
                        onAccepted(admission.entry)
                    }
                    is A2aAttachResult.Rejected -> {
                        job.cancel()
                    }
                }
                admission.entry
            } catch (error: Throwable) {
                registry.rollbackAdmission(admission.entry.taskId)
                throw error
            }
        }
    }
}

internal suspend fun startOrResumeA2aTask(
    appScope: CoroutineScope,
    paramsJson: JsonElement?,
    chatService: ChatService,
    settingsStore: SettingsStore,
    registry: A2aTaskRegistry,
): A2aTaskEntry {
    return startOrResumeA2aTask(
        appScope = appScope,
        params = decodeMessageSendParams(paramsJson),
        messageFlowClient = chatService.toA2aMessageFlowClient(),
        registry = registry,
        getConversation = { chatService.getConversationFlow(it).value },
        resolveSpawnableSkill = { skillId ->
            resolveSpawnableSkill(settingsStore.settingsFlow.value, skillId)
        },
        onAccepted = { entry ->
            startCollectorIfNeeded(appScope, chatService, registry, entry)
        },
    )
}

private fun startCollectorIfNeeded(
    appScope: CoroutineScope,
    chatService: ChatService,
    registry: A2aTaskRegistry,
    entry: A2aTaskEntry,
) {
    registry.startCollector(entry.taskId) {
        appScope.launch {
            try {
                collectA2aTaskEvents(chatService, registry, entry)
            } finally {
                registry.finishCollector(entry.taskId, coroutineContext[Job] ?: error("collector job missing"))
            }
        }
    }
}

private suspend fun collectA2aTaskEvents(
    chatService: ChatService,
    registry: A2aTaskRegistry,
    entry: A2aTaskEntry,
) = coroutineScope {
    chatService.addConversationReference(entry.contextId)
    try {
        val errors = chatService.errors
        val knownErrorIds = errors.value.map { it.id }.toMutableSet()
        var previousConversationDto: ConversationDto? = null
        var previousJobMatches = false
        var registeredCompletionJob: Job? = null
        val reconciledCompletionJobs = Collections.newSetFromMap(IdentityHashMap<Job, Boolean>())

        suspend fun reconcileCompletedJob(job: Job) {
            reconcileTerminalFromJobCompletion(
                entry = entry,
                completedJob = job,
                reconciledCompletionJobs = reconciledCompletionJobs,
                classifyTerminalState = {
                    val conversation = chatService.getConversationFlow(entry.contextId).value
                    classifyA2aTerminalState(
                        cancelRequested = entry.cancelRequested,
                        newError = hasFreshTaggedA2aError(errors.value, knownErrorIds, entry.contextId),
                        pendingApproval = conversationHasPendingA2aApproval(conversation),
                    )
                },
                emitTerminalTransition = { state ->
                    transitionAndEmit(registry, chatService, entry, state)
                },
            )
        }

        fun registerCompletion(job: Job?) {
            if (job == null || registeredCompletionJob === job) return
            registeredCompletionJob = job
            job.invokeOnCompletion {
                launch {
                    reconcileCompletedJob(job)
                }
            }
            if (job.isCompleted) {
                launch {
                    reconcileCompletedJob(job)
                }
            }
        }

        registerCompletion(entry.job)

        registry.transition(
            taskId = entry.taskId,
            state = entry.state,
            terminal = false,
            statusConversation = chatService.getConversationFlow(entry.contextId).value,
        )

        val conversationFlow = combine(
            chatService.getConversationFlow(entry.contextId),
            chatService.getGenerationJobStateFlow(entry.contextId),
        ) { conversation, job ->
            ChatFlowSignalPair(conversation, job === entry.job)
        }

        try {
            conversationFlow.collect { signal ->
                registerCompletion(entry.job)
                val conversation = signal.conversation
                val dto = conversation.toDto(isGenerating = signal.matchesTaskJob)
                emitA2aTaskArtifact(registry, entry, dto, previousConversationDto)
                previousConversationDto = dto

                val transition = classifyA2aTransition(
                    jobPresent = signal.matchesTaskJob,
                    prevJobPresent = previousJobMatches,
                    doneForContext = false,
                    newError = false,
                    cancelRequested = entry.cancelRequested,
                    pendingApproval = conversationHasPendingA2aApproval(conversation),
                    currentState = entry.state,
                )
                previousJobMatches = signal.matchesTaskJob
                transition?.let {
                    transitionAndEmit(registry, chatService, entry, it)
                }

                if (entry.state.isTerminal()) {
                    throw A2aStreamFinished()
                }
            }
        } catch (_: A2aStreamFinished) {
            // Collector exits after the single terminal event is emitted.
        }
    } finally {
        chatService.removeConversationReference(entry.contextId)
    }
}

private suspend fun transitionAndEmit(
    registry: A2aTaskRegistry,
    chatService: ChatService,
    entry: A2aTaskEntry,
    state: A2aTaskState,
) {
    val terminal = state.isTerminal()
    val conversation = chatService.getConversationFlow(entry.contextId).value
    if (terminal) {
        emitFinalA2aTaskArtifact(
            registry = registry,
            entry = entry,
            conversation = conversation,
        )
    }
    val updated = registry.transition(
        entry.taskId,
        state,
        terminal = terminal,
        statusConversation = conversation,
        markTerminalDelivered = !terminal,
    ) ?: return
    if (terminal) {
        registry.markTerminalStatusDelivered(updated.taskId)
        registry.evictExpiredAndOverflow()
        registry.cancelCollector(updated.taskId)
    }
}

private suspend fun emitA2aTaskArtifact(
    registry: A2aTaskRegistry,
    taskEntry: A2aTaskEntry,
    currentDto: ConversationDto,
    previousConversationDto: ConversationDto?,
): Boolean {
    val delta = classifyA2aArtifactDelta(
        previousConversationDto = previousConversationDto,
        currentDto = currentDto,
        lastSentTextByNode = taskEntry.lastSentTextByNode,
    ) ?: return false

    registry.updateLastSentText(taskEntry.taskId, delta.nodeId, delta.fullText)
    registry.emit(
        taskEntry.taskId,
        A2aStreamEvent.TaskArtifactUpdateEvent(
            taskId = taskEntry.taskId,
            contextId = taskEntry.contextId.toString(),
            artifact = A2aArtifact(
                artifactId = taskEntry.taskId,
                parts = listOf(A2aPart.TextPart(text = delta.text)),
                append = delta.append,
            ),
            append = delta.append,
        )
    )
    return true
}

private suspend fun emitFinalA2aTaskArtifact(
    registry: A2aTaskRegistry,
    entry: A2aTaskEntry,
    conversation: Conversation?,
) {
    val text = conversation?.latestAssistantTextForA2a()?.takeIf { it.isNotBlank() } ?: return
    val alreadySent = entry.lastSentTextByNode.values.joinToString("\n")
    if (alreadySent == text) return

    registry.updateLastSentText(entry.taskId, A2A_FINAL_ARTIFACT_NODE_ID, text)
    registry.emit(
        entry.taskId,
        A2aStreamEvent.TaskArtifactUpdateEvent(
            taskId = entry.taskId,
            contextId = entry.contextId.toString(),
            artifact = A2aArtifact(
                artifactId = entry.taskId,
                parts = listOf(A2aPart.TextPart(text = text)),
                append = false,
                lastChunk = true,
            ),
            append = false,
        )
    )
}

internal suspend fun streamA2aTaskEvents(
    writer: Writer,
    requestId: JsonElement?,
    registry: A2aTaskRegistry,
    taskEntry: A2aTaskEntry,
    getCurrentConversation: () -> Conversation?,
    onBeforeStatusSubscription: suspend () -> Unit = {},
) = coroutineScope {
    var terminalArtifactText = currentTerminalArtifactText(taskEntry, getCurrentConversation())
    var terminalArtifactEmitted = taskEntry.state.isTerminal()
    emitConversationArtifactSnapshot(
        writer = writer,
        requestId = requestId,
        taskEntry = taskEntry,
        conversation = getCurrentConversation(),
        isTerminal = taskEntry.state.isTerminal(),
    )
    val heartbeat = launch {
        while (isActive) {
            delay(1.seconds)
            writer.append(": heartbeat\n\n")
            writer.flush()
        }
    }
    try {
        onBeforeStatusSubscription()
        val artifactEvents = registry.events(taskEntry.taskId)
        val statusEvents = registry.status(taskEntry.taskId)?.map {
            A2aStreamEvent.TaskStatusUpdateEvent(
                taskId = taskEntry.taskId,
                contextId = taskEntry.contextId.toString(),
                status = it,
                final = it.state.isTerminal(),
            )
        } ?: emptyFlow()

        merge(artifactEvents, statusEvents).collect { event ->
            when (event) {
                is A2aStreamEvent.TaskStatusUpdateEvent -> {
                    if (event.final) {
                        val currentConversation = getCurrentConversation()
                        val currentTerminalText = currentTerminalArtifactText(taskEntry, currentConversation)
                        if (!terminalArtifactEmitted || currentTerminalText != terminalArtifactText) {
                            emitConversationArtifactSnapshot(
                                writer = writer,
                                requestId = requestId,
                                taskEntry = taskEntry,
                                conversation = currentConversation,
                                isTerminal = true,
                            )
                            terminalArtifactText = currentTerminalText
                            terminalArtifactEmitted = true
                        }
                    }
                    sendSseEvent(writer, requestId, event)
                    if (event.final) {
                        throw A2aStreamFinished()
                    }
                }
                is A2aStreamEvent.TaskArtifactUpdateEvent -> {
                    if (event.artifact.lastChunk) {
                        val finalArtifactText = event.artifact.parts
                            .filterIsInstance<A2aPart.TextPart>()
                            .singleOrNull()
                            ?.text
                        if (finalArtifactText != null) {
                            terminalArtifactText = finalArtifactText
                            terminalArtifactEmitted = true
                        }
                    }
                    sendSseEvent(writer, requestId, event)
                }
            }
        }
    } catch (_: A2aStreamFinished) {
        // The task reached terminal state; close this subscriber stream.
    } finally {
        heartbeat.cancel()
    }
}

private fun currentTerminalArtifactText(taskEntry: A2aTaskEntry, conversation: Conversation?): String? {
    return taskEntry
        .toA2aTask(conversation)
        .artifacts
        .singleOrNull()
        ?.parts
        ?.filterIsInstance<A2aPart.TextPart>()
        ?.singleOrNull()
        ?.text
}

private suspend fun emitConversationArtifactSnapshot(
    writer: Writer,
    requestId: JsonElement?,
    taskEntry: A2aTaskEntry,
    conversation: Conversation?,
    isTerminal: Boolean,
) {
    taskEntry.toA2aTask(conversation)
        .artifacts
        .filter { it.parts.isNotEmpty() }
        .forEach { artifact ->
            sendSseEvent(
                writer = writer,
                requestId = requestId,
                event = A2aStreamEvent.TaskArtifactUpdateEvent(
                    taskId = taskEntry.taskId,
                    contextId = taskEntry.contextId.toString(),
                    artifact = if (isTerminal) artifact.copy(lastChunk = true) else artifact,
                    append = false,
                ),
            )
        }
}

internal fun currentStreamEvents(entry: A2aTaskEntry, conversation: Conversation?): List<A2aStreamEvent> {
    val task = entry.toA2aTask(conversation = conversation)
    val statusEvent = A2aStreamEvent.TaskStatusUpdateEvent(
        taskId = task.id,
        contextId = task.contextId,
        status = task.status,
        final = task.status.state.isTerminal(),
    )
    if (!task.status.state.isTerminal()) return listOf(statusEvent)

    return task.artifacts.map { artifact ->
        A2aStreamEvent.TaskArtifactUpdateEvent(
            taskId = task.id,
            contextId = task.contextId,
            artifact = artifact.copy(lastChunk = true),
            append = false,
        )
    } + statusEvent
}

private fun sendSseEvent(writer: Writer, requestId: JsonElement?, event: A2aStreamEvent) {
    val eventName = when (event) {
        is A2aStreamEvent.TaskStatusUpdateEvent -> EVENT_STATUS_NAME
        is A2aStreamEvent.TaskArtifactUpdateEvent -> EVENT_ARTIFACT_NAME
    }
    writer.append("event: ").append(eventName).append('\n')
    writer.append("data: ").append(jsonRpcStreamEvent(requestId, event)).append("\n\n")
    writer.flush()
}

private fun jsonRpcStreamEvent(requestId: JsonElement?, event: A2aStreamEvent): String =
    JsonInstant.encodeToString(
        JsonRpcSuccess(
            id = requestId,
            result = when (event) {
                is A2aStreamEvent.TaskStatusUpdateEvent -> JsonInstant.encodeToJsonElement(
                    A2aStreamEvent.TaskStatusUpdateEvent.serializer(),
                    event,
                )
                is A2aStreamEvent.TaskArtifactUpdateEvent -> JsonInstant.encodeToJsonElement(
                    A2aStreamEvent.TaskArtifactUpdateEvent.serializer(),
                    event,
                )
            },
        )
    )

/**
 * Suspend until the task reaches a resting state the caller can act on
 * (completed/failed/canceled, or input-required for HITL), bounded by
 * [timeoutMs]. A non-positive timeout returns immediately (async behaviour); a
 * timeout leaves the task working so a polling client can still follow up.
 */
private suspend fun awaitA2aTerminalOrTimeout(entry: A2aTaskEntry, timeoutMs: Long) {
    if (timeoutMs <= 0) return
    withTimeoutOrNull(timeoutMs) {
        entry.status.first { it.state.isA2aSyncResting() }
    }
}

internal fun A2aTaskState.isA2aSyncResting(): Boolean =
    this == A2aTaskState.COMPLETED ||
        this == A2aTaskState.FAILED ||
        this == A2aTaskState.CANCELED ||
        this == A2aTaskState.INPUT_REQUIRED

private fun messageSendSuccess(
    requestId: JsonElement?,
    entry: A2aTaskEntry,
    chatService: ChatService,
): JsonRpcSuccess = JsonRpcSuccess(
    id = requestId,
    result = JsonInstant.encodeToJsonElement(
        MessageSendResult.serializer(),
        MessageSendResult(task = entry.toA2aTask(chatService.getConversationFlow(entry.contextId).value)),
    ),
)

private suspend inline fun dispatchJsonRpc(
    requestId: JsonElement?,
    block: suspend () -> JsonRpcSuccess,
): Any = try {
    block()
} catch (error: Throwable) {
    mapJsonRpcError(requestId, error)
}

private suspend inline fun <T> dispatchJsonRpcValue(
    requestId: JsonElement?,
    block: suspend () -> T,
): JsonRpcValueResult<T> = try {
    JsonRpcValueResult.Success(block())
} catch (error: Throwable) {
    JsonRpcValueResult.Failure(mapJsonRpcError(requestId, error))
}

private sealed interface JsonRpcValueResult<out T> {
    data class Success<T>(val value: T) : JsonRpcValueResult<T>
    data class Failure(val failure: JsonRpcFailure) : JsonRpcValueResult<Nothing>
}

private fun mapJsonRpcError(requestId: JsonElement?, error: Throwable): JsonRpcFailure = when (error) {
    is A2aCapacityExceededException -> jsonRpcFailure(requestId, -32002, "server at capacity, retry later")
    is BadRequestException,
    is IllegalArgumentException,
    is SerializationException -> jsonRpcFailure(requestId, -32602, "Invalid params", error.message)
    is NotFoundException -> jsonRpcFailure(requestId, -32001, "Task not found", error.message)
    else -> {
        if (error is CancellationException) throw error
        jsonRpcFailure(requestId, -32603, "Internal error")
    }
}

private fun jsonRpcFailure(
    requestId: JsonElement?,
    code: Int,
    message: String,
    data: String? = null,
): JsonRpcFailure = JsonRpcFailure(
    id = requestId,
    error = JsonRpcError(
        code = code,
        message = message,
        data = data?.let(::JsonPrimitive),
    ),
)

private fun decodeMessageSendParams(json: JsonElement?): MessageSendParams =
    JsonInstant.decodeFromJsonElement(MessageSendParams.serializer(), json ?: throw BadRequestException("params is required"))
        .also(::validateA2aMessageBounds)

private fun decodeTasksGetParams(json: JsonElement?): TasksGetParams =
    JsonInstant.decodeFromJsonElement(TasksGetParams.serializer(), json ?: throw BadRequestException("params is required"))
        .also(::validateA2aTasksGetParams)

private fun decodeTasksCancelParams(json: JsonElement?): TasksCancelParams =
    JsonInstant.decodeFromJsonElement(TasksCancelParams.serializer(), json ?: throw BadRequestException("params is required"))
        .also(::validateA2aTasksCancelParams)

private suspend fun readBoundedA2aJsonRpcRequest(call: ApplicationCall): JsonRpcRequest {
    val body = readBoundedRequestBody(call)
    return JsonInstant.decodeFromString(JsonRpcRequest.serializer(), body)
}

private suspend fun readBoundedRequestBody(call: ApplicationCall): String {
    val channel = call.request.receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead = 0

    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break

        bytesRead += read
        validateA2aRpcBody(bytesRead)
        output.write(buffer, 0, read)
    }

    // String(ByteArray, Charset) is available since API 1; ByteArrayOutputStream.toString(Charset)
    // is API 33 (> minSdk 26) and trips the NewApi lint gate.
    return String(output.toByteArray(), Charsets.UTF_8)
}

private class A2aCapacityExceededException(message: String) : RuntimeException(message)

private fun extractAssistantTextFromMessageNode(node: MessageNodeDto): String? {
    val message = node.messages.getOrNull(node.selectIndex) ?: return null
    if (message.role != MessageRole.ASSISTANT.name) return null
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}

private fun Conversation.latestAssistantTextForA2a(): String? = currentMessages
    .lastOrNull { it.role == MessageRole.ASSISTANT }
    ?.parts
    ?.filterIsInstance<UIMessagePart.Text>()
    ?.joinToString("") { it.text }

private fun hasFreshTaggedA2aError(
    errors: List<me.rerere.rikkahub.service.ChatError>,
    knownErrorIds: MutableSet<Uuid>,
    contextId: Uuid,
): Boolean = errors.any { error ->
    error.conversationId == contextId && knownErrorIds.add(error.id)
}

private fun A2aTaskState.isTerminal(): Boolean = when (this) {
    A2aTaskState.SUBMITTED,
    A2aTaskState.WORKING,
    A2aTaskState.INPUT_REQUIRED -> false
    A2aTaskState.COMPLETED,
    A2aTaskState.CANCELED,
    A2aTaskState.FAILED -> true
}

private sealed interface ChatFlowSignal
private data class ChatFlowSignalPair(
    val conversation: Conversation,
    val matchesTaskJob: Boolean,
) : ChatFlowSignal

private class A2aStreamFinished : RuntimeException(null, null, false, false)

private fun buildA2aBaseUrl(request: ApplicationRequest): String {
    val scheme = request.origin.scheme.ifBlank { "http" }
    val host = request.origin.serverHost
    val port = request.origin.serverPort
    return "$scheme://$host:$port"
}

private fun extractA2aBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun secureA2aEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
