package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.TelegramChatEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.TelegramChatRepository
import me.rerere.rikkahub.data.telegram.AttachmentKind
import me.rerere.rikkahub.data.telegram.TelegramApiException
import me.rerere.rikkahub.data.telegram.TelegramAttachment
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.data.telegram.TelegramCallbackQuery
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import me.rerere.rikkahub.data.telegram.TelegramIncomingMessage
import me.rerere.rikkahub.data.telegram.parseCallbackQuery
import me.rerere.rikkahub.data.telegram.parseIncoming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.android.ext.android.inject

/**
 * Foreground service that long-polls Telegram for incoming messages and routes them into the
 * existing chat pipeline so the LLM can reply, with full tool-calling.
 *
 * Outbound: TelegramTool (LLM-callable) + this service's outbound helpers exposed via the
 * shared singleton client.
 *
 * Lifecycle: started by TelegramTool's enable_telegram_bot, stopped by disable_telegram_bot,
 * also re-started after device boot via CronBootReceiver if config.enabled was true.
 */
class TelegramBotService : Service() {

    private val prefs: TelegramBotPreferences by inject()
    private val client: TelegramBotClient by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val chatRepo: TelegramChatRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var externalGenPumpJob: Job? = null

    /**
     * Per-chat serialization for non-built-in messages. The poll loop launches each
     * inbound message in its own coroutine (so it can return to getUpdates immediately
     * and pick up /stop while a long generation is in flight), but two LLM round-trips
     * for the same chat must NOT interleave. Built-in slash commands skip this mutex
     * entirely so /stop and /new run the moment they arrive.
     */
    private val chatMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private fun mutexFor(chatId: Long): Mutex = chatMutexes.getOrPut(chatId) { Mutex() }

    /**
     * Per-toolCallId mutex used to serialise inline-keyboard tap callbacks for the SAME
     * approval prompt. Without this, two whitelisted users tapping different scope
     * buttons on the same prompt within ~50ms each pass the `tool.isPending` check
     * (a snapshot read) and both call handleToolApproval; the second cancel()
     * interrupts the first's resume coroutine and the recorded scope can flip silently.
     */
    private val approvalMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun approvalMutexFor(toolCallId: String): Mutex =
        approvalMutexes.getOrPut(toolCallId) { Mutex() }

    /**
     * Tracks the active handleLlmTurn coroutine per chat so /stop and /new can cancel it
     * directly. Without this, a stop/reset cancels the ChatService generation job but
     * the handleLlmTurn loop stays parked on `getGenerationJobStateFlow(...).first { it != null }`
     * waiting forever for a generation that won't come — holding the per-chat mutex —
     * so every subsequent message bounces off `tryLock` with "previous turn waiting".
     * Recovery used to require force-stopping the app.
     */
    private val turnJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /**
     * Conversations whose generation pump is currently being driven by an active
     * [handleIncoming] (i.e. a real Telegram-incoming-message turn). Used by the external
     * generation-done listener to AVOID double-pumping: when handleIncoming finishes a
     * turn, it streams the final text to Telegram itself, so the listener must skip.
     *
     * The listener fires for the OTHER case: a generation that wasn't kicked off by an
     * inbound Telegram message — e.g. the [SubAgentEngine.notifyParentIfBackground] wake
     * message, where a sub-agent finished and posted a synthetic user message into the
     * parent's conversation. Without this listener, the parent's LLM responds silently
     * to the wake — its reply lands in the in-app chat history but never reaches Telegram.
     */
    private val activeHandleIncomingConvs: java.util.concurrent.ConcurrentHashMap.KeySetView<Uuid, Boolean> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Do NOT call startForeground here. Android 12+ ForegroundServiceStartNotAllowedException
        // will fire on any auto-revive (START_STICKY / process restart / etc.) because the
        // foreground "ticket" only exists when the service was explicitly started via
        // startForegroundService() from a user-engaged context. We promote in onStartCommand.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: Throwable) {
            // Most likely on Android 12+ ForegroundServiceStartNotAllowedException after
            // process revive without a fresh foreground ticket, OR SecurityException on
            // Android 14+ if the FOREGROUND_SERVICE_<TYPE> permission isn't declared.
            // Either way: log loudly so we don't fail silently again, then stop.
            android.util.Log.e("TelegramBotService", "startForeground failed; service will not run", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        // Refresh the slash-command menu Telegram shows users every time the bot starts
        // (and any time the BUILT_IN_COMMANDS list changes between releases). Idempotent
        // and cheap; failures are logged but not fatal.
        scope.launch { registerBuiltInCommandsWithTelegram() }
        // External generation-done pump: when a chat-mapped conversation finishes a
        // generation that wasn't kicked off by handleIncoming (e.g. a sub-agent wake), push
        // the assistant's reply text to Telegram so the user sees it without polling.
        // GUARDED — onStartCommand runs many times in a session (service revive, APK
        // reinstall during dev, bot re-toggle). Without this guard, every call spawns a
        // fresh listener; each generationDoneFlow emit triggers ALL listeners; the chat
        // gets N duplicate replies for one sub-agent wake.
        if (externalGenPumpJob?.isActive != true) {
            externalGenPumpJob = scope.launch { listenForExternalGenerationCompletions() }
        }
        isRunning = true
        // START_NOT_STICKY: don't auto-revive; revival without a fresh foreground ticket would
        // crash with the same exception. The boot receiver, RikkaHubApp.onCreate, and the user
        // re-enable cover the legitimate restart paths.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Telegram bot", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notif = buildForegroundNotification()
        // Android 14+ ties the runtime FGS type to the manifest declaration AND to a
        // hard daily-budget cap depending on the type. We previously used DATA_SYNC, which
        // caps at 6 hours/day per app — Telegram bot polling is intended to run
        // indefinitely, so we'd hit ForegroundServiceDidNotStopInTimeException. SPECIAL_USE
        // has no such cap (the manifest declares the subtype "long-running Telegram bot
        // long-poll loop") and is the correct flavor for app-specific long-running work
        // that doesn't fit any predefined Google category.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * Build the foreground notification. If the strict whitelist has rejected at least one
     * sender, surface the most recent rejected sender_id in the body so the user has a way
     * to bootstrap an empty whitelist without spelunking through logcat.
     */
    private fun buildForegroundNotification(): android.app.Notification {
        val rejected = RejectedSenderLog.latest()
        val body = if (rejected != null) {
            "Rejected sender ${rejected.senderId} (chat ${rejected.chatId}). Add to whitelist if that was you."
        } else {
            "Routing inbound messages to RikkaHub"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram bot listening")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notif_telegram)
            .setOngoing(true)
            .build()
    }

    /** Re-render the notification in place. Cheap (single NotificationManager call). */
    private fun updateForegroundNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildForegroundNotification())
        } catch (_: Throwable) { /* notifications can fail in restricted contexts; non-fatal */ }
    }

    /**
     * Long-poll Telegram, dispatch messages, advance offset. Acquires a partial WakeLock
     * around each getUpdates request so the CPU and network stay alive during Doze
     * maintenance windows on OEM-aggressive ROMs (Xiaomi/OPPO/OnePlus/Vivo). Without this,
     * the long-poll connection sits idle on a sleeping CPU and updates only arrive during
     * the device's brief maintenance bursts.
     */
    private suspend fun pollLoop() {
        android.util.Log.i(TAG, "pollLoop: starting")
        val pm = applicationContext.getSystemService(android.os.PowerManager::class.java)
        var offset = 0L
        var cycle = 0L
        // Exponential backoff state. Bumped on every transient error, reset on every
        // successful cycle. Without this, a brief network blip used to spin every 5s
        // forever; now we back off to a max of 2 minutes between retries.
        var consecutiveErrors = 0
        // Bot-token tracking: if `telegram_set_token` writes a new value mid-loop the
        // offset must reset (different bot, different update_id space). Without this,
        // the new bot starts at offset = old-bot's-last + 1 which is always large
        // enough that legitimate inbound updates get skipped.
        var lastTokenSeen: String? = null
        while (true) {
            val cfg = try { prefs.current() } catch (e: Throwable) {
                android.util.Log.e(TAG, "pollLoop: prefs.current() failed", e); null
            }
            if (cfg == null || !cfg.isUsable) {
                android.util.Log.w(TAG, "pollLoop: cfg unusable (token_set=${cfg?.token?.isNotBlank()} enabled=${cfg?.enabled}); stopping")
                stopSelf(); return
            }
            if (lastTokenSeen != null && lastTokenSeen != cfg.token) {
                android.util.Log.i(TAG, "pollLoop: token changed; resetting offset")
                offset = 0L
                consecutiveErrors = 0
            }
            lastTokenSeen = cfg.token
            // Held only for the duration of one long-poll cycle. Released in finally so a
            // crash during getUpdates does not leak the wakelock.
            val wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "rikkahub:telegram_long_poll",
            )?.also { it.setReferenceCounted(false) }
            try {
                cycle++
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                val updates = client.getUpdates(offset, 30)
                if (cycle <= 2 || updates.isNotEmpty()) {
                    android.util.Log.i(TAG, "pollLoop: cycle=$cycle offset=$offset updates=${updates.size}")
                }
                for (u in updates.map { it as kotlinx.serialization.json.JsonObject }) {
                    // Bump the offset BEFORE deciding whether to dispatch this update.
                    // Without this, any update parseIncoming returns null for (callback_query,
                    // edited_message, content-less message, etc.) keeps its update_id, so
                    // Telegram returns the same update on the next getUpdates and the loop
                    // re-processes it forever. This caused an infinite re-poll the first
                    // time a non-message update slipped past the allowed_updates filter.
                    val updateId = u["update_id"]?.jsonPrimitive?.longOrNull
                    if (updateId != null && updateId >= offset) offset = updateId + 1
                    val incoming = parseIncoming(u)
                    if (incoming != null) {
                        android.util.Log.i(TAG, "pollLoop: dispatching message ${incoming.messageId} from chat=${incoming.chatId} sender=${incoming.senderId}")
                        scope.launch {
                            try { handleIncoming(cfg, incoming) }
                            catch (e: Throwable) { android.util.Log.e(TAG, "handleIncoming threw for message ${incoming.messageId}", e) }
                        }
                        continue
                    }
                    val cq = parseCallbackQuery(u)
                    if (cq != null) {
                        android.util.Log.i(TAG, "pollLoop: dispatching callback_query ${cq.callbackQueryId} from chat=${cq.chatId} sender=${cq.senderId}")
                        scope.launch {
                            try { handleCallbackQuery(cfg, cq) }
                            catch (e: Throwable) { android.util.Log.e(TAG, "handleCallbackQuery threw for ${cq.callbackQueryId}", e) }
                        }
                        continue
                    }
                    // Unknown update type — offset already bumped, just drop it.
                }
                // Successful cycle: reset the backoff counter so a transient blip doesn't
                // permanently elevate the retry delay.
                consecutiveErrors = 0
            } catch (e: TelegramApiException) {
                android.util.Log.e(TAG, "pollLoop: telegram api error ${e.errorCode}: ${e.description}", e)
                if (e.errorCode == 401 || e.errorCode == 404) {
                    // Token revoked / wrong / bot deleted. Spinning every 5s forever burns
                    // battery + Telegram quota for no recovery — only the user can fix this
                    // by setting a new token. Disable the bot, surface a notification, and
                    // stop the service cleanly.
                    android.util.Log.w(TAG, "pollLoop: bailing out; bot token rejected (${e.errorCode})")
                    runCatching { prefs.update { it.copy(enabled = false) } }
                    postTokenInvalidNotification(e.errorCode, e.description ?: "Telegram rejected the token")
                    stopSelf(); return
                }
                consecutiveErrors++
                delay(computeBackoffMs(consecutiveErrors))
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "pollLoop: unexpected error in cycle=$cycle", e)
                consecutiveErrors++
                delay(computeBackoffMs(consecutiveErrors))
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Throwable) {}
            }
        }
    }

    /** Capped exponential backoff: 5s, 10s, 20s, 40s, 80s, 120s (capped). */
    private fun computeBackoffMs(consecutiveErrors: Int): Long {
        val base = 5_000L
        val cap = 120_000L
        if (consecutiveErrors <= 0) return base
        val shift = (consecutiveErrors - 1).coerceAtMost(20)
        val computed = base shl shift
        return computed.coerceAtMost(cap)
    }

    /**
     * Long-lived listener for generations completing on chat-mapped conversations that
     * AREN'T currently being pumped by [handleIncoming]. Without this, sub-agent wake
     * messages — posted via [SubAgentEngine.notifyParentIfBackground] →
     * `chatService.sendMessage(parentConvId, …)` — kick off a generation in the parent's
     * Telegram conversation, but its assistant reply never reaches Telegram because
     * handleIncoming isn't running for that turn.
     *
     * The listener stays alive for the lifetime of the foreground service. Errors are
     * logged but do not break the loop — a single bad emit shouldn't kill our pump.
     */
    private suspend fun listenForExternalGenerationCompletions() {
        chatService.generationDoneFlow.collect { convId ->
            // Skip if handleIncoming is driving this turn — it streams + finalises the
            // assistant text to Telegram on its own.
            if (convId in activeHandleIncomingConvs) return@collect
            // Find the chat this conv is bound to. If none, the conv isn't a Telegram
            // conv; nothing to do.
            val mapping = runCatching { chatRepo.getByConversationId(convId.toString()) }
                .getOrNull() ?: return@collect

            // Harvest the most-recent assistant text. Mirrors SubAgentEngine.harvestFinalText
            // but pulls from the live in-memory state when available so we get whatever the
            // last gen actually produced (DB write may lag a few hundred ms behind).
            val text = runCatching {
                val conv = chatService.getConversationFlow(convId).value
                val selected = conv.messageNodes.mapNotNull { it.messages.getOrNull(it.selectIndex) }
                val lastAssistant = selected.lastOrNull { it.role.name.equals("assistant", ignoreCase = true) }
                lastAssistant?.parts
                    ?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")

            if (text.isBlank()) {
                android.util.Log.i(TAG, "external-gen-pump: convId=$convId → empty assistant text, skipping")
                return@collect
            }
            runCatching {
                sendChunked(mapping.chatId, text, replyTo = null)
                android.util.Log.i(TAG, "external-gen-pump: convId=$convId → pushed ${text.length} chars to Telegram chat ${mapping.chatId}")
            }.onFailure {
                android.util.Log.w(TAG, "external-gen-pump: send failed for convId=$convId", it)
            }
        }
    }

    /**
     * Surface a notification when the bot bails out due to an invalid token. The user has
     * no other way to discover that we stopped: the foreground service is gone, the next
     * inbound message would hit a dead bot, and the auto-revive paths only retry when the
     * bot is `enabled=true` (which we've just flipped to false).
     */
    private fun postTokenInvalidNotification(errorCode: Int, description: String) {
        try {
            val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "rikkahub_telegram_token_invalid"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "Telegram bot errors",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val builder = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
                .setContentTitle("Telegram bot disabled")
                .setContentText("Token rejected (HTTP $errorCode). Set a new token in Settings → Telegram bot.")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    "Telegram returned $errorCode: $description. The bot has been disabled to stop retrying. Set a new token in Settings → Telegram bot, then re-enable."
                ))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
            androidx.core.app.NotificationManagerCompat.from(applicationContext)
                .notify(101, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silent failure is fine
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "postTokenInvalidNotification failed", e)
        }
    }

    private suspend fun handleIncoming(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        m: TelegramIncomingMessage,
    ) {
        val sender = m.senderId ?: run {
            android.util.Log.w(TAG, "handleIncoming: dropping — no sender id")
            return
        }
        // Strict whitelist: nobody is allowed unless their sender_id (or the chat_id, for
        // group routing) appears in cfg.whitelist. An empty whitelist drops everything —
        // that matches the UI's "Empty = nobody" promise. Without this, a freshly-enabled
        // bot whose owner forgot to fill in the whitelist would happily accept messages
        // from any random Telegram user who knows the bot's @username.
        if (sender !in cfg.whitelist && m.chatId !in cfg.whitelist) {
            android.util.Log.w(TAG, "handleIncoming: dropping — sender=$sender chat=${m.chatId} not in whitelist=${cfg.whitelist}")
            // Stash the rejected sender so the foreground notification + UI can surface it.
            // First-time setup needs SOME way to discover the user's own chat_id, since
            // BotFather doesn't give it to you and the strict-whitelist policy means you
            // can't "just send a message and check the logs" anymore.
            RejectedSenderLog.record(sender, m.chatId)
            updateForegroundNotification()
            return
        }
        // Built-in slash commands. These are handled entirely on-device — they never reach
        // the LLM, never spend tokens, and resolve in single-digit milliseconds. Critically,
        // they are NOT serialized behind any in-flight LLM call: that's what makes /stop
        // and /new actually responsive while a long generation is running.
        if (m.text.startsWith("/")) {
            if (handleBuiltInCommand(cfg, m)) return
        }

        // Non-built-in messages run the LLM and must be serialized per-chat so two model
        // turns from the same chat don't interleave. Built-ins above ran without this lock
        // by design — that's the whole point of the fix.
        //
        // tryLock-with-timeout (vs the earlier blocking withLock) is what keeps the user
        // informed when a previous turn is paused on tool approval. Without this, sending
        // a follow-up message to a chat with an outstanding approval prompt sits silently
        // in the queue forever — the user has no idea their second message wasn't dropped
        // and not received.
        val mutex = mutexFor(m.chatId)
        var acquired = mutex.tryLock()
        if (!acquired) {
            // The prior turn is parked. Two cases where a fresh user message is the
            // implicit "abandon that, do this instead" and we should auto-stop:
            //   (1) Pending approval keyboard sitting unanswered.
            //   (2) Tool already approved + currently RUNNING — typically a tool that
            //       backgrounded the app (take_photo to the camera, launch_app, the 6
            //       system intents) and is now waiting on an activity result. The user
            //       came back to the chat and started typing instead of finishing the
            //       activity → they changed their mind. Without this case the new
            //       message bounces with "previous turn still generating", which is
            //       confusing because no tokens are actually being generated.
            val stuckOnApproval = ApprovalPromptRegistry.snapshotForChat(m.chatId).isNotEmpty()
            val stuckOnRunningTool = !stuckOnApproval && hasInFlightApprovedTool(m.chatId)
            if (stuckOnApproval || stuckOnRunningTool) {
                autoCancelStuckTurn(m.chatId)
                acquired = mutex.tryLock()
            }
            if (!acquired) {
                try {
                    client.sendMessage(
                        chatId = m.chatId,
                        text = "⏳ A previous turn is still generating. Send /stop to cancel it.",
                        replyToMessageId = m.messageId,
                    )
                } catch (_: Throwable) {}
                return
            }
        }
        // Register THIS coroutine as the chat's active turn so /stop and /new can cancel
        // it. Capture the parent Job from the running coroutine context. On exit (normal,
        // throw, or cancellation) the finally clears the registry and releases the mutex.
        val turnJob = currentCoroutineContext()[Job]
        if (turnJob != null) turnJobs[m.chatId] = turnJob
        try {
            handleLlmTurn(cfg, m)
        } finally {
            // remove ONLY the entry that's still us, in case /new replaced it concurrently
            turnJob?.let { turnJobs.remove(m.chatId, it) }
            mutex.unlock()
        }
    }

    /**
     * Body of an LLM-bound turn. Extracted from handleIncoming so the per-chat mutex wraps
     * exactly the section that owns the conversation/generation state. While this runs the
     * poll loop is still free to receive new messages and dispatch /stop into its own
     * coroutine, which calls chatService.stopGeneration(convId) and unblocks us.
     */
    private suspend fun handleLlmTurn(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        m: TelegramIncomingMessage,
    ) {
        val (convId, wasCreated) = lookupOrCreateConversation(cfg, m.chatId)
        android.util.Log.i(TAG, "handleIncoming: routing to conv=$convId wasCreated=$wasCreated text='${m.text.take(80)}' photos=${m.photoFileIds.size}")
        // UX: tell Telegram "the bot is typing" so the user sees activity while we generate.
        try { client.sendChatAction(m.chatId, "typing") } catch (_: Throwable) {}
        chatService.initializeConversation(convId)
        // Mark this conv browser-headless so browser tools route through
        // HeadlessBrowserSessionPool (no Activity launch on the user's phone) and
        // `streamScreenshotIfHeadless` actually streams to Telegram. Use the
        // browser-only variant — the bot HAS an approval channel (inline-keyboard
        // prompts via promptForToolApproval), so tool calls must still flow through
        // the per-tool approval gate. Using the full mark() here would silently
        // bypass the approval flow because ChatService.isToolAutoApproved checks
        // shouldAutoApprove(), which the full mark sets true.
        HeadlessConversations.markBrowserHeadless(convId)
        // Register the agent-context preamble as a SYSTEM addendum (sent once per
        // generation by GenerationHandler) instead of prepending it to the user message.
        // The previous design persisted the preamble inside `UIMessagePart.Text` so it
        // got replayed on every subsequent turn AND every agentic-loop step, burning
        // ~80 tokens × turn count of pure duplication.
        // Detect audio-class attachments BEFORE building the preamble so we can inject the
        // whisper_status hint in the same addendum. Voice / audio / video_note all count.
        val hasAudioAttachment = m.attachments.any { att ->
            att.kind == AttachmentKind.VOICE ||
            att.kind == AttachmentKind.AUDIO ||
            att.kind == AttachmentKind.VIDEO_NOTE
        }
        val hasPhotoAttachment = m.photoFileIds.isNotEmpty()
        me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum.set(
            convId,
            buildAgentContextPreamble(cfg, m.chatId, wasCreated, hasAudioAttachment, hasPhotoAttachment),
        )
        // Download any inbound photos to the per-chat shared-storage inbox. They are attached as
        // UIMessagePart.Image for the vision pipeline (FileEncoder reads file:// only) AND their
        // saved paths are surfaced in the message text via buildPhotoNote, so a text-only model
        // can still OCR / process them through file tools or Termux.
        val (imageParts, photoPaths) = downloadInboundPhotos(m.chatId, m.photoFileIds)
        val photoNote = buildPhotoNote(photoPaths)

        // Download non-photo attachments (documents, audio, video, voice, video_note) to
        // /sdcard/Download/telegram_inbox/<chatId>/ and build a structured note for the LLM.
        val downloadedAttachments = downloadInboundAttachments(m.chatId, m.attachments)
        val attachmentNote = buildAttachmentNote(downloadedAttachments)

        val parts = buildList<UIMessagePart> {
            addAll(imageParts)
            // Build the user-visible text by joining the typed message with the structured
            // photo / attachment notes for whatever arrived alongside it.
            val combinedText = listOf(m.text, photoNote, attachmentNote)
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
            // Only emit a Text part when there is actual content; an empty text triggers
            // the "no reply" UX downstream and confuses the LLM.
            if (combinedText.isNotEmpty()) add(UIMessagePart.Text(combinedText))
            else if (imageParts.isNotEmpty()) add(UIMessagePart.Text("(photo)"))
        }
        // Snapshot the conversation BEFORE sending so the streaming render can ignore the
        // previous turn's assistant content. Without this baseline, getGenerationJobStateFlow
        // briefly emits null between turns and `first { it == null }` returns immediately;
        // the placeholder then gets edited with the previous reply and finalized in that
        // stale state. Tracking the baseline lets us wait for the NEW turn to actually start
        // before declaring it done.
        val baselineMessageCount = conversationRepo.getConversationById(convId)
            ?.currentMessages?.size ?: 0

        chatService.sendMessage(convId, parts)

        // Send the streaming placeholder once. The per-iteration editJob below edits it
        // in place during active generation, then stops while we wait on the user (so we
        // don't burn battery firing edits every 600ms for hours if the user is away).
        val placeholderId: Long? = try {
            val res = client.sendMessage(
                chatId = m.chatId,
                text = STREAM_PLACEHOLDER,
                replyToMessageId = m.messageId,
            )
            res["message_id"]?.jsonPrimitive?.longOrNull
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleIncoming: placeholder send failed", e)
            null
        }

        // Hold a session reference for the entire handleLlmTurn so the per-conversation
        // session isn't reaped by ChatService's onIdle callback while we're waiting on
        // the user to tap an approval button. Without this, the user-tap callback would
        // arrive AFTER the session was cleaned up, getOrCreateSession would build a fresh
        // empty Conversation, and the toolCallId lookup would return null ("tool no
        // longer active"). Released in finally regardless of how we exit.
        chatService.addConversationReference(convId)
        // Mark this conv as "Telegram-pumped right now" so the external generation-done
        // listener doesn't ALSO push a copy of the assistant text — handleIncoming
        // already streams + finalises it on its own.
        activeHandleIncomingConvs.add(convId)
        // Tools we've already prompted for in this turn — tracked so a single tool call
        // doesn't get a second approval bubble if the loop re-enters before the user
        // resolves it.
        val promptedToolCallIds = mutableSetOf<String>()
        var iteration = 0
        // Captured when the generation flow throws (e.g. provider returns 4xx with a body
        // like "this model does not support image input"). Surfaced in the empty-reply
        // branch so the user sees the actual cause instead of the generic
        // "(model called tools but didn't reply)" hint.
        var generationError: Throwable? = null
        try {
            // Loop: wait for the current generation to finish, look for any tool calls in
            // Pending state (LLM wants approval to run them), send approval prompts with
            // inline keyboards, and loop back when the user's tap restarts generation.
            // Exits when generation completes with no Pending tools left.
            while (true) {
                // First iteration: 10s cold-start window covers the race between
                // chatService.sendMessage and the job actually starting.
                // Subsequent iterations: we've sent approval prompts and are waiting on
                // the user — there's NO timeout. The user might be away from the phone
                // for hours; the prompt stays valid until they tap (or until app restart
                // since the session is process-local). /stop still cancels via the
                // built-in fast-path that bypasses the per-chat mutex.
                val firstActive = if (iteration == 0) {
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        chatService.getGenerationJobStateFlow(convId).first { it != null }
                    }
                } else {
                    chatService.getGenerationJobStateFlow(convId).first { it != null }
                }
                if (firstActive == null) break  // cold-start safety net only

                // Generation is now active — start the streaming jobs for THIS cycle and
                // tear them down the moment it pauses. Without this, the typing indicator
                // and edit attempts would keep firing every few seconds during the
                // user's possibly-long approval wait, wasting battery and Telegram quota.
                val typingJob = scope.launch {
                    while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                        try { client.sendChatAction(m.chatId, "typing") } catch (_: Throwable) {}
                        delay(4_000)
                    }
                }
                val editJob = if (placeholderId != null) scope.launch {
                    var lastSent = ""
                    var lastEditAtMs = 0L
                    while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                        // Sleep in short steps so a fast burst of new content (long token
                        // chunk landing all at once) can wake the loop early via the
                        // burst threshold, instead of being stuck behind the full cadence.
                        val pollSliceMs = 80L
                        var sliceWaitedMs = 0L
                        while (sliceWaitedMs < STREAM_EDIT_INTERVAL_MS) {
                            delay(pollSliceMs)
                            sliceWaitedMs += pollSliceMs
                            val peek = renderAssistantStream(convId, finalizing = false, baselineMessageCount)
                            val grew = peek.length - lastSent.length
                            if (grew >= STREAM_EDIT_BURST_THRESHOLD_CHARS) {
                                val sinceLast = System.currentTimeMillis() - lastEditAtMs
                                if (sinceLast >= STREAM_EDIT_MIN_GAP_MS) break
                            }
                        }
                        val rendered = renderAssistantStream(convId, finalizing = false, baselineMessageCount)
                        if (rendered.isBlank() || rendered == lastSent) continue
                        val cappedRaw = truncateForLiveEdit(rendered, MAX_CHARS)
                        val capped = TelegramHtmlRenderer.render(cappedRaw)
                        val ok = try {
                            client.editMessageText(m.chatId, placeholderId, capped, parseMode = PARSE_MODE_HTML) != null
                        } catch (_: Throwable) {
                            try {
                                client.editMessageText(m.chatId, placeholderId, TelegramHtmlRenderer.stripHtml(capped), parseMode = null) != null
                            } catch (_: Throwable) { false }
                        }
                        if (ok) {
                            lastSent = rendered
                            lastEditAtMs = System.currentTimeMillis()
                        }
                    }
                } else null

                try {
                    // Wait for THIS job to complete (model produced reply OR paused for approval).
                    chatService.getGenerationJobStateFlow(convId).first { it == null }
                } finally {
                    // cancelAndJoin (vs plain cancel) waits for any in-flight HTTP edit
                    // to actually finish, so a stale streaming edit can't land after the
                    // final reply and clobber the placeholder back to "running…".
                    try { typingJob.cancelAndJoin() } catch (_: Throwable) {}
                    try { editJob?.cancelAndJoin() } catch (_: Throwable) {}
                }

                // Enumerate any tool calls now sitting in Pending. Anything we haven't
                // already sent a keyboard for gets a fresh approval prompt.
                val pendingTools = chatService.getConversationFlow(convId).value
                    .currentMessages.drop(baselineMessageCount)
                    .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
                    .filter { it.isPending }
                if (pendingTools.isEmpty()) break  // no approvals needed → done

                for (tool in pendingTools) {
                    if (tool.toolCallId in promptedToolCallIds) continue
                    promptedToolCallIds += tool.toolCallId
                    // Retry circuit breaker: if the same tool has already returned an
                    // error envelope >=3 times in this turn, auto-deny the next call
                    // instead of asking the user to approve another doomed retry. The
                    // model gets back a synthetic envelope and is forced to give up +
                    // report. Stops approval-flood when a smaller model ignores the
                    // "one failed call is information, not a retry" rule from SOUL.
                    val recentFailures = recentFailedRunsOf(convId, tool.toolName, baselineMessageCount)
                    if (recentFailures >= 3) {
                        scope.launch {
                            chatService.handleToolApproval(
                                conversationId = convId,
                                toolCallId = tool.toolCallId,
                                approved = false,
                                reason = "aborted_repeated_failure: ${tool.toolName} returned an error $recentFailures times in this turn — stop retrying and report to the user.",
                                toolName = tool.toolName,
                            )
                            try {
                                client.sendMessage(
                                    m.chatId,
                                    "⛔ Auto-denied: <code>${TelegramHtmlRenderer.escape(tool.toolName)}</code> already failed ${recentFailures}× this turn. Telling the model to stop retrying.",
                                    parseMode = PARSE_MODE_HTML,
                                )
                            } catch (_: Throwable) {}
                        }
                        continue
                    }
                    scope.launch { sendApprovalPrompt(m.chatId, tool) }
                }
                iteration++
                // Loop back; the next first { it != null } waits indefinitely for the
                // user's tap (which restarts generation via handleToolApproval).
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine was cancelled (e.g. /stop or /new) — re-throw so the coroutine
            // machinery marks this job as cancelled. The finally below still runs for cleanup.
            // We must NOT swallow CancellationException: if we do, the post-loop finalisation
            // block (renderAssistantStream → sendChunked) fires on stale state after cancel,
            // potentially sending a partial/incorrect reply to Telegram.
            android.util.Log.i(TAG, "handleIncoming: cancelled (CancellationException) — not sending final reply")
            throw e
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleIncoming: generation flow ended with error", e)
            generationError = e
        } finally {
            chatService.removeConversationReference(convId)
            activeHandleIncomingConvs.remove(convId)
            HeadlessConversations.unmark(convId)
        }

        val finalReply = renderAssistantStream(convId, finalizing = true, baselineMessageCount)
        android.util.Log.i(TAG, "handleIncoming: finalizing ${finalReply.length} chars to chat=${m.chatId}")

        when {
            finalReply.isBlank() -> {
                // The model called tool(s) but emitted no final user-facing text. We try
                // to RESCUE useful artifacts before falling back to a textual hint:
                //   - take_screenshot / take_photo wrote a file the user almost certainly
                //     wanted to see — auto-send the photo to Telegram with a caption that
                //     makes the rescue obvious. Without this, "show me the screenshot"
                //     produces a take_screenshot call but no telegram_send_photo and the
                //     user just sees "(model didn't reply)" — burying the file they asked
                //     for behind a generic fallback string.
                android.util.Log.i(
                    TAG,
                    "handleIncoming: empty final reply (model finished with no text after tool calls) chat=${m.chatId}",
                )
                val rescued = tryRescueImageFromTurn(convId, baselineMessageCount, m.chatId)
                if (!rescued) {
                    // If generation actually threw (provider 4xx, network error, hardline
                    // block, etc.), surface the cause to the user instead of the generic
                    // "no reply" hint. Most provider errors include a clear message body
                    // like "this model does not support image input" — which is exactly
                    // what the user needs to act on (switch model, drop attachment, etc.).
                    val fallback = generationError?.let { e ->
                        val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
                        // Cap to leave room for the prefix and not blow Telegram's 4096
                        // limit on an unbounded provider-error blob.
                        val capped = if (msg.length <= 600) msg else msg.take(600) + "…"
                        "⚠️ Generation failed: $capped"
                    } ?: "(model called tools but didn't reply — try saying \"continue\", switch model with /model, or reset with /new)"
                    if (placeholderId != null) {
                        try { client.editMessageText(m.chatId, placeholderId, fallback) } catch (_: Throwable) {}
                    } else {
                        try { client.sendMessage(m.chatId, fallback) } catch (_: Throwable) {}
                    }
                } else if (placeholderId != null) {
                    // We rescued the photo into a fresh Telegram message — clean up the
                    // placeholder so the chat doesn't show a leftover "thinking…" line.
                    try { client.deleteMessage(m.chatId, placeholderId) } catch (_: Throwable) {}
                }
            }
            placeholderId != null && finalReply.length <= MAX_CHARS -> {
                // Final fits in one message; just edit the placeholder one last time.
                editPlaceholderHtmlWithFallback(m.chatId, placeholderId, finalReply)
            }
            else -> {
                // Final reply overflowed Telegram's per-message limit. Drop the placeholder
                // (delete or fold it into the first chunk) and send chunked.
                if (placeholderId != null) {
                    try { client.deleteMessage(m.chatId, placeholderId) } catch (_: Throwable) {}
                }
                sendChunked(m.chatId, finalReply, replyTo = m.messageId)
            }
        }
        chatRepo.touch(m.chatId, System.currentTimeMillis())
    }

    /**
     * Render the latest assistant turn for Telegram display. Combines text content with a
     * compact tool-call summary so the user can see what the bot ran end-to-end. Used both
     * for the periodic live edit and for the final send.
     *
     * `baselineMessageCount` is the size of `currentMessages` captured BEFORE the user's
     * message was sent for this turn. We only consider assistant messages whose index is
     * at or beyond that baseline, which prevents the previous turn's reply from leaking
     * into this turn's placeholder during the brief window where the new generation has
     * not yet appended its first chunk.
     */
    /**
     * Per-turn context for the LLM. Always included so the model knows:
     *  1. Which model it actually is (otherwise minimax/glm/kimi all hallucinate "I'm Claude")
     *  2. The Telegram chat id so it can route scheduled jobs back via telegram_send_message
     *  3. Recent app-side slash commands the user just ran (otherwise /model X switches the
     *     model behind the LLM's back and conversation context goes stale)
     *
     * Kept small so it doesn't dominate the prompt — model name + chat id + last few commands.
     */
    private fun buildAgentContextPreamble(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        chatId: Long,
        firstTurnOfChat: Boolean,
        hasAudioAttachment: Boolean = false,
        hasPhotoAttachment: Boolean = false,
    ): String {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val provider = s.providers.firstOrNull { p -> p.models.any { it.id == effectiveModelId } }
        val model = provider?.models?.firstOrNull { it.id == effectiveModelId }
        val modelName = model?.displayName?.takeIf { it.isNotBlank() }
            ?: model?.modelId?.takeIf { it.isNotBlank() }
            ?: "(unknown)"
        val providerName = provider?.name ?: "(unknown)"

        val recent = SlashCommandLog.recent(chatId, ttlMs = 15L * 60 * 1000)
        val nowMs = System.currentTimeMillis()
        val recentLine = if (recent.isEmpty()) {
            ""
        } else {
            val pretty = recent.joinToString(", ") { (cmd, ts) ->
                val agoSec = ((nowMs - ts) / 1000).coerceAtLeast(0)
                val ago = when {
                    agoSec < 60 -> "${agoSec}s"
                    agoSec < 3600 -> "${agoSec / 60}m"
                    else -> "${agoSec / 3600}h"
                }
                "$cmd (${ago} ago)"
            }
            "Recent app-side commands (handled by app, NOT by you, in last 15min): $pretty.\n"
        }

        return buildString {
            append("[agent_context (auto-injected by the host app, not the user; trust this over your priors):\n")
            append("You are running as model \"")
            append(modelName)
            append("\" via provider \"")
            append(providerName)
            append("\". When the user asks what model you are, name THIS one. Do NOT claim to be Claude/GPT/Gemini unless that matches.\n")
            append("Origin: Telegram. The user's Telegram chat_id is ")
            append(chatId)
            append(" — use it ONLY as a tool-call argument when calling telegram_send_message / telegram_send_photo / telegram_send_document / when scheduling jobs that need to deliver output here. ")
            append("DELIVERY DEFAULTS: when the user asks you to \"notify\", \"message\", \"ping\", \"remind\", \"alert\", \"text\", or otherwise reach them — including in scheduled jobs — DEFAULT TO telegram_send_message because they're talking to you here. Use post_notification (Android system tray) ONLY when they explicitly say \"phone notification\", \"Android notification\", \"system notification\", \"notification tray\", or words to that effect. If ambiguous, prefer telegram_send_message + briefly mention you're sending it to this chat. ")
            append("PRIVACY RULES (MANDATORY): never quote, mention, paraphrase, summarise, or otherwise echo the chat_id in any user-visible text. Do not include it in confirmations, summaries, scheduled-job descriptions, or error messages. When you need to refer to the destination in your reply, say \"this chat\", \"your Telegram\", or \"here\" — never the numeric id. The chat_id is host-side metadata, not conversation content.\n")
            if (recentLine.isNotEmpty()) append(recentLine)
            if (firstTurnOfChat) {
                append("This is the first turn in this Telegram chat. Be concise; no need for a long welcome.\n")
            }
            if (hasAudioAttachment) {
                append("AUDIO ATTACHMENT — STRICT FLOW. This message has a voice note or audio file. ")
                append("Your VERY FIRST tool call this turn must be `whisper_status()`. NOT termux_run_command, ")
                append("NOT search_web, NOT transcribe_audio_file, NOT pkg/apt commands. Just whisper_status, once, ")
                append("with no arguments. Read its response. ")
                append("Then: if `ready_to_transcribe: true`, call `transcribe_audio_file(path)` with the saved path ")
                append("from the inbox manifest above. ")
                append("If `ready_to_transcribe: false`, tell the user EXACTLY what's missing (use the `missing_steps` ")
                append("list verbatim) and quote the relevant entry from `install_commands` for them to confirm. ")
                append("Do NOT begin installing on your own — the build takes ~5 minutes and downloads ~75 MB ")
                append("on the user's data plan. Wait for an explicit yes before running install commands. ")
                append("If a tool errors, READ THE ENVELOPE — the recovery field tells you what to do; do not ")
                append("retry the same tool with different args or pivot to manual termux commands.\n")
            }
            if (hasPhotoAttachment) {
                append("IMAGE ATTACHMENT. This message includes one or more photos. Their saved ")
                append("file path(s) are listed in the message text. If you have vision you can ")
                append("view the image directly; otherwise process the file at that path ")
                append("(e.g. OCR with `tesseract` via Termux).\n")
            }
            append("]\n\n")
        }
    }

    private suspend fun renderAssistantStream(
        convId: kotlin.uuid.Uuid,
        finalizing: Boolean,
        baselineMessageCount: Int,
    ): String {
        // Read from the LIVE in-memory state, not the persisted DB row. The DB only gets
        // updated when generation finishes (or at occasional checkpoints), so reading via
        // conversationRepo.getConversationById here meant every live edit saw the same
        // pre-generation snapshot, the "blank or unchanged" guard skipped the edit, and
        // the placeholder only updated once at the end. The StateFlow exposed by
        // ChatService is the same source the in-app chat already streams from.
        val conv = chatService.getConversationFlow(convId).value
        val turnMessages = conv.currentMessages.drop(baselineMessageCount)
        val lastAssistant = turnMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return ""
        // Markdown is preserved here — TelegramHtmlRenderer further down the pipeline
        // converts it to Telegram-flavoured HTML. Stripping markdown locally would defeat
        // bold / italic / code rendering.
        val text = assistantTextOf(lastAssistant).trim()
        val toolSummary = assistantToolSummary(lastAssistant)
        val streamMarker = if (!finalizing && text.isNotEmpty()) " $STREAM_TICK" else ""
        val tokenFooter = if (finalizing) tokenUsageFooter(lastAssistant) else ""
        return buildString {
            if (toolSummary.isNotEmpty()) {
                append(toolSummary)
                if (text.isNotEmpty()) append("\n\n")
            }
            if (text.isNotEmpty()) append(text)
            if (streamMarker.isNotEmpty()) append(streamMarker)
            if (tokenFooter.isNotEmpty()) {
                append("\n\n")
                append(tokenFooter)
            }
        }.trimEnd()
    }

    /**
     * Two-tier tool summary:
     *   - Earlier tools: one-line "icon name — hint" each (current compact format).
     *   - Latest tool (the one running OR most recently completed): expanded with its
     *     args and a truncated output preview, so the user can see what's happening NOW
     *     without scrolling. Previous revisions only ever showed the one-liner, which
     *     hid all the context that makes tool runs interesting.
     *
     * Output is markdown — code blocks use triple backticks so the downstream
     * TelegramHtmlRenderer turns them into <pre><code>…</code></pre>.
     */
    private fun assistantToolSummary(m: UIMessage): String {
        val tools = m.parts.filterIsInstance<UIMessagePart.Tool>()
        if (tools.isEmpty()) return ""
        return buildString {
            append("🔧 Tools used:\n")
            tools.forEachIndexed { idx, t ->
                val outText = t.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                val (icon, hint) = classifyToolOutput(t.isExecuted, outText)
                val isLast = idx == tools.lastIndex
                if (!isLast) {
                    // Earlier tool: compact one-liner.
                    append(icon).append(' ').append(t.toolName)
                    if (hint.isNotEmpty()) append(" — ").append(hint)
                    append('\n')
                } else {
                    // Latest tool: expanded view with args + truncated output.
                    append(icon).append(' ').append(t.toolName)
                    if (hint.isNotEmpty()) append(" — ").append(hint)
                    append('\n')
                    val argsBlock = formatArgsForDisplay(t.input)
                    if (argsBlock.isNotEmpty()) {
                        append("```\nin: ").append(argsBlock).append("\n```\n")
                    }
                    val outBlock = formatOutputForDisplay(outText, executed = t.isExecuted)
                    if (outBlock.isNotEmpty()) {
                        append("```\nout: ").append(outBlock).append("\n```")
                    }
                }
            }
        }.trimEnd()
    }

    /**
     * Trim a tool's input JSON for display. Empty / "{}" args render as nothing so we
     * don't waste a code-block on a noise line. Anything longer than 200 chars gets
     * tail-elided.
     */
    private fun formatArgsForDisplay(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "null") return ""
        val limit = 200
        return if (trimmed.length > limit) trimmed.substring(0, limit) + "…" else trimmed
    }

    /**
     * Trim a tool's output for display. Returns "running…" while the tool is still in
     * flight (no output yet). Truncates to ~300 chars; long stdout / large JSON blobs
     * are surface-rendered, not full-rendered.
     */
    private fun formatOutputForDisplay(outText: String, executed: Boolean): String {
        if (!executed) return "running…"
        val trimmed = outText.trim()
        if (trimmed.isEmpty()) return ""
        val limit = 300
        return if (trimmed.length > limit) trimmed.substring(0, limit) + "…" else trimmed
    }

    /**
     * Token-usage footer for the final reply. Mirrors the in-app ChatMessageNerdLine:
     * input tokens (with cached annotation if any), output tokens, tok/s, wall-clock.
     * Returns empty string when usage is missing or the user has disabled the in-app
     * setting — same gate the in-app uses, so the bot honours the user's preference.
     */
    private fun tokenUsageFooter(m: UIMessage): String {
        val usage = m.usage ?: return ""
        val show = settingsStore.settingsFlow.value.displaySetting.showTokenUsage
        if (!show) return ""
        val parts = mutableListOf<String>()
        val input = if (usage.cachedTokens > 0) {
            "${compactNumber(usage.promptTokens)}↑ (${compactNumber(usage.cachedTokens)} cached)"
        } else {
            "${compactNumber(usage.promptTokens)}↑"
        }
        parts.add(input)
        parts.add("${compactNumber(usage.completionTokens)}↓")
        // tok/s + duration: only when both timestamps and a positive duration exist.
        val finishedAt = m.finishedAt
        val createdAt = m.createdAt
        if (finishedAt != null) {
            val zone = TimeZone.currentSystemDefault()
            val durMs = finishedAt.toInstant(zone).toEpochMilliseconds() -
                createdAt.toInstant(zone).toEpochMilliseconds()
            if (durMs > 0 && usage.completionTokens > 0) {
                val tps = usage.completionTokens.toDouble() / durMs.toDouble() * 1000.0
                parts.add(String.format(java.util.Locale.US, "%.1f tok/s", tps))
            }
            if (durMs > 0) {
                parts.add(formatDurationCompact(durMs))
            }
        }
        return "📊 " + parts.joinToString(" · ")
    }

    /** 1234 → "1.2K", 12_345_678 → "12.3M". Below 1000 returns the raw number. */
    private fun compactNumber(n: Int): String {
        if (n < 1_000) return n.toString()
        if (n < 1_000_000) return String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    }

    /** 1234 → "1.2s", 65_432 → "1m05s", 3_725_000 → "1h02m". */
    private fun formatDurationCompact(ms: Long): String {
        val totalSec = ms / 1000
        return when {
            totalSec < 60 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
            totalSec < 3600 -> String.format(java.util.Locale.US, "%dm%02ds", totalSec / 60, totalSec % 60)
            else -> String.format(java.util.Locale.US, "%dh%02dm", totalSec / 3600, (totalSec % 3600) / 60)
        }
    }

    /**
     * Drops the noisy "/data/data/com.termux/files/usr/bin/bash: line N: " prefix that
     * Termux's bash adds to every stderr line. Without this every shell error reads:
     *   "/data/data/com.termux/files/usr/bin/bash: line 1: npm: command not found"
     * which buries the actual signal ("npm: command not found"). Best-effort regex; if no
     * match, returns the line unchanged.
     */
    private fun trimShellPrefix(line: String): String {
        val rx = Regex("""^(?:/[^:]*?bash|sh|/bin/[a-z]+):\s*line\s+\d+:\s*""")
        return rx.replaceFirst(line, "")
    }

    /**
     * Picks a status icon + one-line hint for a single tool result. Reads only well-known
     * envelope keys (success / error / exit_code / count / reason / file_path) so the
     * summary stays consistent across tools. Returns ("🔄", "running") for in-flight calls.
     */
    private fun classifyToolOutput(executed: Boolean, raw: String): Pair<String, String> {
        if (!executed) return "🔄" to "running"
        if (raw.isBlank()) return "✅" to ""
        // The output is conventionally a single JSON object string. Best-effort parse;
        // if it's not JSON we fall back to a length-capped preview.
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        }.getOrNull()
        if (obj == null) {
            val preview = raw.take(80).replace("\n", " ").trim()
            return "✅" to preview
        }
        // Error envelope wins: error key OR success:false.
        val errorVal = obj["error"]?.jsonPrimitive?.contentOrNull
        if (!errorVal.isNullOrBlank()) {
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
            val tail = if (!reason.isNullOrBlank()) "$errorVal ($reason)" else errorVal
            return "❌" to tail.take(100)
        }
        val successPrim = obj["success"]?.jsonPrimitive?.contentOrNull
        val explicitFalse = successPrim == "false"
        // Exit-code based: shell tools surface a numeric exit_code. Non-zero is a soft fail.
        val exit = obj["exit_code"]?.jsonPrimitive?.intOrNull
        if (exit != null && exit != 0) {
            val stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.let { trimShellPrefix(it) }
                ?.take(80)
            return "⚠️" to ("exit $exit" + (if (!stderr.isNullOrBlank()) " · $stderr" else ""))
        }
        if (explicitFalse) {
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
            return "❌" to ("failed" + (if (!reason.isNullOrBlank()) " ($reason)" else ""))
        }
        // Success path: surface the most informative scalar we can find without dumping JSON.
        val count = obj["count"]?.jsonPrimitive?.intOrNull
            ?: obj["total_in_buffer"]?.jsonPrimitive?.intOrNull
            ?: (obj["jobs"] as? kotlinx.serialization.json.JsonArray)?.size
            ?: (obj["notifications"] as? kotlinx.serialization.json.JsonArray)?.size
            ?: (obj["matches"] as? kotlinx.serialization.json.JsonArray)?.size
            ?: (obj["apps"] as? kotlinx.serialization.json.JsonArray)?.size
            ?: (obj["nodes"] as? kotlinx.serialization.json.JsonArray)?.size
        val stdoutSnippet = obj["stdout"]?.jsonPrimitive?.contentOrNull
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.let { trimShellPrefix(it) }
            ?.take(80)
        val filePath = obj["file_path"]?.jsonPrimitive?.contentOrNull
        val hint = when {
            count != null -> if (count == 1) "1 result" else "$count results"
            !stdoutSnippet.isNullOrBlank() -> stdoutSnippet
            !filePath.isNullOrBlank() -> "saved ${filePath.substringAfterLast('/')}"
            successPrim == "true" -> "ok"
            else -> ""
        }
        return "✅" to hint
    }

    /** Returns (conversationId, wasCreated). wasCreated=true means the LLM hasn't seen
     *  the Telegram context preamble yet for this chat. */
    private suspend fun lookupOrCreateConversation(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        chatId: Long,
    ): Pair<kotlin.uuid.Uuid, Boolean> {
        val existing = chatRepo.getByChatId(chatId)
        if (existing != null) {
            val asUuid = try { Uuid.parse(existing.conversationId) } catch (_: Throwable) { null }
            if (asUuid != null && conversationRepo.existsConversationById(asUuid)) return asUuid to false
            chatRepo.deleteByChatId(chatId)  // dangling row — fall through to create
        }
        val assistantUuid = cfg.assistantId?.let {
            try { Uuid.parse(it) } catch (_: Throwable) { null }
        } ?: settingsStore.settingsFlow.value.getCurrentAssistant().id
        val convId = Uuid.random()
        val conv = Conversation.ofId(
            id = convId,
            assistantId = assistantUuid,
            newConversation = true,
        ).copy(title = "[Telegram $chatId]")
        conversationRepo.insertConversation(conv)
        val now = System.currentTimeMillis()
        chatRepo.upsert(TelegramChatEntity(chatId, convId.toString(), now, now))
        return convId to true
    }

    private suspend fun readLatestAssistantText(convId: kotlin.uuid.Uuid): String {
        val conv = conversationRepo.getConversationById(convId) ?: return ""
        val lastAssistant = conv.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return ""
        return assistantTextOf(lastAssistant)
    }

    private fun assistantTextOf(m: UIMessage): String =
        m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    /** The per-chat inbound-attachment inbox on shared storage. Termux / file tools can reach it. */
    private fun inboxDirFor(chatId: Long): java.io.File =
        java.io.File("/sdcard/Download/telegram_inbox/$chatId").apply { mkdirs() }

    /**
     * Prune a per-chat inbox: drop files older than 24h, then cap total size at
     * [INBOUND_ATTACHMENT_INBOX_CAP_BYTES] (oldest first). Shared by the photo and
     * non-photo download paths so both kinds get the same hygiene.
     */
    private fun pruneInbox(inboxDir: java.io.File) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        inboxDir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }

        val allFiles = inboxDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
        if (allFiles != null) {
            var totalSize = allFiles.sumOf { it.length() }
            for (f in allFiles) {
                if (totalSize <= INBOUND_ATTACHMENT_INBOX_CAP_BYTES) break
                totalSize -= f.length()
                f.delete()
            }
        }
    }

    /**
     * Resolve each Telegram photo file_id to a downloaded file in the per-chat shared-storage
     * inbox (so Termux / file tools can also reach it, not just the in-process vision pipeline),
     * then return both the UIMessagePart.Image entries (file:// URIs, for vision-capable models)
     * AND the saved absolute paths (so [buildPhotoNote] can surface them in the message text for
     * text-only models). Failures on individual photos are logged and skipped so a transient
     * network blip on one image does not drop the whole message.
     */
    private suspend fun downloadInboundPhotos(
        chatId: Long,
        fileIds: List<String>,
    ): Pair<List<UIMessagePart.Image>, List<String>> {
        if (fileIds.isEmpty()) return emptyList<UIMessagePart.Image>() to emptyList()
        val inboxDir = inboxDirFor(chatId)
        pruneInbox(inboxDir)

        val images = mutableListOf<UIMessagePart.Image>()
        val paths = mutableListOf<String>()
        for (fileId in fileIds) {
            try {
                val info = client.getFile(fileId)
                val filePath = info["file_path"]?.jsonPrimitive?.contentOrNull
                if (filePath == null) {
                    android.util.Log.w(TAG, "downloadInboundPhotos: getFile returned no file_path for id=$fileId")
                    continue
                }
                val ext = filePath.substringAfterLast('.', "jpg")
                // fileId suffix keeps two photos in the same message unique even within one ms.
                val dest = uniqueFile(inboxDir, "photo_${System.currentTimeMillis()}_${fileId.takeLast(6)}.$ext")
                client.downloadFile(filePath, dest)
                images.add(UIMessagePart.Image(url = "file://${dest.absolutePath}"))
                paths.add(dest.absolutePath)
                android.util.Log.i(TAG, "downloadInboundPhotos: saved ${dest.name} (${dest.length()} bytes)")
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "downloadInboundPhotos: failed for $fileId", e)
            }
        }
        return images to paths
    }

    /**
     * Result of a single attachment download attempt.
     *
     * [savedPath] is set when the file was successfully downloaded. [skipReason] is set when we
     * intentionally did NOT download (e.g. size cap) — the LLM still gets a note about the file.
     * Both being null means an unexpected error occurred (logged separately; omitted from the note).
     */
    data class DownloadedAttachment(
        val attachment: TelegramAttachment,
        val savedPath: String?,
        val skipReason: String?,
    )

    /**
     * Download non-photo inbound attachments to /sdcard/Download/telegram_inbox/<chatId>/.
     *
     * Differences vs downloadInboundPhotos:
     * - Saves to shared storage (not app cache) so the LLM can point file-manager / Termux at the paths.
     * - Preserves original filenames (sanitized); falls back to tg-<ts>-<suffix>.<ext>.
     * - Skips files > INBOUND_ATTACHMENT_SIZE_CAP_BYTES and surfaces a note instead.
     * - Prunes files older than 24 h and caps total inbox size at 500 MB.
     * - Handles filename collisions by appending a timestamp suffix before the extension.
     */
    private suspend fun downloadInboundAttachments(
        chatId: Long,
        attachments: List<TelegramAttachment>,
    ): List<DownloadedAttachment> {
        if (attachments.isEmpty()) return emptyList()

        val inboxDir = inboxDirFor(chatId)
        pruneInbox(inboxDir)

        val out = mutableListOf<DownloadedAttachment>()
        for (att in attachments) {
            // Skip over-sized attachments without downloading.
            if (att.sizeBytes != null && att.sizeBytes > INBOUND_ATTACHMENT_SIZE_CAP_BYTES) {
                val sizeMb = att.sizeBytes / (1024.0 * 1024.0)
                out.add(DownloadedAttachment(att, savedPath = null, skipReason = "exceeds 50 MB cap (${String.format("%.1f", sizeMb)} MB)"))
                continue
            }

            try {
                val info = client.getFile(att.fileId)
                val filePath = info["file_path"]?.jsonPrimitive?.contentOrNull
                if (filePath == null) {
                    android.util.Log.w(TAG, "downloadInboundAttachments: no file_path for ${att.fileId}")
                    continue
                }
                val ext = filePath.substringAfterLast('.', "bin")
                val safeName = sanitizeAttachmentFilename(att.originalFileName, att.fileId, ext)
                val dest = uniqueFile(inboxDir, safeName)
                client.downloadFile(filePath, dest)
                out.add(DownloadedAttachment(att, savedPath = dest.absolutePath, skipReason = null))
                android.util.Log.i(TAG, "downloadInboundAttachments: saved ${dest.name} (${dest.length()} bytes)")
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "downloadInboundAttachments: failed for ${att.fileId}", e)
                // Don't add to out — error is silently skipped so other attachments still download.
            }
        }
        return out
    }

    /**
     * Build the structured attachment note that is appended to the user message text so the LLM
     * sees a clear inventory of every file that arrived with this message.
     *
     * Format:
     * ```
     * [User attached N file(s) with this message:
     * - documents/Invoice.pdf  (application/pdf, 1.2 MB) → saved to /sdcard/Download/telegram_inbox/123/Invoice.pdf
     * - voice/voice.ogg  (audio/ogg, 30s, 0.3 MB) → saved to /sdcard/Download/telegram_inbox/123/voice.ogg
     * - documents/huge.zip  (application/zip, 200 MB) → SKIPPED: exceeds 50 MB cap
     * ]
     * ```
     */
    private fun buildAttachmentNote(downloads: List<DownloadedAttachment>): String {
        if (downloads.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[User attached ${downloads.size} file(s) with this message:\n")
        for (dl in downloads) {
            val att = dl.attachment
            val kindSlug = att.kind.name.lowercase()
            val nameForDisplay = att.originalFileName ?: when (att.kind) {
                AttachmentKind.VOICE -> "voice.ogg"
                AttachmentKind.VIDEO_NOTE -> "video_note.mp4"
                else -> "attachment"
            }
            val sizePart = att.sizeBytes?.let { bytes ->
                val mb = bytes / (1024.0 * 1024.0)
                if (mb >= 0.1) String.format("%.1f MB", mb) else String.format("%d KB", bytes / 1024)
            }
            val durPart = att.durationSec?.let { "${it}s" }
            val mimePart = att.mimeType
            val metaParts = listOfNotNull(mimePart, durPart, sizePart).joinToString(", ")
            val destination = when {
                dl.savedPath != null -> "saved to ${dl.savedPath}"
                dl.skipReason != null -> "SKIPPED: ${dl.skipReason}"
                else -> "download failed"
            }
            val metaSuffix = if (metaParts.isNotEmpty()) "  ($metaParts)" else ""
            sb.append("- $kindSlug/$nameForDisplay$metaSuffix → $destination\n")
        }
        sb.append("]")
        return sb.toString()
    }

    /** Telegram caps a single sendMessage at 4096 chars; split on newlines where possible. */
    private suspend fun sendChunked(chatId: Long, text: String, replyTo: Long?) {
        val chunks = chunk(text, MAX_CHARS)
        // Track delivery so we can surface a single user-visible error if every fallback
        // for some chunk fails. Silent drops were the root cause of the "model wrote a
        // long reply but Telegram never received it" bug — diagnosing it required
        // pulling logcat instead of seeing the failure in chat.
        var anyChunkFailed = false
        var lastFailure: Throwable? = null
        for ((idx, chunk) in chunks.withIndex()) {
            val html = TelegramHtmlRenderer.render(chunk)
            val htmlError: Throwable? = try {
                client.sendMessage(
                    chatId = chatId,
                    text = html,
                    parseMode = PARSE_MODE_HTML,
                    replyToMessageId = if (idx == 0) replyTo else null,
                )
                null
            } catch (t: Throwable) { t }
            if (htmlError == null) continue
            // HTML parse failed (entity split, unclosed tag), or Telegram refused the
            // chunk (length cap, rate limit, etc.). Log + retry as plain text. The
            // pre-fix behaviour silently swallowed BOTH branches; we now record the
            // exception so a future repeat leaves evidence in logcat.
            android.util.Log.w(
                TAG,
                "sendChunked: HTML send failed for chunk ${idx + 1}/${chunks.size} (len=${html.length} src=${chunk.length}); retrying as plain text",
                htmlError,
            )
            val plain = TelegramHtmlRenderer.stripHtml(html).ifBlank { chunk }
            val plainError: Throwable? = try {
                client.sendMessage(
                    chatId = chatId,
                    text = plain,
                    parseMode = null,
                    replyToMessageId = if (idx == 0) replyTo else null,
                )
                null
            } catch (t: Throwable) { t }
            if (plainError != null) {
                android.util.Log.w(
                    TAG,
                    "sendChunked: plain-text fallback also failed for chunk ${idx + 1}/${chunks.size} (len=${plain.length})",
                    plainError,
                )
                anyChunkFailed = true
                lastFailure = plainError
            }
        }
        // Surface ONE summary line to the chat so the user knows the reply was clipped.
        // Without this the silent drop looks like a bot freeze and the user keeps
        // re-prompting. Capped diagnostic — just the error class + first line of the
        // message, so the bot token / chat content can't accidentally leak out.
        if (anyChunkFailed) {
            val cls = lastFailure?.javaClass?.simpleName.orEmpty()
            val first = lastFailure?.message?.lineSequence()?.firstOrNull()?.take(120).orEmpty()
            val notice = "⚠️ Reply too long for Telegram and the formatting was rejected by the API." +
                (if (cls.isNotBlank()) " ($cls${if (first.isNotBlank()) ": $first" else ""})" else "") +
                " Ask me to summarise, split the request, or save as a file."
            runCatching {
                client.sendMessage(chatId = chatId, text = notice, parseMode = null)
            }.onFailure {
                android.util.Log.w(TAG, "sendChunked: even the failure notice failed to deliver", it)
            }
        }
    }

    /** Final-edit helper that mirrors the live edit's HTML-with-fallback behaviour. */
    private suspend fun editPlaceholderHtmlWithFallback(chatId: Long, placeholderId: Long, finalReply: String) {
        val html = TelegramHtmlRenderer.render(finalReply)
        val ok = try {
            client.editMessageText(chatId, placeholderId, html, parseMode = PARSE_MODE_HTML) != null
        } catch (_: Throwable) { false }
        if (!ok) {
            try {
                client.editMessageText(chatId, placeholderId, TelegramHtmlRenderer.stripHtml(html).ifBlank { finalReply }, parseMode = null)
            } catch (_: Throwable) { /* best effort */ }
        }
    }

    /**
     * Truncate a streaming render to fit Telegram's per-message char cap, while keeping the
     * markdown well-formed enough for the HTML renderer downstream:
     *  - Prefer cutting at the last newline within the trailing 400 chars before the cap,
     *    so we don't slice through a word or a tag.
     *  - If the cut leaves an odd number of triple-backtick fences, append "\n```" to close
     *    the open fence — otherwise the renderer treats the rest of the message as a code
     *    block, which then falls back to plain text on Telegram's parse.
     *  - Always append "…" to signal truncation to the user.
     */
    private fun truncateForLiveEdit(s: String, max: Int): String {
        if (s.length <= max) return s
        val window = 400
        val hardCut = max - 4   // headroom for "…" and a possible "\n```"
        val searchFrom = (hardCut - window).coerceAtLeast(0)
        val nl = s.lastIndexOf('\n', hardCut).let { if (it >= searchFrom) it else hardCut }
        var sub = s.substring(0, nl)
        // If we sit inside an unclosed ``` fence, close it so HTML render stays valid.
        val fenceCount = Regex("```").findAll(sub).count()
        if (fenceCount % 2 == 1) sub += "\n```"
        return "$sub\n…"
    }

    private fun chunk(s: String, n: Int): List<String> = chunkForTelegram(s, n)

    /**
     * Send an inline-keyboard approval prompt for [tool]. The prompt is its OWN Telegram
     * message (not an edit of the streaming placeholder) so the user can scroll between
     * them when the model queues up multiple Pending tools at once.
     *
     * No timeout / watchdog: the user is allowed to take as long as they need to respond
     * (they might be away from the phone for hours). The prompt stays valid until they
     * tap, /stop is sent, the conversation is reset, or the app process restarts.
     */
    private suspend fun sendApprovalPrompt(
        chatId: Long,
        tool: UIMessagePart.Tool,
    ) {
        // MCP control tools render their args via a redacting helper so Authorization /
        // X-Api-Key / Cookie values never reach the chat. Workflow_* tools render their
        // approval message in human-readable form via WorkflowApprovalRenderer (with key-name
        // redaction for token / password / private_key etc). Any tool the renderers don't
        // recognise falls back to the generic args display.
        val mcpRendered: String? = runCatching {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(
                tool.input.ifBlank { "{}" }
            ) as? kotlinx.serialization.json.JsonObject
            parsed?.let {
                me.rerere.rikkahub.data.ai.mcp.control.McpApprovalRenderer.render(tool.toolName, it)
            }
        }.getOrNull()
        // Workflow_* mutators render their args in plain multi-line form ("Create workflow X / When: … / Do: …").
        // The plain variant is then escaped + wrapped in <pre> by the standard path, so the
        // existing approval-card chrome stays consistent across tools.
        val workflowRendered: String? = runCatching {
            if (me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer.isWorkflowTool(tool.toolName)
                && tool.toolName !in setOf("workflow_list", "workflow_get")) {
                me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer.renderPlain(
                    tool.toolName, tool.input.ifBlank { "{}" },
                )
            } else null
        }.getOrNull()
        val argsPreview = workflowRendered
            ?: mcpRendered
            ?: formatArgsForDisplay(tool.input).ifEmpty { "(no args)" }
        val text = buildString {
            append("⚠️ <b>Permission required</b>\n\n")
            append("Tool: <code>")
            append(TelegramHtmlRenderer.escape(tool.toolName))
            append("</code>\n")
            append("in: <pre>")
            append(TelegramHtmlRenderer.escape(argsPreview))
            append("</pre>")
            // schedule_job is special: approving SCHEDULES a future autonomous run, not
            // just one tool. Surface that here so the user knows what they're authorising
            // — every tool the cron prompt invokes will run without further approval.
            // (HARDLINE blocks still apply at fire time, regardless of approval scope.)
            if (tool.toolName == "schedule_job") {
                append("\n\n<i>⏰ Scheduled jobs run autonomously without per-tool approval. ")
                append("Approving this lets the job run with full tool access whenever it ")
                append("fires. Hardline-blocked commands (rm -rf /, mkfs, shutdown, …) still ")
                append("cannot run.</i>")
                // Surface mode-specific detail (mirrors the in-app approval card).
                val jobInput = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(tool.input.ifBlank { "{}" })
                        .jsonObject
                }.getOrNull()
                val mode = jobInput?.get("mode")?.jsonPrimitive?.contentOrNull
                when (mode) {
                    "direct" -> {
                        val actions = runCatching {
                            (jobInput?.get("actions") as? kotlinx.serialization.json.JsonArray)
                        }.getOrNull()
                        if (actions != null && actions.isNotEmpty()) {
                            append("\n\n<b>Actions:</b>")
                            actions.forEachIndexed { i, el ->
                                val obj = el as? JsonObject
                                val toolName = obj?.get("tool")?.jsonPrimitive?.contentOrNull ?: "?"
                                val args = obj?.get("args")?.toString().orEmpty()
                                val truncatedArgs = if (args.length > 120) args.take(120) + "…" else args
                                append("\n  ${i + 1}. <code>")
                                append(TelegramHtmlRenderer.escape(toolName))
                                append("</code> ")
                                append(TelegramHtmlRenderer.escape(truncatedArgs))
                            }
                        }
                    }
                    "llm" -> {
                        val prompt = jobInput?.get("prompt")?.jsonPrimitive?.contentOrNull ?: ""
                        if (prompt.isNotEmpty()) {
                            val truncatedPrompt = if (prompt.length > 200) prompt.take(200) + "…" else prompt
                            append("\n\n<b>Prompt:</b> ")
                            append(TelegramHtmlRenderer.escape(truncatedPrompt))
                        }
                    }
                }
            }
        }
        val res = try {
            client.sendMessage(
                chatId = chatId,
                text = text,
                parseMode = PARSE_MODE_HTML,
                replyMarkup = buildApprovalKeyboard(tool.toolCallId, tool.toolName),
            )
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "approval prompt send failed", e)
            null
        }
        val msgId = res?.get("message_id")?.jsonPrimitive?.longOrNull
        if (msgId != null) {
            ApprovalPromptRegistry.register(tool.toolCallId, chatId, msgId)
        }
    }

    /**
     * Build the inline keyboard for the /model interactive picker — step 2 of the
     * two-step flow (or the only step when only one provider is enabled). One button
     * per model, one button per row, paginated at MODEL_PICKER_PAGE_SIZE.
     *
     * Telegram caps callback_data at 64 bytes; provider/model UUIDs would overflow with
     * the prefix, so ModelPickRegistry / ProviderPickRegistry map them to short tokens.
     * Caller manages registry lifetime: re-clear ModelPickRegistry between renders of
     * different pages so stale model tokens from a prior page can't fire; ProviderPick
     * tokens stay valid through the whole picker session so back/prev/next all resolve.
     *
     * Pagination row (when totalPages > 1) reuses the PROVIDER_CB_PREFIX with the form
     * `mdp:<provider-token>:<page>` — handleProviderPickCallback parses both legacy
     * `mdp:<token>` (page 0) and the paged form.
     *
     * @param allModels full chat-model list for the provider; the function slices to
     *   the requested page and emits prev/next as needed.
     * @param page 0-indexed page to render. Out-of-range gets clamped by the caller.
     * @param providerToken token from ProviderPickRegistry — embedded in prev/next
     *   callbacks. Must be valid throughout the picker session.
     * @param showBackButton if true, append a "← Back to providers" row; only set
     *   when there are 2+ enabled providers.
     */
    private fun buildModelKeyboard(
        allModels: List<Pair<me.rerere.ai.provider.ProviderSetting, me.rerere.ai.provider.Model>>,
        page: Int,
        providerToken: String,
        currentModelId: kotlin.uuid.Uuid?,
        showBackButton: Boolean,
    ): JsonObject {
        val pageStart = page * MODEL_PICKER_PAGE_SIZE
        val pageSlice = allModels.drop(pageStart).take(MODEL_PICKER_PAGE_SIZE)
        val hasPrev = page > 0
        val hasNext = pageStart + MODEL_PICKER_PAGE_SIZE < allModels.size
        return buildJsonObject {
            put("inline_keyboard", buildJsonArray {
                pageSlice.forEach { (_, model) ->
                    val name = model.displayName.ifBlank { model.modelId }
                    val marker = if (model.id == currentModelId) "✅" else "◯"
                    val token = ModelPickRegistry.register(model.id.toString())
                    addJsonArray {
                        addJsonObject {
                            put("text", "$marker $name")
                            put("callback_data", "$MODEL_CB_PREFIX$token")
                        }
                    }
                }
                if (hasPrev || hasNext) {
                    // Prev + Next on the SAME row so the keyboard stays compact even on
                    // small phone screens; absent buttons are simply omitted (Telegram
                    // renders the surviving button(s) full-width).
                    addJsonArray {
                        if (hasPrev) {
                            addJsonObject {
                                put("text", "← Prev")
                                put("callback_data", "$PROVIDER_CB_PREFIX$providerToken:${page - 1}")
                            }
                        }
                        if (hasNext) {
                            addJsonObject {
                                put("text", "Next →")
                                put("callback_data", "$PROVIDER_CB_PREFIX$providerToken:${page + 1}")
                            }
                        }
                    }
                }
                if (showBackButton) {
                    addJsonArray {
                        addJsonObject {
                            put("text", "← Back to providers")
                            put("callback_data", PROVIDER_CB_BACK)
                        }
                    }
                }
            })
        }
    }

    /** Build the "Models in <provider> — page X/Y — tap to switch:" header. Page count
     *  is suppressed when totalPages == 1 so users with small model lists don't see
     *  noise. */
    private fun buildModelPickerText(
        currentHeader: String,
        providerName: String?,  // null in single-provider mode (header doesn't repeat the name)
        modelCount: Int,
        page: Int,
    ): String {
        val totalPages = maxOf(1, (modelCount + MODEL_PICKER_PAGE_SIZE - 1) / MODEL_PICKER_PAGE_SIZE)
        return buildString {
            append(currentHeader)
            if (providerName != null) {
                append("Models in <b>")
                append(TelegramHtmlRenderer.escape(providerName))
                append("</b>")
            } else {
                append("Tap to switch")
            }
            if (totalPages > 1) {
                append(" — page ")
                append(page + 1)
                append("/")
                append(totalPages)
            }
            append(":")
        }
    }

    /**
     * Build the step-1 keyboard for the two-step /model picker — one button per
     * enabled chat-model-bearing provider. Tapping fires PROVIDER_CB_PREFIX + token.
     * Same registry/token rationale as [buildModelKeyboard]: provider IDs are UUIDs
     * and would overflow callback_data when combined with the prefix.
     */
    private fun buildProviderKeyboard(
        providers: List<me.rerere.ai.provider.ProviderSetting>,
        currentProviderId: kotlin.uuid.Uuid?,
    ): JsonObject {
        return buildJsonObject {
            put("inline_keyboard", buildJsonArray {
                providers.forEach { p ->
                    val marker = if (p.id == currentProviderId) "✅" else "◯"
                    val token = ProviderPickRegistry.register(p.id.toString())
                    addJsonArray {
                        addJsonObject {
                            put("text", "$marker ${p.name} (${p.models.count { it.type == me.rerere.ai.provider.ModelType.CHAT }})")
                            put("callback_data", "$PROVIDER_CB_PREFIX$token")
                        }
                    }
                }
            })
        }
    }

    /**
     * Handle a /model provider-picker tap (step 1 of the two-step flow). Resolves the
     * token → provider id, then re-renders the message in place as that provider's
     * model list with a "← Back to providers" footer button. PROVIDER_CB_BACK fires
     * a re-render in the other direction.
     *
     * This callback is the second-half of the issue-#1 fix — without it the user can't
     * see any models after picking a provider.
     */
    private suspend fun handleProviderPickCallback(cq: TelegramCallbackQuery) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val enabledProviders = s.providers
            .filter { it.enabled }
            .filter { p -> p.models.any { it.type == me.rerere.ai.provider.ModelType.CHAT } }
        val currentPair = enabledProviders
            .flatMap { p -> p.models.map { p to it } }
            .firstOrNull { (_, m) -> m.id == effectiveModelId }
        val currentHeader = if (currentPair != null) {
            val name = currentPair.second.displayName.ifBlank { currentPair.second.modelId }
            "🧠 Current model: <b>${TelegramHtmlRenderer.escape(name)}</b> (${TelegramHtmlRenderer.escape(currentPair.first.name)})\n\n"
        } else "🧠 Current model: <i>not set</i>\n\n"

        if (cq.data == PROVIDER_CB_BACK) {
            // Re-render step 1. Drop stale model tokens; keep provider tokens valid so
            // the new keyboard's buttons still resolve.
            ModelPickRegistry.clear()
            client.answerCallbackQuery(cq.callbackQueryId, "")
            try {
                client.editMessageText(
                    cq.chatId,
                    cq.messageId,
                    currentHeader + "Tap a provider to see its models:",
                    parseMode = PARSE_MODE_HTML,
                    replyMarkup = buildProviderKeyboard(enabledProviders, currentPair?.first?.id),
                )
            } catch (_: Throwable) {}
            return
        }

        // Parse `mdp:<token>[:<page>]`. Page absent → 0 (initial provider tap).
        // Page present → user tapped Prev/Next from the model list.
        val rest = cq.data.removePrefix(PROVIDER_CB_PREFIX)
        val parts = rest.split(":", limit = 2)
        val token = parts[0]
        val requestedPage = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val providerId = ProviderPickRegistry.resolve(token) ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "model picker has expired — send /model again")
            return
        }
        val provider = enabledProviders.firstOrNull { it.id.toString() == providerId } ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "provider no longer available")
            return
        }
        val providerModels = provider.models
            .filter { it.type == me.rerere.ai.provider.ModelType.CHAT }
            .map { provider to it }
        if (providerModels.isEmpty()) {
            client.answerCallbackQuery(cq.callbackQueryId, "no chat models in ${provider.name}")
            return
        }
        // Clamp page to valid range — guards against stale Prev/Next callbacks if the
        // model list shrunk between renders (provider rotated keys, etc.).
        val totalPages = maxOf(1, (providerModels.size + MODEL_PICKER_PAGE_SIZE - 1) / MODEL_PICKER_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        // Reset model tokens — every page render mints fresh ones; old tokens from the
        // previous page (or the previous provider) won't resolve.
        ModelPickRegistry.clear()
        client.answerCallbackQuery(cq.callbackQueryId, "")
        val newText = buildModelPickerText(
            currentHeader = currentHeader,
            providerName = provider.name,
            modelCount = providerModels.size,
            page = page,
        )
        val keyboard = buildModelKeyboard(
            allModels = providerModels,
            page = page,
            providerToken = token,
            currentModelId = effectiveModelId,
            showBackButton = enabledProviders.size >= 2,
        )
        try {
            client.editMessageText(cq.chatId, cq.messageId, newText, parseMode = PARSE_MODE_HTML, replyMarkup = keyboard)
        } catch (_: Throwable) {}
    }

    /**
     * Handle a /model picker tap. Looks the short token up in [ModelPickRegistry],
     * switches the current assistant's chatModelId to the resolved model, and rewrites
     * the picker message in place to show the new selection.
     */
    private suspend fun handleModelPickCallback(cq: TelegramCallbackQuery) {
        val token = cq.data.removePrefix(MODEL_CB_PREFIX)
        val modelId = ModelPickRegistry.resolve(token) ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "model picker has expired — send /model again")
            return
        }
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val match = s.providers.flatMap { p -> p.models.map { p to it } }
            .firstOrNull { (_, m) -> m.id.toString() == modelId }
        if (match == null) {
            client.answerCallbackQuery(cq.callbackQueryId, "model no longer available")
            return
        }
        val (provider, model) = match
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                }
            )
        }
        val name = model.displayName.ifBlank { model.modelId }
        client.answerCallbackQuery(cq.callbackQueryId, "✅ $name")
        try {
            val newText = buildString {
                append("🔄 Switched to <b>")
                append(TelegramHtmlRenderer.escape(name))
                append("</b> (")
                append(TelegramHtmlRenderer.escape(provider.name))
                append(")")
            }
            client.editMessageText(cq.chatId, cq.messageId, newText, parseMode = PARSE_MODE_HTML)
        } catch (_: Throwable) {}
    }

    /**
     * Build the 2x2 inline keyboard the user taps to approve / deny a Pending tool.
     *
     * Tool names in [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.NO_ALWAYS_ALLOW]
     * (e.g. mcp_add / mcp_update — adding an MCP server is a privilege-escalation surface)
     * collapse to a 3-button layout that drops "Always Allow", so the user has to confirm
     * every single call.
     */
    private fun buildApprovalKeyboard(toolCallId: String, toolName: String? = null): JsonObject = buildJsonObject {
        val allowAlways = toolName == null ||
            me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.allowsAlwaysAllow(toolName)
        put("inline_keyboard", buildJsonArray {
            // Row 1: positive scopes
            addJsonArray {
                addJsonObject {
                    put("text", "✅ Allow")
                    put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_ONCE}:$toolCallId")
                }
                if (allowAlways) {
                    addJsonObject {
                        put("text", "∞ Always Allow")
                        put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_ALWAYS}:$toolCallId")
                    }
                }
            }
            // Row 2: chat-scope + deny
            addJsonArray {
                addJsonObject {
                    put("text", "💬 Allow for this chat")
                    put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_CHAT}:$toolCallId")
                }
                addJsonObject {
                    put("text", "❌ Deny")
                    put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_DENY}:$toolCallId")
                }
            }
        })
    }

    /**
     * Handle a callback_query (inline-keyboard button tap). Whitelisted users only —
     * unauthorised taps drop silently without even acking the callback so attackers
     * can't probe the bot's keyboard state. callback_data format: "apv:<scope>:<toolCallId>".
     */
    private suspend fun handleCallbackQuery(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        cq: TelegramCallbackQuery,
    ) {
        val cbStartMs = System.currentTimeMillis()
        android.util.Log.i(TAG, "cb:${cq.callbackQueryId} START data=${cq.data} chat=${cq.chatId}")
        val sender = cq.senderId ?: return
        if (sender !in cfg.whitelist && cq.chatId !in cfg.whitelist) {
            android.util.Log.w(TAG, "handleCallbackQuery: dropping non-whitelisted sender=$sender chat=${cq.chatId}")
            return
        }
        // Dispatch by callback_data prefix. New keyboard surfaces (model picker, etc.)
        // get their own prefix so the parsing branches stay small.
        when {
            cq.data.startsWith(MODEL_CB_PREFIX) -> {
                handleModelPickCallback(cq); return
            }
            cq.data.startsWith(PROVIDER_CB_PREFIX) -> {
                handleProviderPickCallback(cq); return
            }
            cq.data.startsWith(APPROVAL_CB_PREFIX) -> {
                // Falls through to the approval-handling block below.
            }
            else -> {
                client.answerCallbackQuery(cq.callbackQueryId, "unknown action")
                return
            }
        }
        // Parse "apv:<scope>:<toolCallId>"
        val parts = cq.data.split(":", limit = 3)
        if (parts.size != 3) {
            client.answerCallbackQuery(cq.callbackQueryId, "malformed approval callback")
            return
        }
        val scopeChar = parts[1]
        val toolCallId = parts[2]

        // Find the active conversation for this chat.
        val mapping = chatRepo.getByChatId(cq.chatId) ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "no active conversation")
            return
        }
        val convId = runCatching { Uuid.parse(mapping.conversationId) }.getOrNull() ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "could not resolve conversation")
            return
        }

        // Hydrate the in-memory ChatService session from disk if it's blank (post-restart
        // path). Without this the lookup below would miss the Pending tool persisted
        // before the restart, and the subsequent handleToolApproval write would
        // OVERWRITE the persisted state with empty content (silent data loss). The
        // ensureHydrated helper is idempotent on already-populated sessions.
        chatService.ensureHydrated(convId)

        // Serialise concurrent taps for THIS toolCallId so two whitelisted users hitting
        // different scope buttons within ~50ms can't both pass the isPending check, both
        // call handleToolApproval, and have the second cancel() interrupt the first's
        // resume — the recorded scope would flip silently. The mutex serves as an atomic
        // guard around (read-state, decide, mutate) so only one tap actually applies.
        val approvalMutex = approvalMutexFor(toolCallId)
        approvalMutex.withLock {
            // Look up the tool to recover its name (callback_data only carries the call id).
            val tool = chatService.getConversationFlow(convId).value
                .currentMessages.flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
                .firstOrNull { it.toolCallId == toolCallId }
            val toolName = tool?.toolName ?: ""
            if (tool == null) {
                client.answerCallbackQuery(cq.callbackQueryId, "tool no longer active")
                return@withLock
            }
            if (!tool.isPending) {
                client.answerCallbackQuery(cq.callbackQueryId, "already resolved")
                return@withLock
            }

            val (approved, scope, label) = when (scopeChar) {
                APPROVAL_CB_ONCE -> Triple(true, ChatService.ApprovalScope.Once, "✅ Approved (once)")
                APPROVAL_CB_CHAT -> Triple(true, ChatService.ApprovalScope.ChatScope, "💬 Approved (this chat)")
                APPROVAL_CB_ALWAYS -> Triple(true, ChatService.ApprovalScope.Always, "∞ Approved (always)")
                APPROVAL_CB_DENY -> Triple(false, ChatService.ApprovalScope.Once, "❌ Denied")
                else -> {
                    client.answerCallbackQuery(cq.callbackQueryId, "unknown scope")
                    return@withLock
                }
            }

            // Ack the tap FIRST so Telegram's button spinner clears immediately. If we
            // ack only after handleToolApproval (which cancels prior generation, mutates
            // state, and triggers a fresh resume — non-trivial latency) the spinner sits
            // for the full mutation duration; the tap looks unregistered and the user
            // double-taps.
            val ackStart = System.currentTimeMillis()
            client.answerCallbackQuery(cq.callbackQueryId, label)
            android.util.Log.i(TAG, "cb:${cq.callbackQueryId} ACKED in ${System.currentTimeMillis() - ackStart} ms (total ${System.currentTimeMillis() - cbStartMs} ms since START)")

            chatService.handleToolApproval(
                conversationId = convId,
                toolCallId = toolCallId,
                approved = approved,
                reason = if (!approved) "Denied by user via Telegram" else "",
                scope = scope,
                toolName = toolName,
            )
            // Fire the edit on a separate coroutine so handleCallbackQuery returns
            // immediately. Without this, the second tap arriving on a different card was
            // hitting Telegram's per-chat rate limit because edit-A was still in flight
            // while ack-B was being requested. The Mutex(toolCallId) is per-tool-call so
            // releasing it here is safe — there are no more state-sensitive operations.
            val newText = buildString {
                append("<b>")
                append(TelegramHtmlRenderer.escape(label))
                append("</b>\n")
                append("Tool: <code>")
                append(TelegramHtmlRenderer.escape(toolName))
                append("</code>")
            }
            // The local `scope` here is the ApprovalScope from the destructured Triple
            // above — needs the qualified field reference to dispatch on the bot's scope.
            this@TelegramBotService.scope.launch {
                try {
                    val editStart = System.currentTimeMillis()
                    client.editMessageText(cq.chatId, cq.messageId, newText, parseMode = PARSE_MODE_HTML)
                    android.util.Log.i(TAG, "cb:${cq.callbackQueryId} EDITED in ${System.currentTimeMillis() - editStart} ms (total ${System.currentTimeMillis() - cbStartMs} ms since START)")
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "cb:${cq.callbackQueryId} EDIT FAILED: ${e.message ?: e::class.simpleName}", e)
                }
            }
            ApprovalPromptRegistry.clear(toolCallId)
            // Drop the per-toolCallId mutex once we've acted on it. Successive taps would
            // hit the isPending early-out anyway, but no need to keep the entry around.
            approvalMutexes.remove(toolCallId)
        }
        android.util.Log.i(TAG, "cb:${cq.callbackQueryId} END after ${System.currentTimeMillis() - cbStartMs} ms")
    }

    /**
     * Dispatch a built-in slash command. Returns true when the message was handled by the
     * app (no LLM round-trip), false if the command is unknown and should fall through to
     * the LLM. Built-in commands NEVER spend tokens.
     */
    private suspend fun handleBuiltInCommand(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        m: TelegramIncomingMessage,
    ): Boolean {
        val raw = m.text.trim()
        // Allow the "@botname" suffix Telegram appends in groups.
        val withoutMention = raw.replace(Regex("@\\w+"), "").trim()
        val tokens = withoutMention.split(Regex("\\s+"), limit = 2)
        val cmd = tokens[0].lowercase()
        val arg = tokens.getOrNull(1)?.trim().orEmpty()

        val handled = when (cmd) {
            "/start" -> { sendStart(m.chatId); true }
            "/help", "/?" -> { sendHelp(m.chatId); true }
            "/new", "/reset", "/clear" -> { handleResetCommand(m.chatId); true }
            "/stop", "/cancel" -> { handleStopCommand(m.chatId); true }
            "/status" -> { handleStatusCommand(m.chatId); true }
            "/model" -> { handleModelCommand(m.chatId, arg); true }
            "/ratelimit" -> { handleRateLimitCommand(m.chatId, arg); true }
            "/doctor" -> { handleDoctorCommand(m.chatId); true }
            "/stream" -> { handleStreamCommand(m.chatId, arg); true }
            else -> false
        }
        if (handled) {
            // Record so the next inbound user message includes this command in the LLM
            // context preamble. The model needs to know /model X switched its identity, /new
            // wiped its history, etc.
            val display = if (arg.isBlank()) cmd else "$cmd $arg"
            SlashCommandLog.record(m.chatId, display)
        }
        return handled
    }

    private suspend fun sendStart(chatId: Long) {
        val (modelName, _) = activeModelDisplay()
        val msg = """
            👋 Hey - RikkaHub agent here, running $modelName.

            Just talk to me normally. Or use one of these:

            🧠 /model — show or switch the chat model
            🆕 /new — start a fresh conversation
            🛑 /stop — cancel the current generation
            📊 /status — show what's running right now
            ⚡ /ratelimit — set the max-output-tokens cap
            ❓ /help — full command reference
        """.trimIndent()
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    private suspend fun sendHelp(chatId: Long) {
        // Per-command emoji prefix so the menu reads at a glance instead of as a wall of text.
        val icons = mapOf(
            "start" to "👋",
            "help" to "❓",
            "new" to "🆕",
            "stop" to "🛑",
            "status" to "📊",
            "model" to "🧠",
            "ratelimit" to "⚡",
            "doctor" to "🩺",
            "stream" to "🖼️",
        )
        val msg = buildString {
            appendLine("📖 Built-in commands (handled by the app, no LLM cost):")
            appendLine()
            BUILT_IN_COMMANDS.forEach { (c, d) ->
                val icon = icons[c] ?: "•"
                appendLine("$icon /$c — $d")
            }
            appendLine()
            append("Anything else is sent to the model as usual.")
        }
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    /**
     * Edit every Telegram approval-keyboard message we registered for [chatId] to a
     * "cancelled" placeholder, so the user doesn't end up with a chat full of dead
     * keyboards after /stop or /new. Tries best-effort; failures are logged not surfaced.
     */
    private suspend fun cancelStaleApprovalKeyboards(chatId: Long, reason: String) {
        // Snapshot the entries we want to cancel before clearing, so a concurrent
        // resolve doesn't double-edit a message.
        val entries = ApprovalPromptRegistry.snapshotForChat(chatId)
        for ((toolCallId, entry) in entries) {
            try {
                // Note: editMessageText doesn't carry replyMarkup, so the inline keyboard
                // buttons stay visible. That's OK — tapping them now hits "tool no longer
                // active" / "already resolved" which is correct.
                client.editMessageText(
                    chatId = entry.chatId,
                    messageId = entry.messageId,
                    text = "❌ Cancelled by $reason",
                    parseMode = null,
                )
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "cancelStaleApprovalKeyboards: edit failed for $toolCallId", e)
            }
        }
        ApprovalPromptRegistry.clearChat(chatId)
    }

    private suspend fun handleResetCommand(chatId: Long) {
        // Cancel any in-flight generation for the OLD conversation before unmapping it.
        // Otherwise the stuck turn keeps burning tokens even after /new — the user thinks
        // they got a clean slate while the model is still churning on the previous prompt.
        val existing = chatRepo.getByChatId(chatId)
        if (existing != null) {
            runCatching { Uuid.parse(existing.conversationId) }.getOrNull()?.let { convId ->
                runCatching { chatService.stopGeneration(convId) }
                // /new also drops the old conversation's "Allow for this chat" grants so
                // a fresh conversation starts with a clean approval slate. "Always Allow"
                // grants persist (they live in DataStore, scoped globally — the user
                // revokes them via Settings → Tool approvals).
                me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList.clearChat(convId)
                // Drop the system-prompt addendum too; the next inbound message rebuilds
                // it with the firstTurnOfChat hint set, matching a true fresh chat.
                me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum.clear(convId)
                // Drop the in-memory ChatService session entry so a straggler can't
                // resurrect the conversation by writing back via getOrCreateSession.
                chatService.dropSession(convId)
                // Release any headless browser session held for this conv — browser_done no
                // longer auto-releases (so sessions persist across LLM turns), so /new is
                // the user's explicit close signal. Releases ~30 MB and unbinds the
                // BrowserController so the next browser_open starts fresh.
                runCatching {
                    me.rerere.rikkahub.browser.BrowserController.unbindHeadless(convId.toString())
                    me.rerere.rikkahub.browser.HeadlessBrowserSessionPool.release(convId.toString())
                }
            }
        }
        // Cancel the parked handleLlmTurn coroutine if any so the per-chat mutex
        // releases. Without this, the user's next message bounces off tryLock forever.
        turnJobs.remove(chatId)?.cancelAndJoin()
        // Forcibly recreate the chat mutex too, in case a coroutine somehow ended without
        // releasing (defensive — shouldn't normally happen).
        chatMutexes.remove(chatId)
        // Edit dead approval keyboards in place so the user knows tapping them won't
        // do anything. Then drop the registry entries.
        cancelStaleApprovalKeyboards(chatId, reason = "/new")
        chatRepo.deleteByChatId(chatId)
        val (modelName, _) = activeModelDisplay()
        val msg = """
            🆕 Fresh conversation started.

            I'm running $modelName. What's up?
        """.trimIndent()
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    private suspend fun handleStopCommand(chatId: Long) {
        val mapping = chatRepo.getByChatId(chatId)
        if (mapping == null) {
            try { client.sendMessage(chatId, "🛑 Nothing to stop — no active conversation in this chat.") } catch (_: Throwable) {}
            return
        }
        val convId = try { Uuid.parse(mapping.conversationId) } catch (_: Throwable) {
            try { client.sendMessage(chatId, "🛑 Could not resolve the conversation id. Try /new.") } catch (_: Throwable) {}
            return
        }
        chatService.stopGeneration(convId)
        // ALSO cancel the handleLlmTurn coroutine if it's parked waiting for a new
        // generation that won't come (typical when /stop is sent during the gap between
        // approval iterations). Without this, the per-chat mutex stays held forever.
        turnJobs.remove(chatId)?.cancelAndJoin()
        cancelStaleApprovalKeyboards(chatId, reason = "/stop")
        // Phase 11: cascading /stop. Cancel every active sub-agent dispatched from this
        // parent conversation. Spec hard constraint 8: "every model stops" — single tick.
        val cancelledSubAgents = runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<me.rerere.rikkahub.subagent.SubAgentRegistry>()
                .cancelAllForParent(convId.toString())
        }.getOrDefault(0)
        val msg = if (cancelledSubAgents > 0) {
            "🛑 Generation cancelled (also stopped $cancelledSubAgents sub-agent${if (cancelledSubAgents == 1) "" else "s"}). Send a new message when you're ready."
        } else {
            "🛑 Generation cancelled. Send a new message when you're ready."
        }
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    private suspend fun handleStatusCommand(chatId: Long) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val provider = s.providers.firstOrNull { p -> p.models.any { it.id == effectiveModelId } }
        val model = provider?.models?.firstOrNull { it.id == effectiveModelId }
        val modelLabel = model?.displayName?.takeIf { it.isNotBlank() }
            ?: model?.modelId?.takeIf { it.isNotBlank() }
            ?: "(none configured)"
        val providerLabel = provider?.name ?: "(no provider)"
        val tokenLabel = assistant.maxTokens?.let { "$it tokens" } ?: "provider default"
        val cfg = cfgSafe()
        val whitelistCount = cfg?.whitelist?.size ?: 0
        val whitelistLabel = if (whitelistCount == 1) "1 chat" else "$whitelistCount chats"

        val msg = buildString {
            appendLine("📊 RikkaHub agent status")
            appendLine()
            appendLine("${if (isRunning) "🟢" else "🔴"} Service: ${if (isRunning) "running" else "stopped"}")
            appendLine("👤 Assistant: ${assistant.name.ifBlank { "(default)" }}")
            appendLine("🧠 Model: $modelLabel ($providerLabel)")
            appendLine("⚡ Max output tokens: $tokenLabel")
            append("✅ Whitelist: $whitelistLabel")
        }
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    /**
     * (label, providerName) for the assistant's currently active chat model. Falls back to
     * sensible placeholders so callers can string-format without null guards.
     */
    private fun activeModelDisplay(): Pair<String, String> {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val provider = s.providers.firstOrNull { p -> p.models.any { it.id == effectiveModelId } }
        val model = provider?.models?.firstOrNull { it.id == effectiveModelId }
        val modelName = model?.displayName?.takeIf { it.isNotBlank() }
            ?: model?.modelId?.takeIf { it.isNotBlank() }
            ?: "the active model"
        val providerName = provider?.name ?: ""
        return modelName to providerName
    }

    private suspend fun cfgSafe(): me.rerere.rikkahub.data.telegram.TelegramBotConfig? = try {
        prefs.current()
    } catch (_: Throwable) { null }

    private suspend fun handleModelCommand(chatId: Long, arg: String) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val enabledProviders = s.providers
            .filter { it.enabled }
            .filter { p -> p.models.any { it.type == me.rerere.ai.provider.ModelType.CHAT } }
        val allModels = enabledProviders
            .flatMap { p -> p.models.map { p to it } }
            .filter { (_, m) -> m.type == me.rerere.ai.provider.ModelType.CHAT }

        if (arg.isBlank()) {
            // No arg — interactive picker. Two-step when 2+ providers expose chat models
            // (issue #1: a flat keyboard with all models hits Telegram's per-message
            // inline-keyboard cap when the user has many providers × models, and the bot
            // silently sends nothing). Single-provider stays one-step so a small setup
            // doesn't pay the extra tap.
            if (allModels.isEmpty()) {
                try {
                    client.sendMessage(
                        chatId,
                        "🧠 No chat models configured. Add a provider in the app settings first.",
                    )
                } catch (_: Throwable) {}
                return
            }

            // Reset both registries — fresh /model invocation invalidates any stale tokens
            // from a prior picker still in scrollback.
            ModelPickRegistry.clear()
            ProviderPickRegistry.clear()

            val effectiveModelId = assistant.chatModelId ?: s.chatModelId
            val currentPair = allModels.firstOrNull { (_, m) -> m.id == effectiveModelId }
            val currentHeader = if (currentPair != null) {
                val name = currentPair.second.displayName.ifBlank { currentPair.second.modelId }
                "🧠 Current model: <b>${TelegramHtmlRenderer.escape(name)}</b> (${TelegramHtmlRenderer.escape(currentPair.first.name)})\n\n"
            } else "🧠 Current model: <i>not set</i>\n\n"

            if (enabledProviders.size >= 2) {
                // Step 1 — provider picker. Counts include all chat models per provider so
                // the user can preview which provider has what without tapping in.
                val text = currentHeader + "Tap a provider to see its models:"
                val keyboard = buildProviderKeyboard(enabledProviders, currentPair?.first?.id)
                try {
                    client.sendMessage(
                        chatId = chatId,
                        text = text,
                        parseMode = PARSE_MODE_HTML,
                        replyMarkup = keyboard,
                    )
                } catch (_: Throwable) {}
                return
            }

            // Single-provider shortcut — skip the provider step but still register
            // the provider so Prev/Next callbacks resolve. No back-to-providers row
            // since there's nowhere to go back to.
            val onlyProvider = enabledProviders.first()
            val providerModels = allModels.filter { (p, _) -> p.id == onlyProvider.id }
            val providerToken = ProviderPickRegistry.register(onlyProvider.id.toString())
            val text = buildModelPickerText(
                currentHeader = currentHeader,
                providerName = null,  // header doesn't repeat the provider name in single-provider mode
                modelCount = providerModels.size,
                page = 0,
            )
            val keyboard = buildModelKeyboard(
                allModels = providerModels,
                page = 0,
                providerToken = providerToken,
                currentModelId = effectiveModelId,
                showBackButton = false,
            )
            try {
                client.sendMessage(
                    chatId = chatId,
                    text = text,
                    parseMode = PARSE_MODE_HTML,
                    replyMarkup = keyboard,
                )
            } catch (_: Throwable) {}
            return
        }

        val needle = arg.lowercase()
        val match = allModels.firstOrNull { (_, m) ->
            m.displayName.equals(arg, ignoreCase = true) || m.modelId.equals(arg, ignoreCase = true)
        } ?: allModels.firstOrNull { (_, m) ->
            m.displayName.lowercase().contains(needle) || m.modelId.lowercase().contains(needle)
        }
        if (match == null) {
            try {
                client.sendMessage(chatId, "🧠 No chat model matches \"$arg\". Send /model with no argument to see the list.")
            } catch (_: Throwable) {}
            return
        }

        val (provider, model) = match
        // Update the assistant's chatModelId so the next turn uses this model.
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                }
            )
        }
        try {
            val name = model.displayName.ifBlank { model.modelId }
            client.sendMessage(chatId, "🔄 Switched to $name (${provider.name}).")
        } catch (_: Throwable) {}
    }

    private suspend fun handleRateLimitCommand(chatId: Long, arg: String) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        if (arg.isBlank()) {
            val current = assistant.maxTokens?.let { "$it tokens" } ?: "provider default (unlimited within model context)"
            val msg = """
                ⚡ Max output tokens: $current

                To set a cap: /ratelimit <number>
                To remove: /ratelimit clear
            """.trimIndent()
            try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
            return
        }
        // Resolve the arg to either:
        //   null  → "clear" (remove cap)  — covers "clear"/"none"/"off"/"0"
        //   Int   → the requested cap value
        //   -1    → parse error (unrecognised string)
        //   -2    → out of range numeric
        val isClearKeyword = arg.equals("clear", ignoreCase = true) ||
            arg.equals("none", ignoreCase = true) ||
            arg.equals("off", ignoreCase = true) ||
            arg == "0"
        val parsedInt = if (isClearKeyword) null else arg.toIntOrNull()
        val newCap: Int?
        val parseError: String?
        when {
            isClearKeyword -> { newCap = null; parseError = null }
            parsedInt != null && parsedInt in 1..200_000 -> { newCap = parsedInt; parseError = null }
            parsedInt != null -> {
                // Numeric but out of range.
                newCap = null
                parseError = "⚡ Value out of range. Use 1..200000, or 'clear' to remove the cap."
            }
            else -> {
                // Not a number, not a keyword. Truncate arg in case it's very long.
                newCap = null
                parseError = "⚡ Could not parse \"${arg.take(40)}\". Use a number or 'clear'."
            }
        }
        if (parseError != null) {
            try { client.sendMessage(chatId, parseError) } catch (_: Throwable) {}
            return
        }
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistant.id) it.copy(maxTokens = newCap) else it
                }
            )
        }
        val msg = if (newCap == null) "⚡ Max-token cap removed."
        else "⚡ Max output tokens set to $newCap."
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    /**
     * Count tool runs in the current turn that returned an error envelope for [toolName].
     * Walks the assistant messages from [baselineMessageCount] onward; for each Tool part
     * matching [toolName] that has executed, looks at its first text output and treats it
     * as a failure if the JSON has an "error" key (the standard error-envelope shape used
     * across local tools) or the un-parsed text starts with the literal "error".
     *
     * Returns the count of distinct failed runs in this turn — fed into the retry-circuit-
     * breaker before the next approval prompt is sent.
     */
    private fun recentFailedRunsOf(
        convId: kotlin.uuid.Uuid,
        toolName: String,
        baselineMessageCount: Int,
    ): Int {
        val conv = chatService.getConversationFlow(convId).value
        val assistantTools = conv.currentMessages.drop(baselineMessageCount)
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
            .filter { it.toolName == toolName && it.isExecuted }
        var failures = 0
        for (t in assistantTools) {
            val outText = t.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }.trim()
            if (outText.isEmpty()) continue
            val isError = runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(outText)
                    as? kotlinx.serialization.json.JsonObject
                obj?.containsKey("error") == true
            }.getOrDefault(false) || outText.startsWith("{\"error\"") || outText.startsWith("error", ignoreCase = true)
            if (isError) failures++
        }
        return failures
    }

    /**
     * True if this chat's conversation has any Tool part that's been approved (or auto-
     * approved) but hasn't finished executing yet — typically a tool that backgrounded the
     * app to another activity (take_photo to camera, launch_app, system intents). The
     * tryLock-fail path treats this the same as a parked approval keyboard: a fresh user
     * message means abandon the in-flight tool, not bounce.
     */
    private suspend fun hasInFlightApprovedTool(chatId: Long): Boolean {
        val mapping = runCatching { chatRepo.getByChatId(chatId) }.getOrNull() ?: return false
        val convId = runCatching { Uuid.parse(mapping.conversationId) }.getOrNull() ?: return false
        val conv = runCatching { chatService.getConversationFlow(convId).value }.getOrNull() ?: return false
        return conv.currentMessages
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
            .any { !it.isPending && !it.isExecuted }
    }

    /**
     * Quietly cancel the prior turn for [chatId] without sending a "🛑 Cancelled" message.
     * Used by the auto-/stop path when the user sends a new text message while a Pending
     * tool approval is parked — they're implicitly asking us to drop the stuck turn and
     * answer the new question instead, so we cancel without noise.
     */
    private suspend fun autoCancelStuckTurn(chatId: Long) {
        val mapping = chatRepo.getByChatId(chatId) ?: return
        val convId = runCatching { Uuid.parse(mapping.conversationId) }.getOrNull() ?: return
        chatService.stopGeneration(convId)
        turnJobs.remove(chatId)?.let { runCatching { it.cancelAndJoin() } }
        cancelStaleApprovalKeyboards(chatId, reason = "auto-cancelled by new message")
        runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<me.rerere.rikkahub.subagent.SubAgentRegistry>()
                .cancelAllForParent(convId.toString())
        }
    }

    /**
     * Rescue an image artifact when the model called an image-producing tool but forgot
     * to chain into `telegram_send_photo`. Returns true if we actually dispatched a
     * photo to Telegram, false if there was nothing to rescue.
     *
     * Covered tools (and the JSON-output key they each use for the file path):
     *  - `take_screenshot` — writes `gallery_path` (Pictures/RikkaHub/Screenshots) +
     *    `file_path` (cache).
     *  - `take_photo` — writes `gallery_path` (cache).
     *  - `browser_screenshot` — writes `file_path` (cache/browser-shots).
     *  - `show_image` — writes `path`.
     *
     * Walks the most recent assistant message's tool calls newest-first, finds the
     * first one matching the allowlist whose output JSON has `success: true` and any of
     * the recognised path keys pointing at an existing local image file, then sends
     * that file via the Telegram Bot API with a caption that explains the rescue.
     */
    private suspend fun tryRescueImageFromTurn(
        convId: kotlin.uuid.Uuid,
        baselineMessageCount: Int,
        chatId: Long,
    ): Boolean {
        val lastAssistant = runCatching {
            val conv = chatService.getConversationFlow(convId).value
            conv.currentMessages.drop(baselineMessageCount)
                .lastOrNull { it.role == MessageRole.ASSISTANT }
        }.getOrNull() ?: return false
        val tools = lastAssistant.parts.filterIsInstance<UIMessagePart.Tool>()
        if (tools.isEmpty()) return false
        // Tools that produce a single image file we can re-upload as a photo. Order
        // doesn't matter — we walk the assistant's tool calls newest-first.
        val rescueable = setOf(
            "take_screenshot",
            "take_photo",
            "browser_screenshot",
            "show_image",
        )
        // Walk newest-first so if the model took multiple screenshots we send the last one.
        for (tool in tools.reversed()) {
            if (tool.toolName !in rescueable) continue
            val outText = tool.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(outText)
                    as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: continue
            // Tools emit success either as a JSON boolean (`put("success", true)`) or
            // a quoted-string boolean. JsonPrimitive.booleanOrNull handles the boolean
            // case; toBooleanStrictOrNull catches the string case. Either path = ok.
            val ok = parsed["success"]?.jsonPrimitive?.let { p ->
                p.booleanOrNull ?: p.contentOrNull?.toBooleanStrictOrNull()
            } == true
            if (!ok) continue
            val path = parsed["gallery_path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && !it.startsWith("(") }
                ?: parsed["file_path"]?.jsonPrimitive?.contentOrNull
                ?: parsed["path"]?.jsonPrimitive?.contentOrNull
                ?: continue
            val file = java.io.File(path)
            if (!file.exists() || !file.isFile) continue
            val caption = when (tool.toolName) {
                "take_screenshot" ->
                    "📸 (rescued — model took the screenshot but didn't reply with a description)"
                "take_photo" ->
                    "📸 (rescued — model captured the photo but didn't reply with a description)"
                "browser_screenshot" ->
                    "📸 (rescued — model captured the browser page but didn't reply with a description)"
                "show_image" ->
                    "📸 (rescued — model surfaced an image but didn't reply with a description)"
                else ->
                    "📸 (rescued — model captured an image but didn't reply with a description)"
            }
            return runCatching {
                client.sendPhoto(chatId, file, caption)
                android.util.Log.i(TAG, "tryRescueImageFromTurn: sent ${tool.toolName} artifact to chat=$chatId path=$path")
                true
            }.getOrElse {
                android.util.Log.w(TAG, "tryRescueImageFromTurn: sendPhoto failed", it)
                false
            }
        }
        return false
    }

    /**
     * /doctor — run all DoctorChecks and stream the formatted report back to Telegram.
     * Same data the in-app Doctor screen renders; useful when the user is remote and only
     * has Telegram. Runs the checks inline (so cron/foreground tools see the same Conext).
     */
    private suspend fun handleDoctorCommand(chatId: Long) {
        try { client.sendChatAction(chatId, "typing") } catch (_: Throwable) {}
        val results = runCatching { doctorChecks.runAll() }.getOrElse {
            try {
                client.sendMessage(
                    chatId,
                    "🩺 Doctor failed to run: ${it::class.simpleName}: ${it.message ?: "(no message)"}",
                )
            } catch (_: Throwable) {}
            return
        }
        val report = me.rerere.rikkahub.ui.pages.setting.doctor.DoctorReport.format(results)
        // Chunk on raw text and send each chunk wrapped in <pre>...</pre> for monospace
        // rendering. Skip sendChunked's markdown→HTML pass (it would mangle the report's
        // existing layout); use the HTML parse mode directly with our own escaping.
        val chunks = chunk(report, MAX_CHARS - 16)  // leave room for the <pre> wrapper
        for (c in chunks) {
            val html = "<pre>${me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer.escape(c)}</pre>"
            runCatching {
                client.sendMessage(chatId, html, parseMode = PARSE_MODE_HTML)
            }.onFailure {
                // Fallback to plain text if HTML send fails for any reason.
                runCatching { client.sendMessage(chatId, c) }
            }
        }
    }

    /**
     * `/stream` — show or toggle whether tool screenshots auto-stream to this chat.
     * No arg = show + toggle. Arg `on` / `off` = set explicitly. Stored globally on the
     * bot config (not per-chat) since users with one Telegram account → one bot expect
     * one knob; both streamers read the same flag.
     */
    private suspend fun handleStreamCommand(chatId: Long, arg: String) {
        val current = runCatching { prefs.current().streamScreenshots }.getOrDefault(true)
        val target: Boolean? = when (arg.trim().lowercase()) {
            "" -> !current  // toggle
            "on", "true", "yes", "1", "enable", "enabled" -> true
            "off", "false", "no", "0", "disable", "disabled" -> false
            else -> null
        }
        if (target == null) {
            try {
                client.sendMessage(
                    chatId,
                    "🖼️ Auto-stream is currently ${if (current) "ON" else "OFF"}. " +
                        "Use /stream on or /stream off to set explicitly, or /stream alone to toggle.",
                )
            } catch (_: Throwable) {}
            return
        }
        runCatching { prefs.update { it.copy(streamScreenshots = target) } }
        val msg = if (target) {
            "🖼️ Auto-stream ON. Screenshots will be sent here after each browser action and after every interactive tool fires."
        } else {
            "🖼️ Auto-stream OFF. Tool screenshots will NOT be sent. Re-enable with /stream on."
        }
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    /**
     * Push the canonical built-in command list to Telegram + any custom commands the LLM
     * has previously persisted via telegram_set_commands. Called once on bot service
     * start. Without merging the custom commands here, every app restart would silently
     * wipe everything the model has added — the user would lose /weather, /reminder,
     * etc. on every reboot.
     */
    private suspend fun registerBuiltInCommandsWithTelegram() {
        try {
            val custom = try { prefs.current().customCommands } catch (_: Throwable) { emptyList() }
            val merged = BUILT_IN_COMMANDS + custom
            val ok = client.setMyCommands(merged)
            android.util.Log.i(TAG, "registerBuiltInCommandsWithTelegram: setMyCommands ok=$ok (builtins=${BUILT_IN_COMMANDS.size}, custom=${custom.size})")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "registerBuiltInCommandsWithTelegram failed", e)
        }
    }

    companion object {
        const val TAG = "TelegramBotService"
        const val CHANNEL_ID = "rikkahub_telegram_bot"
        const val NOTIF_ID = 0xA1B2

        // Telegram's HARD per-message limit is 4096. We chunk at 3500 (was 4000) because
        // chunks are markdown-rendered to HTML downstream and the rendered HTML can be
        // LONGER than the source: `**foo**` (7) → `<b>foo</b>` (10), a single `[text](url)`
        // can swell by 12+ chars per occurrence, and there's no upper bound on
        // markdown→HTML expansion in pathological cases. A 4000-char chunk packed with
        // bold + headers (e.g. a 25-paragraph story with `**Paragraph N:**` lines) easily
        // pushed Telegram over the 4096 cap and got 400 MESSAGE_TOO_LONG, then the
        // try-catches silently swallowed both the HTML and stripped-text retries leaving
        // the user with no message at all. 3500 leaves ~600 bytes of headroom — enough
        // for typical markdown-heavy text without making chunks needlessly small.
        const val MAX_CHARS = 3500

        /** Long-poll request can take ~50s server-side + a few seconds for the client to
         *  handle inbound updates and dispatch them. 75s is comfortable headroom; the wake
         *  lock is auto-released in finally before each next cycle so a longer hang cannot
         *  leak it. */
        const val WAKELOCK_TIMEOUT_MS: Long = 75_000L

        /** Initial placeholder text the bot posts before streaming begins. */
        const val STREAM_PLACEHOLDER = "..."

        /** Trailing tick the live edit appends so the user can tell the bot is mid-stream
         *  versus finished. The final edit drops it. */
        const val STREAM_TICK = "▌"

        /** Timer-driven cadence for live edits. 600ms feels close to typing without
         *  tripping Telegram's edit limiter when paired with the gap floor below. */
        const val STREAM_EDIT_INTERVAL_MS: Long = 600L

        /** Hard floor between any two edits to the same placeholder, regardless of why
         *  the edit was triggered (timer or burst). Telegram returns 429 if you go faster. */
        const val STREAM_EDIT_MIN_GAP_MS: Long = 400L

        /** When the rendered text grows by at least this many characters since the last
         *  edit, fire an edit immediately instead of waiting for the next timer tick.
         *  Makes long token bursts feel instant. */
        const val STREAM_EDIT_BURST_THRESHOLD_CHARS: Int = 80

        /** parse_mode value for outbound LLM-generated messages. We render through
         *  TelegramHtmlRenderer first so the body uses Telegram's tiny HTML subset. */
        const val PARSE_MODE_HTML: String = "HTML"

        /** Inline-keyboard callback_data prefix and per-scope discriminators for tool-
         *  approval prompts. Telegram caps callback_data at 64 bytes; "apv:N:<uuid>" is
         *  4 + 36 = 40 bytes, comfortably under. */
        const val APPROVAL_CB_PREFIX: String = "apv:"
        const val APPROVAL_CB_ONCE: String = "1"
        const val APPROVAL_CB_CHAT: String = "2"
        const val APPROVAL_CB_ALWAYS: String = "3"
        const val APPROVAL_CB_DENY: String = "4"

        /** Inline-keyboard prefix for /model interactive picker. callback_data is
         *  "mdl:<short-token>" where the token is a numeric handle into ModelPickRegistry —
         *  some provider model_ids are too long to fit Telegram's 64-byte cap directly. */
        const val MODEL_CB_PREFIX: String = "mdl:"

        /** Inline-keyboard prefix for the /model provider step (two-step picker). callback_data
         *  is "mdp:<short-token>" → ProviderPickRegistry resolves to a provider id. A trailing
         *  "mdp:back" entry re-shows the provider step from the model step. The two-step layout
         *  exists because users with many models per provider blew past Telegram's per-message
         *  inline-keyboard cap (#1) and got no response at all. */
        const val PROVIDER_CB_PREFIX: String = "mdp:"
        const val PROVIDER_CB_BACK: String = "mdp:back"

        /** Maximum file size for auto-downloading inbound non-photo attachments.
         *  Telegram allows up to 2 GB; we cap at 50 MB to avoid surprise storage use. */
        const val INBOUND_ATTACHMENT_SIZE_CAP_BYTES: Long = 50L * 1024 * 1024   // 50 MB

        /** Total size cap for the per-chat attachment inbox. Oldest files are pruned when
         *  this is exceeded. */
        private const val INBOUND_ATTACHMENT_INBOX_CAP_BYTES: Long = 500L * 1024 * 1024  // 500 MB

        /** How many models to show per page in the /model picker. Issue #1 escalation:
         *  one user reported a provider with ~256 models was rendered as a 30-row vertical
         *  wall (the prior `MODEL_PICKER_BUTTON_CAP` truncated to 30 with no way to see
         *  the rest from inside Telegram). 10 per page keeps the keyboard short and adds
         *  prev/next navigation that scales to arbitrary model counts. */
        const val MODEL_PICKER_PAGE_SIZE: Int = 10

        /**
         * Process-scoped registry mapping short numeric tokens to full model IDs. The
         * /model picker registers each visible button's model id under a fresh token, and
         * the callback handler resolves the token back. We can't put the model_id straight
         * into callback_data because Telegram caps it at 64 bytes and some provider model
         * IDs exceed the budget when combined with the prefix. Reset on every /model call.
         */
        object ModelPickRegistry {
            private val byToken = java.util.concurrent.ConcurrentHashMap<String, String>()
            private val nextId = java.util.concurrent.atomic.AtomicInteger(0)
            fun register(modelId: String): String {
                val token = nextId.incrementAndGet().toString()
                byToken[token] = modelId
                return token
            }
            fun resolve(token: String): String? = byToken[token]
            fun clear() { byToken.clear() }
        }

        /**
         * Process-scoped registry mapping short numeric tokens to provider IDs for the
         * /model two-step picker. Same shape and rationale as ModelPickRegistry — provider
         * IDs are UUIDs and would overflow Telegram's 64-byte callback_data cap when
         * combined with the prefix. Reset on every fresh /model invocation.
         */
        object ProviderPickRegistry {
            private val byToken = java.util.concurrent.ConcurrentHashMap<String, String>()
            private val nextId = java.util.concurrent.atomic.AtomicInteger(0)
            fun register(providerId: String): String {
                val token = nextId.incrementAndGet().toString()
                byToken[token] = providerId
                return token
            }
            fun resolve(token: String): String? = byToken[token]
            fun clear() { byToken.clear() }
        }

        // No approval timeout / auto-deny — the user explicitly asked for "no timeout
        // because the user is busy and might take long to answer". The streaming jobs
        // are torn down between iterations of handleLlmTurn (see the per-iteration
        // typing/edit launch + cancelAndJoin) so a long wait doesn't burn battery or
        // Telegram quota. /stop is still effective via the built-in fast-path.

        /**
         * Process-scoped registry of (toolCallId → (chatId, messageId)) for in-flight
         * approval prompts. Lets the callback handler edit/clean up the right Telegram
         * message when a tap arrives.
         *
         * Soft-capped at MAX_ENTRIES (FIFO of insertion order). Without the cap, a model
         * that produces many never-resolved approval prompts (user away for days) would
         * leak entries until process death. The cap evicts oldest first so any in-flight
         * approval the user might still tap stays addressable.
         */
        object ApprovalPromptRegistry {
            data class Entry(val chatId: Long, val messageId: Long)
            private const val MAX_ENTRIES = 256
            private val byCallId = java.util.concurrent.ConcurrentHashMap<String, Entry>()
            // Tracks insertion order so we know which entry is oldest when we hit the cap.
            // Bounded LinkedHashMap on the same key set would do this for us, but we need
            // concurrent reads, so we pair the concurrent map with a synchronised deque.
            private val insertionOrder = java.util.concurrent.LinkedBlockingDeque<String>()
            fun register(toolCallId: String, chatId: Long, messageId: Long) {
                val wasNew = byCallId.put(toolCallId, Entry(chatId, messageId)) == null
                if (wasNew) {
                    insertionOrder.addLast(toolCallId)
                    // Evict oldest entries while we're over the cap. If pollFirst returns a
                    // key that was already removed from byCallId (e.g. after a clear()), the
                    // remove is a no-op — that's fine, we keep looping until we're under cap.
                    while (byCallId.size > MAX_ENTRIES) {
                        val oldest = insertionOrder.pollFirst() ?: break
                        byCallId.remove(oldest)
                    }
                }
                // If the key was already present, byCallId is updated in-place above. The
                // existing position in insertionOrder is still correct for FIFO eviction
                // (re-registering the same toolCallId re-uses the original slot). No
                // structural change to insertionOrder needed.
            }
            fun get(toolCallId: String): Entry? = byCallId[toolCallId]
            fun clear(toolCallId: String) {
                if (byCallId.remove(toolCallId) != null) {
                    insertionOrder.remove(toolCallId)
                }
            }
            /** Drop every prompt we registered for [chatId]. Called on /new so a reset
             *  conversation doesn't leave stale (toolCallId → messageId) lookups behind. */
            fun clearChat(chatId: Long) {
                val toRemove = byCallId.entries.asSequence()
                    .filter { it.value.chatId == chatId }
                    .map { it.key }
                    .toList()
                for (k in toRemove) {
                    byCallId.remove(k)
                    insertionOrder.remove(k)
                }
            }

            /** Snapshot of every entry whose chatId == [chatId]. Used by /stop and /new
             *  to edit each registered keyboard message in place to "Cancelled" before
             *  clearing the registry — without this the user sees orphan buttons forever. */
            fun snapshotForChat(chatId: Long): List<Pair<String, Entry>> {
                return byCallId.entries.asSequence()
                    .filter { it.value.chatId == chatId }
                    .map { it.key to it.value }
                    .toList()
            }
        }

        /**
         * Process-scoped log of the most recently rejected (non-whitelisted) sender. The
         * foreground notification reads this so a user who enabled the bot with an empty
         * whitelist can DM the bot once, see the rejection in the notification, and copy
         * their chat_id into the whitelist UI. Without this you'd have to dig through
         * logcat to discover your own Telegram chat_id.
         */
        data class RejectedSender(val senderId: Long, val chatId: Long, val atMs: Long)
        object RejectedSenderLog {
            @Volatile private var last: RejectedSender? = null
            fun record(senderId: Long, chatId: Long) {
                last = RejectedSender(senderId, chatId, System.currentTimeMillis())
            }
            fun latest(): RejectedSender? = last
            fun clear() { last = null }
        }

        /**
         * Process-scoped per-chat ring of recently-handled slash commands. Used to inject
         * "the user just ran /model X" context into the next LLM turn so the model knows
         * what the user did via the app's UI rather than via tool calls. Trims by TTL on
         * read so stale entries vanish without a sweeper.
         */
        object SlashCommandLog {
            private const val MAX_PER_CHAT = 8
            // MutableList values are always accessed under the list's own monitor. CHM
            // provides safe get/putIfAbsent so we can obtain the list atomically; all
            // mutations then go through synchronized(list) so record() and recent() never
            // interleave on the same entry. Using compute() directly was incorrect because
            // it held the CHM bucket lock — not list's monitor — while mutating the list,
            // allowing a concurrent recent() call holding list's monitor to see a
            // partially-updated list.
            private val byChat = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Pair<String, Long>>>()

            fun record(chatId: Long, display: String) {
                val now = System.currentTimeMillis()
                val list = byChat.getOrPut(chatId) { mutableListOf() }
                synchronized(list) {
                    list.add(display to now)
                    while (list.size > MAX_PER_CHAT) list.removeAt(0)
                }
            }

            fun recent(chatId: Long, ttlMs: Long): List<Pair<String, Long>> {
                val list = byChat[chatId] ?: return emptyList()
                val cutoff = System.currentTimeMillis() - ttlMs
                synchronized(list) {
                    list.removeAll { (_, ts) -> ts < cutoff }
                    return list.toList()
                }
            }
        }

        /**
         * The single source of truth for the bot's built-in slash-command menu. Each entry
         * is (command-without-slash, description shown in Telegram's autocomplete menu).
         * Order matches what the user sees when they tap "/" in the chat.
         *
         * Telegram caps each description at 256 chars and the command at 32 chars; keep
         * descriptions short.
         */
        val BUILT_IN_COMMANDS: List<Pair<String, String>> = listOf(
            "start" to "Show a quick welcome and the most useful commands",
            "help" to "List every built-in slash command",
            "new" to "Start a fresh conversation (clears history)",
            "stop" to "Cancel the current generation immediately",
            "status" to "Show service state, current model, assistant, and rate limit",
            "model" to "Show or switch the chat model. Usage: /model [name]",
            "ratelimit" to "Show or set the assistant's max output tokens. Usage: /ratelimit [number|clear]",
            "doctor" to "Run app diagnostics — perms, services, DB, network, Termux",
            "stream" to "Show or toggle auto-streamed screenshots. Usage: /stream [on|off]",
        )

        /** Set whenever the service is alive AND its long-poll loop is running. */
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TelegramBotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramBotService::class.java))
        }
    }
}

/**
 * Sanitize a raw Telegram-supplied filename into a safe basename.
 *
 * Rules (applied in order):
 * 1. Strip any directory separators (/ and \) so `../../etc/passwd` becomes `passwd`.
 * 2. Strip leading dots to avoid hidden-file names.
 * 3. Remove ASCII control characters (0x00–0x1F) and null bytes.
 * 4. Trim whitespace from both ends.
 * 5. Truncate to 200 characters preserving the file extension.
 * 6. If the result is empty after sanitization, fall back to `tg-<timestamp>-<fileIdSuffix>.<ext>`.
 */
internal fun sanitizeAttachmentFilename(
    raw: String?,
    fileId: String,
    fallbackExt: String,
): String {
    if (!raw.isNullOrBlank()) {
        // 1. Take the last path component only (strip directory separators).
        var name = raw.replace('\\', '/').substringAfterLast('/')
        // 2. Strip leading dots.
        name = name.trimStart('.')
        // 3. Remove control characters.
        name = name.filter { it.code > 0x1F }
        // 4. Trim whitespace.
        name = name.trim()
        // 5. Truncate to 200 chars, preserving extension.
        if (name.length > 200) {
            val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
            val base = name.substringBeforeLast('.').take(200 - ext.length)
            name = "$base$ext"
        }
        if (name.isNotEmpty()) return name
    }
    // 6. Fallback.
    return "tg-${System.currentTimeMillis()}-${fileId.takeLast(8)}.$fallbackExt"
}

/**
 * Return a [java.io.File] in [dir] with [preferredName] that does not already exist.
 * If a file with that name already exists, appends `-<timestamp>` before the extension.
 */
internal fun uniqueFile(dir: java.io.File, preferredName: String): java.io.File {
    val candidate = java.io.File(dir, preferredName)
    if (!candidate.exists()) return candidate
    val ts = System.currentTimeMillis()
    val ext = if (preferredName.contains('.')) ".${preferredName.substringAfterLast('.')}" else ""
    val base = if (ext.isNotEmpty()) preferredName.substringBeforeLast('.') else preferredName
    return java.io.File(dir, "$base-$ts$ext")
}

/**
 * Build the structured note appended to the user message when inbound photos arrive, so the
 * LLM learns the saved file path(s). Mirrors [TelegramBotService.buildAttachmentNote] — it
 * lets the model OCR / process the image via file tools or Termux even when the configured
 * model has no vision pipeline. Returns "" when no photo was saved.
 */
internal fun buildPhotoNote(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    val noun = if (paths.size == 1) "photo" else "photos"
    val sb = StringBuilder()
    sb.append("[User attached ${paths.size} $noun with this message:\n")
    for (p in paths) {
        sb.append("- photo → saved to $p\n")
    }
    sb.append("View it directly if you have vision, or process the file at that path (e.g. OCR with `tesseract` via Termux).]")
    return sb.toString()
}
