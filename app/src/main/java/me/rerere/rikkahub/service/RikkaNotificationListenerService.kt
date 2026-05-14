package me.rerere.rikkahub.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.notifications.NotificationEntry
import me.rerere.rikkahub.data.notifications.NotificationListenerPreferences
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import org.koin.android.ext.android.inject

private const val TAG = "RikkaNListener"
// Generous ring so a busy morning (school bus app + Slack + WhatsApp + Instagram +
// dozen others piling notifications on while the user wakes up) does not silently roll
// over within ~10 minutes. The ring is held in memory only; entries cost ~200 bytes
// each so 500 entries is ~100KB — well below the per-process Java heap budget.
private const val RING_SIZE = 500
private const val MAX_TEXT_BYTES = 4096
// Cache window for [RikkaNotificationListenerService.listActive]'s mapped entries.
// Short enough that a freshly posted notification appears on the next user turn (loop
// guard's freshness TTL is 5 s) but long enough that a single LLM tool turn that calls
// list_active_notifications + dismiss_notification + list_active_notifications again
// only pays the binder IPC + per-entry packageManager.getApplicationLabel() once.
private const val ACTIVE_LIST_CACHE_TTL_MS = 1_000L

/**
 * Reads notifications from every app the user has granted us access to. Maintains an
 * in-memory ring buffer (100 entries) for LLM lookup AND auto-forwards whitelisted
 * packages' notifications to the default Telegram chat as a plain text summary.
 *
 * Bound by the OS once the user enables RikkaHub in Settings -> Notification access.
 * The companion singleton lets tool factories reach the live instance synchronously.
 */
class RikkaNotificationListenerService : NotificationListenerService() {

    private val whitelistPrefs: NotificationListenerPreferences by inject()
    private val telegramPrefs: TelegramBotPreferences by inject()
    private val telegramClient: TelegramBotClient by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _bound = MutableStateFlow(false)
    val bound = _bound.asStateFlow()

    private val _recent = MutableStateFlow<List<NotificationEntry>>(emptyList())
    val recent = _recent.asStateFlow()

    // Tracks the last (key -> "title|text" hash) we forwarded so progressive updates of
    // the same notification don't spam Telegram. Mutated from per-notification IO
    // coroutines (one launched per onNotificationPosted), so it must be concurrent-safe;
    // a plain HashMap here can ConcurrentModificationException on burst notifications.
    private val lastForwarded = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Tracks the very last (package + title + text) we forwarded across ALL keys. Persistent
    // notifications (Termux, music players, etc.) sometimes flap between identical content
    // under different StatusBarNotification keys; per-key dedup misses those, so this
    // global dedup catches "exact same payload as the previous forward" regardless of key.
    @Volatile private var lastForwardedGlobalSig: Int = 0
    @Volatile private var lastForwardedGlobalAtMs: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _bound.value = true
        Log.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        _bound.value = false
        lastForwarded.clear()
        Log.i(TAG, "listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        _bound.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val entry = sbn.toEntry(packageManager) ?: return
        if (shouldDrop(entry)) return

        // Active-list cache is now stale — force the next listActive() to re-fetch.
        invalidateActiveCache()

        // Append / replace by key (dedup updates of the same notification).
        appendToRing(entry)

        // Auto-route to Telegram if package is whitelisted and a default chat is set.
        scope.launch { tryForwardToTelegram(entry) }

        // Phase 12 — fan out to the workflow notification trigger dispatcher. The dispatcher
        // checks the matching workflows itself; with zero matching, this is a no-op.
        runCatching {
            me.rerere.rikkahub.workflow.trigger.NotificationTriggerDispatcher.onPosted(
                packageName = entry.packageName,
                title = entry.title,
                text = entry.text,
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Active-list cache no longer reflects reality — force re-fetch on the next read.
        // (Ring buffer is intentionally retained — it's "history" and removed
        // notifications stay visible to the LLM's history lookup.)
        invalidateActiveCache()
    }

    private fun appendToRing(entry: NotificationEntry) {
        val cur = _recent.value
        val withoutOld = cur.filterNot { it.key == entry.key }
        val next = (withoutOld + entry).takeLast(RING_SIZE)
        _recent.value = next
    }

    private suspend fun tryForwardToTelegram(entry: NotificationEntry) {
        val cfg = try { whitelistPrefs.current() } catch (t: Throwable) {
            Log.w(TAG, "whitelist prefs read failed; skipping forward", t); return
        }
        if (entry.packageName !in cfg.whitelist) return

        val tg = try { telegramPrefs.current() } catch (t: Throwable) {
            Log.w(TAG, "telegram prefs read failed; skipping forward", t); return
        }
        val chatId = tg.defaultChatId ?: return
        if (!tg.enabled) return

        // Drop forwards for *ongoing* (foreground-service) notifications from packages the
        // agent itself just kicked off — Termux's "X sessions" pill flaps every time we run
        // a command, and the user does not need a Telegram ping for each. Non-ongoing
        // notifications (e.g. a termux-notification the user explicitly asked the agent
        // to send) still go through.
        if (entry.ongoing &&
            me.rerere.rikkahub.data.ai.AgentTurnTracker.isFreshlyTouched(entry.packageName)) {
            return
        }

        val signature = (entry.title + "|" + entry.text).hashCode()
        if (lastForwarded[entry.key] == signature) return  // identical update; skip

        // Global dedup: if the previous forward (any key) had the exact same package + title
        // + text within the last 5 minutes, skip. Catches Termux's persistent notification
        // re-posting identical content under shifting keys.
        val globalSig = (entry.packageName + "|" + entry.title + "|" + entry.text).hashCode()
        val now = System.currentTimeMillis()
        if (globalSig == lastForwardedGlobalSig && (now - lastForwardedGlobalAtMs) < 5 * 60_000) {
            return
        }

        val body = buildString {
            append("🔔 [").append(entry.label).append("] ")
            if (entry.title.isNotBlank()) append(entry.title)
            if (entry.text.isNotBlank()) {
                if (entry.title.isNotBlank()) append('\n')
                append(entry.text)
            }
        }.take(3500)

        try {
            telegramClient.sendMessage(chatId, body)
            // Record dedup state ONLY AFTER successful send. Previously these were set
            // before sendMessage, so a transient network error meant the user never saw
            // that notification re-forwarded — the dedup map said "already done" forever.
            lastForwarded[entry.key] = signature
            lastForwardedGlobalSig = globalSig
            lastForwardedGlobalAtMs = now
        } catch (e: Throwable) {
            Log.w(TAG, "auto-route failed for ${entry.packageName}", e)
        }
    }

    /**
     * Best-effort dismiss by key. Returns true on dispatch (the OS does the rest), false
     * if the key is not in the currently active set.
     */
    fun dismissByKey(key: String): Boolean {
        val active = activeNotifications ?: return false
        if (active.none { it.key == key }) return false
        cancelNotification(key)
        return true
    }

    /**
     * Fire one of a notification's action PendingIntents. Lookup precedence:
     * 1. action_title (case-insensitive, exact match)
     * 2. action_index (0-based)
     */
    fun triggerAction(key: String, actionIndex: Int?, actionTitle: String?): TriggerResult {
        val sbn = activeNotifications?.firstOrNull { it.key == key }
            ?: return TriggerResult.NotFound
        val actions = sbn.notification.actions ?: emptyArray()
        if (actions.isEmpty()) return TriggerResult.NoAction
        val normalizedActionTitle = actionTitle?.trim()

        val matched: Notification.Action = when {
            !normalizedActionTitle.isNullOrBlank() ->
                actions.firstOrNull { matchesNotificationActionTitle(it.title?.toString(), normalizedActionTitle) }
            actionIndex != null -> actions.getOrNull(actionIndex)
            else -> null
        } ?: return TriggerResult.NoAction

        val remoteInputs = matched.remoteInputs
        if (remoteInputs != null && remoteInputs.isNotEmpty()) {
            return TriggerResult.RequiresInput(matched.title?.toString().orEmpty())
        }

        val pi = matched.actionIntent ?: return TriggerResult.NoAction
        return try {
            pi.send()
            TriggerResult.Success(matched.title?.toString().orEmpty())
        } catch (t: Throwable) {
            TriggerResult.SendFailed(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Fill a notification's reply action's RemoteInput with [text] and fire its
     * PendingIntent — the one-call equivalent of launch_app + set_text + click_node for
     * apps that expose a direct-reply action (WhatsApp, Messages, Telegram, etc.).
     * Picks the first action that carries at least one RemoteInput.
     */
    fun triggerReplyAction(key: String, text: String): TriggerResult {
        val sbn = activeNotifications?.firstOrNull { it.key == key }
            ?: return TriggerResult.NotFound
        val actions = sbn.notification.actions ?: emptyArray()
        if (actions.isEmpty()) return TriggerResult.NoAction

        val matched = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: return TriggerResult.NoAction
        val remoteInputs = matched.remoteInputs ?: return TriggerResult.NoAction
        val pi = matched.actionIntent ?: return TriggerResult.NoAction

        return try {
            val intent = android.content.Intent()
            val bundle = android.os.Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, text)
            }
            android.app.RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            pi.send(this, 0, intent)
            TriggerResult.Success(matched.title?.toString().orEmpty())
        } catch (t: Throwable) {
            TriggerResult.SendFailed(t.message ?: t::class.java.simpleName)
        }
    }

    fun listActive(): List<NotificationEntry> {
        // Short cache so back-to-back tool calls (the LLM often calls list_active +
        // dismiss_notification in the same turn) and rapid Compose recomps don't each
        // pay the binder IPC + the per-entry packageManager.getApplicationLabel().
        // 1 second is short enough that a fresh post is visible by the next user
        // turn (the loop guard's freshness TTL is 5s).
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = activeListCache
        if (cached != null && now - cached.cachedAtMs < ACTIVE_LIST_CACHE_TTL_MS) {
            return cached.entries
        }
        val active = activeNotifications ?: return emptyList()
        val entries = active.mapNotNull { it.toEntry(packageManager) }.filterNot { shouldDrop(it) }
        activeListCache = ActiveListCacheEntry(now, entries)
        return entries
    }

    /** Drop the cache so the next [listActive] call re-fetches via binder. Called from the
     *  posted/removed callbacks so a fresh notification is reflected immediately. */
    private fun invalidateActiveCache() {
        activeListCache = null
    }

    private data class ActiveListCacheEntry(val cachedAtMs: Long, val entries: List<NotificationEntry>)
    @Volatile private var activeListCache: ActiveListCacheEntry? = null

    sealed class TriggerResult {
        data class Success(val actionTitle: String) : TriggerResult()
        data object NotFound : TriggerResult()
        data object NoAction : TriggerResult()
        data class RequiresInput(val actionTitle: String) : TriggerResult()
        data class SendFailed(val reason: String) : TriggerResult()
    }

    private fun shouldDrop(entry: NotificationEntry): Boolean {
        if (entry.packageName == BuildConfig.APPLICATION_ID) return true
        if (entry.packageName == "com.android.systemui") return true
        if (entry.title.isEmpty() && entry.text.isEmpty()) return true
        return false
    }

    companion object {
        @Volatile
        var instance: RikkaNotificationListenerService? = null
            private set
    }
}

internal fun matchesNotificationActionTitle(actual: String?, requested: String?): Boolean =
    !requested.isNullOrBlank() &&
        actual?.trim()?.equals(requested.trim(), ignoreCase = true) == true

private fun StatusBarNotification.toEntry(pm: PackageManager): NotificationEntry? {
    val n = notification ?: return null
    val extras = n.extras ?: return null

    fun extraText(): String {
        val direct = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (direct.isNotEmpty()) return direct.take(MAX_TEXT_BYTES)
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (big.isNotEmpty()) return big.take(MAX_TEXT_BYTES)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.isNotEmpty()) {
            return lines.joinToString("\n") { it.toString() }.take(MAX_TEXT_BYTES)
        }
        return ""
    }

    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = extraText()
    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
    val label = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: Throwable) {
        // Uninstalled / restricted package — fall back to the raw package name.
        Log.d(TAG, "getApplicationLabel failed for $packageName", t)
        packageName
    }
    val actionTitles = n.actions?.mapNotNull { it.title?.toString() } ?: emptyList()
    val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
    return NotificationEntry(
        key = key,
        packageName = packageName,
        label = label,
        title = title,
        text = text,
        subText = subText,
        postTimeMs = postTime,
        actionTitles = actionTitles,
        ongoing = ongoing,
    )
}
