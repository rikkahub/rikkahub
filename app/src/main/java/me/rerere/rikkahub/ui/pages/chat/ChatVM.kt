package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.shellrun.ShellRunStore
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getAssistantByIdOrCurrent
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.mutation.ConversationMutations
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.common.state.UiState
import me.rerere.rikkahub.utils.launchVm
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val shellRunStore: ShellRunStore,
    private val workspaceRepository: WorkspaceRepository,
    private val taskRunRepository: TaskRunRepository,
    private val taskCoordinator: TaskCoordinator,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    // #92: getConversationJobs() 本身不做错误降级（见 ChatService）。这里在 stateIn 之前加 catch，
    // 使一次上游异常不会永久杀死这条 UI StateFlow 的收集协程——异常被记录、降级为空 map（“无活跃任务”），
    // UI 继续可用。底层是对 in-memory StateFlow 的 combine，正常不会抛；真抛说明是非瞬时的程序错误，
    // 故只降级 + 记录，不重试（重试只会立刻再抛成死循环）。CancellationException 仍由 catch 透传，
    // 不会被吞（结构化并发的取消语义保持）。
    val conversationJobs: StateFlow<Map<Uuid, Job?>> = chatService
        .getConversationJobs()
        .catch { e ->
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e(TAG, "conversationJobs flow failed; degrading to empty map", e)
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Detached shell runs AND detached background subagents share the jobs sheet. They live in
    // separate stores, so combine the two live flows; each is independently mapped/filtered to the
    // in-flight rows of THIS conversation and concatenated, newest first.
    val backgroundJobs: StateFlow<List<UiBackgroundJob>> = combine(
        shellRunStore.observeBackgroundJobs(_conversationId)
            .map { rows -> mapBackgroundShellJobs(rows, _conversationId.toString()) },
        taskRunRepository.observeByConversation(_conversationId)
            .map { rows -> mapBackgroundSubagentJobs(rows, _conversationId.toString()) },
    ) { shell, subagent ->
        (shell + subagent).sortedByDescending { it.elapsedStartMillis() ?: 0L }
    }
        .catch { e ->
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            android.util.Log.e(TAG, "backgroundJobs flow failed; degrading to empty list", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置 — expose the hot, real-valued flow directly (no dummy->real
    // recomposition on chat entry; that double-composed the heavy chat screen
    // right inside the nav transition).
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        launchVm(onError = { reportOperationError(it) }) {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        launchVm(onError = { reportOperationError(it) }) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        // Reserved native slash commands (#364) are handled BEFORE skill expansion: they perform a
        // guaranteed side effect (arming/clearing the autonomous goal), not a model-trusted prose send.
        if (handleReservedSlashCommand(content)) return

        // A leading "/<skill> <param>" slash command (from the input popup) is expanded SYNCHRONOUSLY into
        // a directive that runs the skill — no coroutine/IO before the send, so the slash send is ordered
        // exactly like a normal send and can never land after (and cancel) a later turn.
        chatService.sendMessage(_conversationId, expandSlashSkillCommand(content), answer)
    }

    /**
     * Intercept reserved native slash commands (#364) before skill expansion. Returns true iff the
     * input was a reserved command (and was handled — no message is sent for it).
     *
     * - `/goal` or `/goal clear` → clear the active goal.
     * - `/goal <condition>` → arm the session goal AND kick a turn working toward it (the condition is
     *   sent as the first user message; the turn-end goal loop then continues until it is met, bounded
     *   by the maxGoalIterations preference and by user-stop).
     */
    private fun handleReservedSlashCommand(content: List<UIMessagePart>): Boolean {
        val idx = content.indexOfLast { it is UIMessagePart.Text }
        if (idx < 0) return false
        val text = (content[idx] as UIMessagePart.Text).text.trim()
        if (text != "/goal" && !text.startsWith("/goal ")) return false
        val arg = text.removePrefix("/goal").trim()
        if (arg.isEmpty() || arg.equals("clear", ignoreCase = true)) {
            chatService.clearGoal(_conversationId)
        } else {
            chatService.setGoal(_conversationId, arg)
            chatService.sendMessage(_conversationId, listOf(UIMessagePart.Text(arg)), answer = true)
        }
        return true
    }

    /**
     * Expand a leading "/<skill> <param>" into a directive the agent actually runs. The skill must already
     * be armed on the active assistant (the input popup enables it on selection, so its `use_skill` tool is
     * exposed for the turn); we match the text after "/" against the assistant's enabled skill NAMES by
     * longest prefix — which also handles skill names that contain spaces — and rewrite the text part into an
     * explicit "Use the <name> skill." directive plus the param. Anything that doesn't match an enabled
     * skill (a plain message, or an unknown/not-yet-enabled token) is returned verbatim. Pure & synchronous.
     */
    private fun expandSlashSkillCommand(content: List<UIMessagePart>): List<UIMessagePart> {
        val idx = content.indexOfLast { it is UIMessagePart.Text }
        if (idx < 0) return content
        val text = (content[idx] as UIMessagePart.Text).text
        if (!text.startsWith("/")) return content
        val afterSlash = text.substring(1)
        // Match against the assistant that ACTUALLY runs the turn — the conversation-bound one
        // (ChatService resolves via getAssistantByIdOrCurrent), not the global current assistant — so the
        // armed skill and the exposed use_skill tool belong to the same assistant.
        val name = settingsStore.settingsFlow.value
            .getAssistantByIdOrCurrent(conversation.value.assistantId).enabledSkills
            .filter { afterSlash == it || afterSlash.startsWith("$it ") || afterSlash.startsWith("$it\n") }
            .maxByOrNull { it.length } ?: return content
        val param = afterSlash.removePrefix(name).trim()
        val directive = buildString {
            append("Use the \"$name\" skill.")
            if (param.isNotEmpty()) {
                append("\n\n")
                append(param)
            }
        }
        return content.toMutableList().also { it[idx] = UIMessagePart.Text(directive) }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        launchVm(onError = { reportOperationError(it) }) {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        launchVm(onError = { reportOperationError(it) }) {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_vm_stop_generation_before_delete)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    // Foreground package the assistant would observe/drive if granted (#187 v2). Read live from the
    // accessibility runtime via ChatService so the in-chat grant sheet can show the user exactly which
    // app the scope will cover. Null when the accessibility service is not connected.
    fun automationForegroundPackage(): String? =
        chatService.automationForegroundPackage()

    // Write the user's per-run automation scope (#187 v2). Transient: it lands on the session's
    // pendingAutomationGrant (not the persisted Assistant), consumed by the lease derivation and
    // cleared with the rest of the automation lease lifecycle. Confirming the grant sheet calls this.
    fun grantAutomation(grant: AutomationGrant) {
        analytics.logEvent("ai_grant_automation", null)
        chatService.setPendingAutomationGrant(_conversationId, grant)
    }

    suspend fun tailBackgroundJob(job: UiBackgroundJob): String = when (job.kind) {
        // A subagent has no async output channel like a shell run; its detail (latest progress
        // summary, or the prompt) is pre-rendered at map time.
        BackgroundJobKind.Subagent -> job.detail.orEmpty()
        BackgroundJobKind.Shell -> workspaceRepository.tailShellRun(
            id = job.workspaceId,
            conversationId = _conversationId,
            taskId = job.taskId,
            maxBytes = BACKGROUND_JOB_TAIL_MAX_BYTES,
        )
    }

    /**
     * Cancel a cancellable background job from the jobs sheet. Only a background subagent is
     * cancellable in v1 (its detached coordinator job is interrupted, which folds the run to
     * Cancelled and enqueues its completion); a shell job is view-only and ignored. Cancelling a
     * job whose coordinator job is already gone (completed/process-restarted) is a benign no-op.
     */
    fun cancelBackgroundJob(job: UiBackgroundJob) {
        if (job.kind != BackgroundJobKind.Subagent) return
        val taskId = runCatching { Uuid.parse(job.taskId) }.getOrNull() ?: return
        taskCoordinator.cancelBackground(taskId)
    }

    fun saveConversationAsync() {
        launchVm(onError = { reportOperationError(it) }) {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        launchVm(onError = { reportOperationError(it) }) {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    private val ensureWorkspaceMutex = Mutex()

    /**
     * Lazily ensure the assistant has a workspace, creating + assigning one on first use (no
     * cold-start provisioning at app launch). Returns the workspace id to open — drives the chat
     * folder shortcut so pressing it always lands in a real workspace. Single-flight via the mutex:
     * the lock serializes concurrent presses, and [settingsStore.update] sets `settingsFlow.value`
     * synchronously before persisting, so the next caller re-reads the just-assigned `workspaceId`
     * from live settings — two quick taps can't each create + assign a separate workspace. Reading
     * live settings each call (no memo) also means a later workspace deletion/reassignment can't
     * strand the shortcut on a stale id.
     */
    suspend fun ensureWorkspaceId(assistantId: Uuid): String = ensureWorkspaceMutex.withLock {
        val assistant = settings.value.assistants.find { it.id == assistantId }
            ?: error("Assistant not found")
        assistant.workspaceId?.let { return@withLock it.toString() }
        val workspace = workspaceRepository.create(assistant.name)
        val newId = Uuid.parse(workspace.id)
        settingsStore.update { s ->
            s.copy(
                assistants = s.assistants.map {
                    if (it.id == assistantId) it.copy(workspaceId = newId) else it
                }
            )
        }
        workspace.id
    }

    fun deleteConversation(conversation: Conversation) {
        launchVm(onError = { reportOperationError(it) }) {
            chatService.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        launchVm(onError = { reportOperationError(it) }) {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(target: Conversation, targetAssistantId: Uuid) {
        launchVm(onError = { reportOperationError(it) }) {
            if (target.id == _conversationId) {
                // CAS-fold the assistant rebind onto the LATEST live state so a concurrent streaming
                // publish can't be clobbered (no session.state reassignment from a stale DB snapshot),
                // then persist the folded result. Switching mid-turn is safe: the in-flight turn already
                // captured its assistant/model, so it finishes unchanged and only the NEXT turn rebinds.
                chatService.updateConversationState(_conversationId) {
                    ConversationMutations.moveToAssistant(it, targetAssistantId)
                }
                conversationRepo.updateConversation(conversation.value)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                val conversationFull = conversationRepo.getConversationById(target.id) ?: return@launchVm
                conversationRepo.updateConversation(
                    ConversationMutations.moveToAssistant(conversationFull, targetAssistantId)
                )
            }
        }
    }

    fun renameConversation(target: Conversation, newTitle: String) {
        val title = newTitle.trim()
        launchVm(onError = { reportOperationError(it) }) {
            if (target.id == _conversationId) {
                // CAS-fold the title onto the LATEST live state so a concurrent streaming
                // publish can't be clobbered (no session.state reassignment), then persist
                // the folded result.
                chatService.updateConversationState(_conversationId) { it.copy(title = title) }
                conversationRepo.updateConversation(conversation.value)
            } else {
                val conversationFull = conversationRepo.getConversationById(target.id) ?: return@launchVm
                conversationRepo.updateConversation(conversationFull.copy(title = title))
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        launchVm(onError = { reportOperationError(it) }) {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launchVm
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        launchVm(onError = { reportOperationError(it) }) {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    private fun reportOperationError(error: Throwable) {
        chatService.addError(
            error = error,
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        launchVm(onError = { reportOperationError(it) }) {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}

private const val BACKGROUND_JOB_TAIL_MAX_BYTES = 4096

/**
 * The sinks a selected verb requires in the grant's budget, mirroring the kernel's verb→sink
 * derivation ([AutomationCore.act]: SET_TEXT⇒TYPE_INTO, GLOBAL⇒GLOBAL_NAV; TAP/OBSERVE/SCROLL carry
 * no sink — an ordinary tap is verb-gated only and the submit-class SUBMIT is the separate opt-in the
 * kernel withholds). Pure so [buildPerRunGrant] cannot mint a verb without the sink that authorizes it.
 *
 * The kernel guard DENYs a non-null sink absent from the budget (`CapabilityGuard.decide`): a
 * SET_TEXT/GLOBAL grant with an empty sink budget authorizes the verb but then denies the action,
 * which is the lie this mapping closes — the sheet exposes only verbs, so the sinks MUST be derived
 * from them, never taken from a caller that could drift from (or contradict) the verb selection.
 */
internal fun requiredSinksForVerbs(verbs: Set<AutomationVerb>): Set<AutomationSink> =
    buildSet {
        if (AutomationVerb.SET_TEXT in verbs) add(AutomationSink.TYPE_INTO)
        if (AutomationVerb.GLOBAL in verbs) add(AutomationSink.GLOBAL_NAV)
        // TAP's submit-class SUBMIT is intentionally NOT minted here — it is the stricter separate
        // opt-in the kernel deliberately withholds (the kernel's `toCapability` strips it again).
    }

/**
 * Pure confirm-logic of the in-chat per-run automation grant sheet (T10). Builds the transient
 * [AutomationGrant] the user is authorizing from the live foreground package + their selected verbs,
 * TTL and step budget. The grant's sink budget is DERIVED from the verbs ([requiredSinksForVerbs]),
 * not caller-supplied, so a write/navigation verb always carries the sink the kernel guard needs to
 * authorize its action. Top-level so it is JVM-testable without the ViewModel/Android (mirrors
 * [shouldBlockSubmitForMissingModel] in ChatPage).
 *
 * Returns `null` when there is no foreground package to scope to — an in-chat grant is always scoped
 * to exactly the one app currently on screen, so without it there is nothing to authorize.
 *
 * [AutomationSink.SUBMIT] is never derived: submit-class automation is the stricter, separate opt-in
 * the kernel deliberately withholds (Boundaries: "Ask first — adding Sink.SUBMIT"), and this UX must
 * never mint it. The kernel's `toCapability` strips SUBMIT again at the lease seam regardless.
 */
internal fun buildPerRunGrant(
    foregroundPackage: String?,
    verbs: Set<AutomationVerb>,
    ttlMinutes: Int,
    maxSteps: Int,
): AutomationGrant? {
    val pkg = foregroundPackage?.takeIf { it.isNotBlank() } ?: return null
    return AutomationGrant(
        enabled = true,
        allowedPackages = setOf(pkg),
        verbs = verbs,
        sinks = requiredSinksForVerbs(verbs),
        ttlMinutes = ttlMinutes,
        maxSteps = maxSteps,
    )
}
