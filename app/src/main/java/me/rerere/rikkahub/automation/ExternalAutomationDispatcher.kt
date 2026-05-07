package me.rerere.rikkahub.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * Single dispatch point shared by [ExternalAutomationActivity] (for app-callers via Activity
 * target) and [ExternalAutomationReceiver] (for ADB callers).
 *
 * Two intent actions are recognised:
 *  - `me.rerere.rikkahub.RUN_TASK` — fire-and-forget headless run. The provided prompt
 *    runs against the user's current assistant in a fresh conversation marked headless
 *    (auto-approves tools at fire time, HARDLINE still applies). If the caller provided
 *    `return_action` + `return_package`, the dispatcher posts callback broadcasts:
 *    `accepted` immediately and a terminal `completed` / `failed` / `cancelled` /
 *    `blocked` / `rejected` broadcast when the run finishes.
 *  - `me.rerere.rikkahub.RUN_CHAT` — interactive. The activity opens the in-app chat with
 *    the prompt pre-filled but does NOT auto-send it. (Activity handles this directly —
 *    the dispatcher only handles RUN_TASK semantics.)
 *
 * Trust model:
 *  1. If `enabled` flag is OFF → reject every call with `rejected:disabled`.
 *  2. If caller package is in `trustedPackages` → run.
 *  3. Otherwise → return `pending_user_approval`. (For v1, the Activity surface shows an
 *     in-app dialog before continuing; the broadcast-receiver path silently rejects with
 *     `rejected:untrusted_caller` because we cannot show UI from a manifest-registered
 *     receiver running cold.)
 *
 * Extras keys (case-sensitive, matching spec):
 *   - `task` (string) or `task_b64` (Base64-NO_WRAP) — the prompt for RUN_TASK
 *   - `chat` (string) or `chat_b64` — the prompt for RUN_CHAT
 *   - `request_id` (string) — caller correlation id, echoed in callback
 *   - `return_action` (string) — broadcast action for callback
 *   - `return_package` (string) — package to deliver callback to
 *
 * Result codes (in callback broadcast `extra("status", String)`):
 *   - `accepted` — handed off to ChatService
 *   - `completed` — generation finished without error
 *   - `failed` — generation threw or returned an error message
 *   - `cancelled` — user cancelled mid-run
 *   - `blocked` — HARDLINE blocked a tool call inside the headless run
 *   - `rejected` — caller failed the trust gate
 */
class ExternalAutomationDispatcher(
    private val context: Context,
    private val config: ExternalAutomationConfig,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {

    sealed class TrustResult {
        data object Trusted : TrustResult()
        data object PendingUserApproval : TrustResult()
        data object Disabled : TrustResult()
    }

    suspend fun classifyCaller(callerPackage: String?): TrustResult {
        if (!config.isEnabled()) return TrustResult.Disabled
        if (callerPackage.isNullOrBlank()) return TrustResult.PendingUserApproval
        return if (callerPackage in config.trustedPackages()) TrustResult.Trusted
        else TrustResult.PendingUserApproval
    }

    /**
     * Dispatch a RUN_TASK intent. Pre-condition: caller is trusted (or user just approved
     * via the activity dialog). Logs the invocation, kicks off the generation in a
     * background coroutine, and posts callback broadcasts if [returnAction]/[returnPackage]
     * were provided.
     *
     * Returns a stable status string the caller can show / log immediately. The actual
     * generation runs async and produces its terminal status via the callback path.
     */
    suspend fun dispatchTask(
        prompt: String,
        callerPackage: String,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
    ): String {
        val parsedPrompt = prompt.trim()
        if (parsedPrompt.isEmpty()) {
            log(callerPackage, "RUN_TASK", "rejected:empty_prompt", requestId)
            sendCallback(returnAction, returnPackage, requestId, "rejected", "empty prompt")
            return "rejected:empty_prompt"
        }

        sendCallback(returnAction, returnPackage, requestId, "accepted", null)
        log(callerPackage, "RUN_TASK", "accepted", requestId)

        appScope.launch(Dispatchers.IO) {
            runHeadless(parsedPrompt, requestId, returnAction, returnPackage)
        }

        return "accepted"
    }

    private suspend fun runHeadless(
        prompt: String,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
    ) {
        val assistant = withContext(Dispatchers.IO) {
            settingsStore.settingsFlow.first().getCurrentAssistant()
        }
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = assistant.id,
            newConversation = true,
        ).copy(title = "[External] ${prompt.take(40).ifBlank { "(empty)" }}")
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        try {
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(prompt)))
            val finished = withTimeoutOrNull(15L * 60_000L) {
                chatService.getGenerationJobStateFlow(conv.id).first { it == null }
            }
            if (finished == null) {
                sendCallback(returnAction, returnPackage, requestId, "failed", "exceeded 15-minute generation cap")
            } else {
                sendCallback(returnAction, returnPackage, requestId, "completed", null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "external automation run failed", t)
            sendCallback(returnAction, returnPackage, requestId, "failed", "${t::class.simpleName}: ${t.message.orEmpty()}")
        } finally {
            HeadlessConversations.unmark(conv.id)
        }
    }

    /** Convenience for the receiver path that already has classified rejection status. */
    suspend fun rejectAndCallback(
        callerPackage: String,
        action: String,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
        reason: String,
    ) {
        log(callerPackage, action, "rejected:$reason", requestId)
        sendCallback(returnAction, returnPackage, requestId, "rejected", reason)
    }

    private fun sendCallback(
        returnAction: String?,
        returnPackage: String?,
        requestId: String?,
        status: String,
        message: String?,
    ) {
        if (returnAction.isNullOrBlank() || returnPackage.isNullOrBlank()) return
        val intent = Intent(returnAction).apply {
            setPackage(returnPackage)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_REQUEST_ID, requestId.orEmpty())
            if (!message.isNullOrBlank()) putExtra(EXTRA_MESSAGE, message)
            // FLAG_INCLUDE_STOPPED_PACKAGES so the callback reaches Tasker even when it's
            // currently background-frozen (Tasker has the manifest-receiver path enabled,
            // but stopped-package state can still drop the broadcast otherwise).
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        runCatching { context.sendBroadcast(intent) }.onFailure {
            Log.w(TAG, "callback broadcast failed", it)
        }
    }

    private suspend fun log(
        callerPackage: String,
        action: String,
        status: String,
        requestId: String?,
    ) {
        config.logInvocation(
            ExternalAutomationConfig.InvocationLog(
                timestampMs = System.currentTimeMillis(),
                callerPackage = callerPackage,
                action = action,
                status = status,
                requestId = requestId,
            )
        )
    }

    companion object {
        const val ACTION_RUN_TASK = "me.rerere.rikkahub.RUN_TASK"
        const val ACTION_RUN_CHAT = "me.rerere.rikkahub.RUN_CHAT"
        const val EXTRA_TASK = "task"
        const val EXTRA_TASK_B64 = "task_b64"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_CHAT_B64 = "chat_b64"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RETURN_ACTION = "return_action"
        const val EXTRA_RETURN_PACKAGE = "return_package"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        private const val TAG = "ExtAutomation"

        /**
         * Pull the prompt out of an intent. Spec mandates support for both raw string and
         * Base64-NO_WRAP encoded forms (Tasker has historically corrupted unicode strings,
         * so the b64 form is the safe path).
         */
        fun extractPrompt(intent: Intent, rawKey: String, b64Key: String): String? =
            extractPromptStrings(
                raw = intent.getStringExtra(rawKey),
                base64Encoded = intent.getStringExtra(b64Key),
            )

        /**
         * Pure-logic version of [extractPrompt] taking the two string values directly. Lets
         * us unit-test the precedence + base64-decode logic on the JVM without an Intent.
         * Public-internal so the extractor stays a single source of truth across the
         * Activity, Receiver, and tests.
         */
        @JvmStatic
        fun extractPromptStrings(raw: String?, base64Encoded: String?): String? {
            raw?.takeIf { it.isNotBlank() }?.let { return it }
            base64Encoded?.takeIf { it.isNotBlank() }?.let { encoded ->
                return runCatching {
                    String(java.util.Base64.getUrlDecoder().decode(encoded))
                }.recoverCatching {
                    String(java.util.Base64.getDecoder().decode(encoded))
                }.getOrNull()
            }
            return null
        }
    }
}
