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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
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
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.data.telegram.TelegramCallbackQuery
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import me.rerere.rikkahub.data.telegram.TelegramIncomingMessage
import me.rerere.rikkahub.data.telegram.TelegramMyChatMember
import me.rerere.rikkahub.data.telegram.parseCallbackQuery
import me.rerere.rikkahub.data.telegram.parseIncoming
import me.rerere.rikkahub.data.telegram.parseMyChatMember
import kotlinx.serialization.json.JsonObject
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

    // The DI-injected dependencies are `internal` (not `private`) because the command
    // handler extension functions in TelegramCommandHandlers.kt access them. Module-private
    // is the same practical scope — only :app classes can resolve TelegramBotService, and
    // it's Koin-managed there.
    internal val prefs: TelegramBotPreferences by inject()
    internal val client: TelegramBotClient by inject()
    internal val chatService: ChatService by inject()
    internal val conversationRepo: ConversationRepository by inject()
    internal val chatRepo: TelegramChatRepository by inject()
    internal val settingsStore: SettingsStore by inject()
    internal val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks by inject()
    internal val agentRunRepo: me.rerere.rikkahub.data.agentrun.AgentRunRepository by inject()
    // Phase 24 — shared long-poll stall tracker. The poll loop stamps it on every
    // getUpdates; the stall checker reads it; DoctorChecks reads it.
    private val pollStallTracker: me.rerere.rikkahub.data.telegram.TelegramPollStallTracker by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var externalGenPumpJob: Job? = null
    // Phase 24 — the periodic poll-stall checker coroutine. Launched alongside the poll
    // loop in onStartCommand, cancelled with the service scope.
    private var pollStallCheckerJob: Job? = null

    /**
     * Per-chat serialization for non-built-in messages. The poll loop launches each
     * inbound message in its own coroutine (so it can return to getUpdates immediately
     * and pick up /stop while a long generation is in flight), but two LLM round-trips
     * for the same chat must NOT interleave. Built-in slash commands skip this mutex
     * entirely so /stop and /new run the moment they arrive.
     */
    private val chatMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    internal fun mutexFor(chatId: Long): Mutex = chatMutexes.getOrPut(chatId) { Mutex() }

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
    internal val turnJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

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

    /**
     * Deferred-removal jobs for [activeHandleIncomingConvs]. When [handleIncoming] exits,
     * the set entry stays for [ACTIVE_INCOMING_LINGER_MS] so the external generation-done
     * pump still sees the conv as "Telegram-pumped right now" and skips it.
     *
     * Why the linger: [chatService.sendMessage]'s launch block emits to
     * [chatService.generationDoneFlow] BEFORE the launch block exits, but the pump's
     * `collect { }` lambda runs on a separate coroutine. There is a tiny window where
     * [handleIncoming] can return + run its finally (remove from activeHandleIncomingConvs)
     * BEFORE the pump's lambda processes the emit — typically observed on very fast
     * generations (sub-second), where handleIncoming's final Telegram edit + cleanup
     * race past the pump's scheduling latency. Without the linger the pump then sees
     * the conv as not-actively-pumped and sends a duplicate copy of the assistant text
     * to Telegram (visible to the user as the same reply landing twice in a row).
     *
     * A new handleIncoming for the same chat cancels any pending removal so the entry
     * doesn't get dropped mid-turn.
     */
    private val pendingActiveIncomingRemovals: java.util.concurrent.ConcurrentHashMap<Uuid, Job> =
        java.util.concurrent.ConcurrentHashMap<Uuid, Job>()

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
        // Phase 24 — launch the poll-stall checker as a child of the service scope (so it
        // is auto-cancelled on FGS teardown). It periodically asks the tracker whether
        // getUpdates has gone silent inside a still-live FGS; on a stall it recycles JUST
        // the poll loop (not the service). GUARDED like externalGenPumpJob below — every
        // onStartCommand would otherwise spawn a duplicate checker.
        if (pollStallCheckerJob?.isActive != true) {
            pollStallTracker.markUpdate()  // fresh baseline so the checker doesn't fire on stale state
            val checker = TelegramPollStallChecker(
                tracker = pollStallTracker,
                restartPollLoop = ::restartPollLoop,
                onEscalate = ::escalatePollStallToFgsRestart,
            )
            pollStallCheckerJob = scope.launch { checker.monitor() }
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
        } catch (e: Throwable) {
            // Notifications can fail in restricted contexts (POST_NOTIFICATIONS revoked,
            // channel blocked); non-fatal, but log so a vanished notification leaves a trace.
            android.util.Log.w(TAG, "updateForegroundNotification failed", e)
        }
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
        // Persisted offset survives process death: a cold start replays no more than
        // the updates that arrived in the gap. Without this, an OEM kill -> next boot
        // re-processes up to 24 h of cached updates (Telegram retains unconfirmed
        // server-side) and the bot replies to ancient messages.
        var offset = runCatching { prefs.lastOffset() }.getOrDefault(0L)
        android.util.Log.i(TAG, "pollLoop: starting at offset=$offset")
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
                runCatching { prefs.setLastOffset(0L) }
                consecutiveErrors = 0
            }
            lastTokenSeen = cfg.token
            // Held only for the duration of one long-poll cycle, and only when the screen
            // is off - while the device is interactive the CPU stays awake on its own, so
            // the lock is pure battery waste (see shouldHoldPollWakeLock). Released in
            // finally so a crash during getUpdates does not leak the wakelock.
            val wakeLock = if (shouldHoldPollWakeLock(pm?.isInteractive)) {
                pm?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "rikkahub:telegram_long_poll",
                )?.also { it.setReferenceCounted(false) }
            } else null
            try {
                cycle++
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                val updates = client.getUpdates(offset, 30)
                // Phase 24 — stamp the stall tracker on every successful getUpdates round-
                // trip (success OR empty result). A long-poll that returns at all — even
                // with zero updates — proves the loop is alive; the stall checker only
                // fires when this stamp goes stale for >90s.
                pollStallTracker.markUpdate()
                if (cycle <= 2 || updates.isNotEmpty()) {
                    android.util.Log.i(TAG, "pollLoop: cycle=$cycle offset=$offset updates=${updates.size}")
                }
                val offsetBefore = offset
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
                    val mcm = parseMyChatMember(u)
                    if (mcm != null) {
                        android.util.Log.i(TAG, "pollLoop: my_chat_member chat=${mcm.chatId} newStatus=${mcm.newStatus}")
                        scope.launch {
                            try { handleMyChatMember(mcm) }
                            catch (e: Throwable) { android.util.Log.e(TAG, "handleMyChatMember threw for chat=${mcm.chatId}", e) }
                        }
                        continue
                    }
                    // Unknown update type — offset already bumped, just drop it.
                }
                // Persist the high-water offset once per cycle (cheap async DataStore write).
                // Only when it actually advanced — idle cycles don't need the disk hit.
                if (offset != offsetBefore) {
                    runCatching { prefs.setLastOffset(offset) }
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
                    postTokenInvalidNotification(e.errorCode, e.description)
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

    /**
     * Phase 24 — recycle ONLY the long-poll coroutine, leaving the FGS alive. Called by
     * [TelegramPollStallChecker] when getUpdates has gone silent. Cancelling and
     * relaunching the poll job forces a fresh OkHttp connection + DNS lookup, which clears
     * the common "stuck socket after a background network transition" stall without the
     * cost of a full service restart (notification flash, OkHttp re-warm).
     */
    private fun restartPollLoop() {
        android.util.Log.w(TAG, "restartPollLoop: recycling the long-poll coroutine")
        runCatching { pollJob?.cancel() }
        pollJob = scope.launch { pollLoop() }
    }

    /**
     * Phase 24 — flapping-defence escalation. When the poll loop has been recycled past the
     * flap ceiling within the flap window, a local recycle clearly isn't fixing it (network
     * down, token issue, OEM freeze). Hand off to a full service restart via
     * [TelegramBotHealthWorker]'s next pass — it re-arms the FGS from scratch. Silent
     * recovery: no user notification (per spec).
     */
    private fun escalatePollStallToFgsRestart() {
        android.util.Log.w(TAG, "escalatePollStallToFgsRestart: poll loop flapping — requesting FGS restart")
        runCatching {
            // The health worker is idempotent (ExistingPeriodicWorkPolicy.KEEP) and its
            // next pass re-starts the service if it finds it unhealthy. Re-scheduling here
            // ensures the worker is armed even if it had been cancelled.
            TelegramBotHealthWorker.schedule(applicationContext)
            // Also stop ourselves so the worker's next pass brings up a clean instance —
            // START_NOT_STICKY means we won't auto-revive without a fresh foreground ticket,
            // which the health worker provides via startForegroundService.
            stopSelf()
        }.onFailure { android.util.Log.w(TAG, "escalatePollStallToFgsRestart failed", it) }
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
     * Handle a `my_chat_member` update: the bot's membership in [TelegramMyChatMember.chatId]
     * just changed. We only care about the terminal states — `kicked` (user blocked the bot
     * in a private chat) and `left` (bot was removed from a group). On either, drop the
     * chat -> conversation mapping so subsequent outbound paths can't keep posting into a
     * dead chat, then cancel any parked turn for the chat. We don't try to send a goodbye
     * message — Telegram would 403 the send for `kicked` chats anyway.
     */
    private suspend fun handleMyChatMember(update: TelegramMyChatMember) {
        val terminal = update.newStatus == "kicked" || update.newStatus == "left"
        if (!terminal) return
        // Cancel any parked turn for this chat so the per-chat mutex releases.
        turnJobs.remove(update.chatId)?.let { runCatching { it.cancelAndJoin() } }
        // Drop the chat -> conversation mapping. Conversation rows stay in the DB so the
        // user can still browse history in-app; only the Telegram routing breaks.
        runCatching { chatRepo.deleteByChatId(update.chatId) }
        // Stale approval keyboards in this chat are now orphan; their messageIds belong to
        // a chat we can no longer edit. Drop them from the registry so no future cancellation
        // sweep tries (and fails) to edit them.
        runCatching { ApprovalPromptRegistry.clearChat(update.chatId) }
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

        // A pending interactive ask_user question awaiting a free-form reply consumes this
        // message as its answer (feeding it back to the parked generation) instead of starting
        // a new turn.
        if (tryResolveClarifyText(m.chatId, m.text)) return
        // Otherwise the message starts something new, so any earlier unanswered clarify for this
        // chat is abandoned — drop it so a stale entry doesn't linger.
        clearClarifyForChat(m.chatId)

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
        val (imageParts, photoPaths) = downloadInboundPhotos(client, m.chatId, m.photoFileIds)
        val photoNote = buildPhotoNote(photoPaths)

        // Download non-photo attachments (documents, audio, video, voice, video_note) to
        // /sdcard/Download/telegram_inbox/<chatId>/ and build a structured note for the LLM.
        val downloadedAttachments = downloadInboundAttachments(client, m.chatId, m.attachments)
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

        // Phase 24 — open a cross-pillar ledger row for THIS Telegram turn. kind=telegram,
        // domain_id=conversation id; one row per turn (a fresh row each inbound message).
        // Marked terminal in the finally block below. Best-effort — never breaks the turn.
        val ledgerId = agentRunRepo.open(
            kind = me.rerere.rikkahub.data.agentrun.AgentRunKind.Telegram,
            domainId = convId.toString(),
            metadata = buildJsonObject {
                put("chat_id", m.chatId.toString())
                put("conversation_created", wasCreated)
            },
        )

        // Fresh per-turn screenshot signal so the finalizer's "post the answer at the bottom"
        // decision reflects only THIS turn's auto-streamed photos.
        me.rerere.rikkahub.data.telegram.TelegramStreamSignal.reset(convId.toString())

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
        // already streams + finalises it on its own. Cancel any pending deferred
        // removal first so a fast back-to-back turn doesn't drop the marker mid-turn.
        pendingActiveIncomingRemovals.remove(convId)?.cancel()
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
        // Phase 24 — set when the turn is cancelled (/stop, /new) so the ledger row is
        // closed as `cancelled` rather than `succeeded` in the finally block.
        var turnCancelled = false
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
                    // ask_user is interactive, not a permission gate: render its questions as a
                    // clarify keyboard instead of an approve/deny card, and skip the failure
                    // circuit breaker (it collects an answer, it doesn't "fail").
                    if (tool.toolName == "ask_user") {
                        scope.launch { startClarify(m.chatId, convId, tool) }
                        continue
                    }
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
            turnCancelled = true
            throw e
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleIncoming: generation flow ended with error", e)
            generationError = e
        } finally {
            chatService.removeConversationReference(convId)
            // Defer the activeHandleIncomingConvs removal by ACTIVE_INCOMING_LINGER_MS so
            // the external generation-done pump still sees the conv as Telegram-pumped
            // for a short window after handleIncoming returns. Without this, the pump's
            // collect lambda — which runs on a separate coroutine and can land after
            // handleIncoming exits on fast generations — duplicates the assistant text
            // into Telegram.
            val removalJob = scope.launch {
                delay(ACTIVE_INCOMING_LINGER_MS)
                activeHandleIncomingConvs.remove(convId)
                pendingActiveIncomingRemovals.remove(convId)
            }
            pendingActiveIncomingRemovals.compute(convId) { _, existing ->
                existing?.cancel()
                removalJob
            }
            HeadlessConversations.unmark(convId)
            // Phase 24 — mark the ledger row terminal. cancelled (/stop or /new) →
            // cancelled; a generation error → failed; otherwise succeeded. Runs in the
            // finally so the row is closed even on the CancellationException re-throw path.
            val ledgerStatus = when {
                turnCancelled -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.cancelled
                generationError != null -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.failed
                else -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.succeeded
            }
            agentRunRepo.markTerminal(
                id = ledgerId,
                status = ledgerStatus,
                lastError = generationError?.let { "${it::class.simpleName}: ${it.message.orEmpty()}" },
            )
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
            placeholderId != null && finalReply.length <= MAX_CHARS &&
                me.rerere.rikkahub.data.telegram.TelegramStreamSignal.count(convId.toString()) == 0 -> {
                // Final fits in one message AND no screenshots streamed this turn; just edit
                // the placeholder one last time (it's still the newest message).
                editPlaceholderHtmlWithFallback(m.chatId, placeholderId, finalReply)
            }
            else -> {
                // Either the reply overflowed one message, OR screenshots were auto-streamed
                // during the turn. In the screenshot case the placeholder is now buried ABOVE
                // the photos, so editing it would leave the answer where the user has to scroll
                // up past every screenshot to read it. Drop the placeholder and post the final
                // answer as a fresh message at the BOTTOM, below the photos (the Hermes
                // "deliver the final response as a new message" behaviour).
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
                val modelCanSeeImages = model?.inputModalities?.contains(Modality.IMAGE) == true
                if (modelCanSeeImages) {
                    append("IMAGE ATTACHMENT. This message includes one or more photos. You can ")
                    append("view them directly. Their saved file path(s) are also listed in the ")
                    append("message text if you need to process the file (e.g. OCR).\n")
                } else {
                    append("IMAGE ATTACHMENT — YOU CANNOT SEE IT. This message includes one or more ")
                    append("photos, but the current model has no vision capability. Do NOT describe ")
                    append("or guess what the image shows. Their saved file path(s) are listed in ")
                    append("the message text — to read the contents, OCR the file (e.g. ")
                    append("`tesseract <path> stdout` via termux_run_command) or process it with ")
                    append("another file tool.\n")
                }
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
        val tokenFooter = if (finalizing) tokenUsageFooter(
            lastAssistant,
            settingsStore.settingsFlow.value.displaySetting.showTokenUsage,
        ) else ""
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

    private fun assistantTextOf(m: UIMessage): String =
        m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    /** Telegram caps a single sendMessage at 4096 chars; split on newlines where possible. */
    internal suspend fun sendChunked(chatId: Long, text: String, replyTo: Long?) {
        val chunks = chunk(text, MAX_CHARS)
        // A very long reply (e.g. a full audit report) becomes dozens of back-to-back
        // messages, which trips Telegram's per-chat flood limit (429: retry after N) and
        // buries the content. Above MAX_INLINE_CHUNKS, upload the whole thing as a .md
        // document with a short inline preview instead. Falls through to chunked send only
        // if the document upload itself fails.
        if (chunks.size > MAX_INLINE_CHUNKS && trySendReplyAsDocument(chatId, text, replyTo)) return
        // Track delivery so we can surface a single user-visible error if every fallback
        // for some chunk fails. Silent drops were the root cause of the "model wrote a
        // long reply but Telegram never received it" bug — diagnosing it required
        // pulling logcat instead of seeing the failure in chat.
        var anyChunkFailed = false
        var lastFailure: Throwable? = null
        for ((idx, chunk) in chunks.withIndex()) {
            val html = TelegramHtmlRenderer.render(chunk)
            var err = sendWithFloodRetry(chatId, html, PARSE_MODE_HTML, if (idx == 0) replyTo else null)
            if (err != null) {
                // HTML parse failed (entity split, unclosed tag), or Telegram refused the
                // chunk. Log + retry as plain text. sendWithFloodRetry already honored any
                // 429 retry_after on the HTML attempt. The pre-fix behaviour silently
                // swallowed both branches; we record the exception so a repeat leaves a trail.
                android.util.Log.w(
                    TAG,
                    "sendChunked: HTML send failed for chunk ${idx + 1}/${chunks.size} (len=${html.length} src=${chunk.length}); retrying as plain text",
                    err,
                )
                val plain = TelegramHtmlRenderer.stripHtml(html).ifBlank { chunk }
                err = sendWithFloodRetry(chatId, plain, null, if (idx == 0) replyTo else null)
                if (err != null) {
                    android.util.Log.w(
                        TAG,
                        "sendChunked: plain-text fallback also failed for chunk ${idx + 1}/${chunks.size} (len=${plain.length})",
                        err,
                    )
                    anyChunkFailed = true
                    lastFailure = err
                }
            }
            // Pace subsequent chunks so a multi-message reply stays under Telegram's per-chat
            // send rate instead of bursting into a 429.
            if (idx < chunks.lastIndex) delay(INTER_CHUNK_DELAY_MS)
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

    /**
     * Send one Telegram message, honoring a single 429 flood-wait. Returns null on success or
     * the last error. On a 429 with retry_after, waits that long (plus a small buffer) and
     * retries once; any other error is returned immediately for the caller's fallback path.
     */
    private suspend fun sendWithFloodRetry(
        chatId: Long,
        text: String,
        parseMode: String?,
        replyToMessageId: Long?,
    ): Throwable? {
        try {
            client.sendMessage(chatId = chatId, text = text, parseMode = parseMode, replyToMessageId = replyToMessageId)
            return null
        } catch (e: TelegramApiException) {
            if (e.errorCode != 429 || e.retryAfterSec == null) return e
            android.util.Log.w(TAG, "sendWithFloodRetry: 429 flood-wait ${e.retryAfterSec}s; backing off then retrying once")
            delay(e.retryAfterSec * 1000L + 250L)
        } catch (t: Throwable) {
            return t
        }
        return try {
            client.sendMessage(chatId = chatId, text = text, parseMode = parseMode, replyToMessageId = replyToMessageId)
            null
        } catch (t: Throwable) { t }
    }

    /**
     * Save [text] to a temp .md file and upload it as a Telegram document with a short inline
     * preview, instead of fragmenting a huge reply into dozens of flood-prone messages. Returns
     * true if the document was delivered; false (temp file cleaned up) so the caller can fall
     * back to chunked send.
     */
    private suspend fun trySendReplyAsDocument(chatId: Long, text: String, replyTo: Long?): Boolean {
        var file: java.io.File? = null
        return try {
            file = java.io.File.createTempFile("rikkahub_reply_", ".md", cacheDir).apply { writeText(text) }
            val preview = text.take(500).let { if (text.length > 500) "$it…" else it }
            sendWithFloodRetry(
                chatId,
                "📄 Reply is long (${text.length} chars); the full text is attached as a file below.\n\n$preview",
                null,
                replyTo,
            )
            client.sendDocument(chatId, file, caption = "Full reply (${text.length} chars)")
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "trySendReplyAsDocument: failed, falling back to chunked send", t)
            false
        } finally {
            try { file?.delete() } catch (_: Throwable) {}
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

    internal fun chunk(s: String, n: Int): List<String> = chunkForTelegram(s, n)

    /**
     * Send an inline-keyboard approval prompt for [tool]. The prompt is its OWN Telegram
     * message (not an edit of the streaming placeholder) so the user can scroll between
     * them when the model queues up multiple Pending tools at once.
     *
     * No timeout / watchdog: the user is allowed to take as long as they need to respond
     * (they might be away from the phone for hours). The prompt stays valid until they
     * tap, /stop is sent, the conversation is reset, or the app process restarts.
     */
    // ---- Interactive ask_user (clarify) over Telegram --------------------------------
    //
    // ask_user has no executable body — in-app it's answered via a tappable question card.
    // Over Telegram we mirror the approval flow: render each question's options as an inline
    // keyboard (plus a "type your own answer" button), capture taps via the clr: callback and
    // free-form replies via the message intercept in handleIncoming, then feed the collected
    // answers back through handleToolApproval(answer=…) — the same hook the in-app card uses.
    // Modelled on the Hermes clarify_gateway pattern; questions are asked one at a time.

    private data class ClarifyQuestion(val id: String, val text: String, val options: List<String>)

    private class ClarifyPending(
        val chatId: Long,
        val convId: Uuid,
        val toolCallId: String,
        val questions: List<ClarifyQuestion>,
    ) {
        val answers = LinkedHashMap<String, String>()
        var currentIdx = 0
        var awaitingText = false
        /** message_id of the question currently on screen, so we can clear its keyboard once answered. */
        var questionMessageId: Long? = null
    }

    /** Pending clarify prompts keyed by toolCallId; resolved by a button tap or a text reply. */
    private val clarifyPending = java.util.concurrent.ConcurrentHashMap<String, ClarifyPending>()

    private fun parseClarifyQuestions(input: String): List<ClarifyQuestion> {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(input.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return emptyList()
        val arr = obj["questions"]?.jsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val q = (el as? kotlinx.serialization.json.JsonObject) ?: return@mapIndexedNotNull null
            val text = q["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val id = q["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "q$i"
            val options = q["options"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { o -> o.isNotBlank() } }
                ?: emptyList()
            ClarifyQuestion(id, text, options)
        }
    }

    /** Begin an interactive ask_user prompt over Telegram. If the questions can't be parsed,
     *  deny the call with an "ask in plain text" reason so the turn never hangs. */
    private suspend fun startClarify(chatId: Long, convId: Uuid, tool: UIMessagePart.Tool) {
        val questions = parseClarifyQuestions(tool.input)
        if (questions.isEmpty()) {
            chatService.handleToolApproval(
                conversationId = convId,
                toolCallId = tool.toolCallId,
                approved = false,
                reason = "ask_user could not be rendered over Telegram. Ask your question in plain reply text instead.",
                toolName = "ask_user",
            )
            return
        }
        val entry = ClarifyPending(chatId, convId, tool.toolCallId, questions)
        clarifyPending[tool.toolCallId] = entry
        sendClarifyQuestion(entry)
    }

    private suspend fun sendClarifyQuestion(entry: ClarifyPending) {
        val q = entry.questions.getOrNull(entry.currentIdx) ?: run { finalizeClarify(entry); return }
        val header = if (entry.questions.size > 1)
            "❓ <b>Question ${entry.currentIdx + 1}/${entry.questions.size}</b>\n\n" else "❓ "
        val body = header + TelegramHtmlRenderer.escape(q.text)
        val res = if (q.options.isEmpty()) {
            entry.awaitingText = true
            runCatching {
                client.sendMessage(entry.chatId, "$body\n\n<i>Reply with your answer.</i>", parseMode = PARSE_MODE_HTML)
            }.getOrNull()
        } else {
            entry.awaitingText = false
            runCatching {
                client.sendMessage(
                    entry.chatId, body, parseMode = PARSE_MODE_HTML,
                    replyMarkup = buildClarifyKeyboard(entry.toolCallId, entry.currentIdx, q.options),
                )
            }.getOrNull()
        }
        entry.questionMessageId = res?.get("message_id")?.jsonPrimitive?.longOrNull
    }

    private suspend fun handleClarifyCallback(cq: TelegramCallbackQuery) {
        val parts = cq.data.removePrefix(CLARIFY_CB_PREFIX).split(":", limit = 3)
        if (parts.size != 3) { client.answerCallbackQuery(cq.callbackQueryId, "malformed"); return }
        val qIdx = parts[0].toIntOrNull()
        val sel = parts[1]
        val toolCallId = parts[2]
        val entry = clarifyPending[toolCallId]
        if (entry == null) { client.answerCallbackQuery(cq.callbackQueryId, "question expired"); return }
        if (qIdx != entry.currentIdx) { client.answerCallbackQuery(cq.callbackQueryId, "already answered"); return }
        val q = entry.questions.getOrNull(entry.currentIdx)
            ?: run { client.answerCallbackQuery(cq.callbackQueryId, "done"); return }
        if (sel == "o") {
            entry.awaitingText = true
            client.answerCallbackQuery(cq.callbackQueryId, "Type your answer")
            runCatching {
                client.sendMessage(
                    entry.chatId,
                    "✍️ Type your answer for: <i>${TelegramHtmlRenderer.escape(q.text)}</i>",
                    parseMode = PARSE_MODE_HTML,
                )
            }
            return
        }
        val chosen = sel.toIntOrNull()?.let { q.options.getOrNull(it) }
        if (chosen == null) { client.answerCallbackQuery(cq.callbackQueryId, "unknown option"); return }
        client.answerCallbackQuery(cq.callbackQueryId, "✓ ${chosen.take(28)}")
        recordAndAdvance(entry, q, chosen)
    }

    /** Consume a free-form message as the answer to a clarify question awaiting text. Returns
     *  true when consumed — the caller must NOT then treat the message as a new prompt. */
    private suspend fun tryResolveClarifyText(chatId: Long, text: String): Boolean {
        if (text.isBlank()) return false
        val entry = clarifyPending.values.firstOrNull { it.chatId == chatId && it.awaitingText } ?: return false
        val q = entry.questions.getOrNull(entry.currentIdx) ?: return false
        entry.awaitingText = false
        recordAndAdvance(entry, q, text.trim())
        return true
    }

    private suspend fun recordAndAdvance(entry: ClarifyPending, q: ClarifyQuestion, answer: String) {
        entry.answers[q.id] = answer
        // Clear the just-answered question's inline keyboard and show what was picked, so the
        // buttons don't linger looking still-tappable. Editing the text without a replyMarkup
        // removes the keyboard.
        entry.questionMessageId?.let { msgId ->
            runCatching {
                client.editMessageText(
                    entry.chatId,
                    msgId,
                    "❓ ${TelegramHtmlRenderer.escape(q.text)}\n→ <b>${TelegramHtmlRenderer.escape(answer)}</b>",
                    parseMode = PARSE_MODE_HTML,
                )
            }
        }
        entry.currentIdx++
        if (entry.currentIdx < entry.questions.size) sendClarifyQuestion(entry) else finalizeClarify(entry)
    }

    private fun finalizeClarify(entry: ClarifyPending) {
        clarifyPending.remove(entry.toolCallId)
        val answerJson = buildJsonObject {
            put("answers", buildJsonObject {
                entry.questions.forEach { q ->
                    put(q.id, kotlinx.serialization.json.JsonPrimitive(entry.answers[q.id] ?: ""))
                }
            })
        }.toString()
        chatService.handleToolApproval(
            conversationId = entry.convId,
            toolCallId = entry.toolCallId,
            approved = true,
            answer = answerJson,
            toolName = "ask_user",
        )
    }

    /** Drop any pending clarify prompts for a chat — the user moved on or cancelled the turn. */
    internal fun clearClarifyForChat(chatId: Long) {
        clarifyPending.values.removeAll { it.chatId == chatId }
    }

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
                        // Inside this branch jobInput is smart-cast to non-null
                        // (mode being non-null implies the jobInput?.get(...) chain succeeded).
                        val actions = runCatching {
                            (jobInput.get("actions") as? kotlinx.serialization.json.JsonArray)
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
                        val prompt = jobInput.get("prompt")?.jsonPrimitive?.contentOrNull ?: ""
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
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "handleProviderPickCallback: back-to-providers edit failed", e)
            }
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
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleProviderPickCallback: model-list edit failed", e)
        }
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
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleModelPickCallback: switched-confirmation edit failed", e)
        }
    }

    /**
     * Handle a callback_query (inline-keyboard button tap). Whitelisted users only —
     * unauthorised taps are still acked (so Telegram clears the button spinner and stops
     * retrying the delivery) but no work is done. callback_data format:
     * "apv:<scope>:<toolCallId>" / "mdl:<token>" / "mdp:<token>" / "dfix:<id>".
     */
    private suspend fun handleCallbackQuery(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        cq: TelegramCallbackQuery,
    ) {
        val cbStartMs = System.currentTimeMillis()
        android.util.Log.i(TAG, "cb:${cq.callbackQueryId} START data=${cq.data} chat=${cq.chatId}")
        val sender = cq.senderId
        if (sender == null || (sender !in cfg.whitelist && cq.chatId !in cfg.whitelist)) {
            android.util.Log.w(TAG, "handleCallbackQuery: dropping non-whitelisted sender=$sender chat=${cq.chatId}")
            // ALWAYS ack, even when dropping. Spec requires answerCallbackQuery within
            // ~15 s or Telegram resends the tap multiple times — without this ack the
            // bot wastes a getUpdates round-trip on every retry. There is no probe-defence
            // value in withholding the ack: Telegram only routes callback_query updates
            // to the bot that produced the keyboard, so non-whitelisted taps cannot be
            // adversarial scans from outside.
            runCatching { client.answerCallbackQuery(cq.callbackQueryId) }
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
            cq.data.startsWith(DOCTOR_FIX_CB_PREFIX) -> {
                handleDoctorFixCallback(cq); return
            }
            cq.data.startsWith(CLARIFY_CB_PREFIX) -> {
                handleClarifyCallback(cq); return
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

        /** Above this many chunks a reply is uploaded as a .md document instead of dozens of
         *  back-to-back messages (which trip Telegram's per-chat flood limit, 429 retry_after). */
        const val MAX_INLINE_CHUNKS = 6

        /** Pause between chunked messages so a multi-part reply stays under Telegram's per-chat
         *  send rate and doesn't flood into a 429. */
        const val INTER_CHUNK_DELAY_MS: Long = 400L

        /** How long an activeHandleIncomingConvs entry lingers after handleIncoming exits.
         *  The external generation-done pump can land its collect lambda AFTER the finally
         *  has removed the marker, on fast (sub-second) generations. Keeping the marker
         *  for an extra 5 seconds covers any realistic pump scheduling latency without
         *  blocking legitimate sub-agent wake messages from going through after that. */
        const val ACTIVE_INCOMING_LINGER_MS: Long = 5_000L

        /** Long-poll request can take ~50s server-side + a few seconds for the client to
         *  handle inbound updates and dispatch them. 75s is comfortable headroom; the wake
         *  lock is auto-released in finally before each next cycle so a longer hang cannot
         *  leak it. */
        const val WAKELOCK_TIMEOUT_MS: Long = 75_000L

        /**
         * Whether the long-poll cycle needs to hold a partial wake lock. The lock only
         * matters when the CPU would otherwise suspend mid-poll, i.e. when the screen is
         * off. While the device is interactive the CPU is already awake, so holding it
         * then is pure waste - profiling showed the unconditional acquire kept the lock
         * held ~100% of the time, which was the app's entire wake-lock budget. Gating on
         * the non-interactive state preserves Doze survival (screen-off always gets the
         * lock) while dropping the screen-on waste.
         *
         * @param isInteractive `PowerManager.isInteractive`, or `null` when no
         *   PowerManager is available - in which case we default to holding the lock
         *   (the safe choice: never weaker than the old unconditional behaviour).
         */
        fun shouldHoldPollWakeLock(isInteractive: Boolean?): Boolean = isInteractive != true

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

        /** Inline-keyboard prefix for the /doctor "Run fix" buttons. callback_data is
         *  "dfix:<check_id>" where check_id is the DoctorCheck.id (e.g. "db.integrity").
         *  Check ids are short kebab/dot-cased identifiers, comfortably under the 64-byte cap. */
        const val DOCTOR_FIX_CB_PREFIX: String = "dfix:"

        // Other inline-keyboard prefixes, registries, and builders live in
        // TelegramKeyboards.kt (approval cards + the two-step /model picker).
        //
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

