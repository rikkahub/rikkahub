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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.TelegramChatEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.TelegramChatRepository
import me.rerere.rikkahub.data.telegram.TelegramApiException
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.data.telegram.TelegramIncomingMessage
import me.rerere.rikkahub.data.telegram.parseIncoming
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

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
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram bot listening")
            .setContentText("Routing inbound messages to RikkaHub")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** Long-poll Telegram, dispatch messages, advance offset. */
    private suspend fun pollLoop() {
        android.util.Log.i(TAG, "pollLoop: starting")
        var offset = 0L
        var cycle = 0L
        while (true) {
            val cfg = try { prefs.current() } catch (e: Throwable) {
                android.util.Log.e(TAG, "pollLoop: prefs.current() failed", e); null
            }
            if (cfg == null || !cfg.isUsable) {
                android.util.Log.w(TAG, "pollLoop: cfg unusable (token_set=${cfg?.token?.isNotBlank()} enabled=${cfg?.enabled}); stopping")
                stopSelf(); return
            }
            try {
                cycle++
                val updates = client.getUpdates(offset, 30)
                if (cycle <= 2 || updates.isNotEmpty()) {
                    android.util.Log.i(TAG, "pollLoop: cycle=$cycle offset=$offset updates=${updates.size}")
                }
                for (u in updates.map { it as kotlinx.serialization.json.JsonObject }) {
                    val incoming = parseIncoming(u) ?: continue
                    android.util.Log.i(TAG, "pollLoop: dispatching message ${incoming.messageId} from chat=${incoming.chatId} sender=${incoming.senderId}")
                    offset = incoming.updateId + 1
                    handleIncoming(cfg, incoming)
                }
            } catch (e: TelegramApiException) {
                android.util.Log.e(TAG, "pollLoop: telegram api error ${e.errorCode}: ${e.description}", e)
                delay(5000)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "pollLoop: unexpected error in cycle=$cycle", e)
                delay(5000)
            }
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
        if (cfg.whitelist.isNotEmpty() && sender !in cfg.whitelist && m.chatId !in cfg.whitelist) {
            android.util.Log.w(TAG, "handleIncoming: dropping — sender=$sender chat=${m.chatId} not in whitelist=${cfg.whitelist}")
            return
        }
        // Built-in /reset and /new — clear the chat→conversation mapping so the next message
        // starts a fresh thread. The LLM never sees these.
        if (m.text.trim() in setOf("/reset", "/new")) {
            chatRepo.deleteByChatId(m.chatId)
            try { client.sendMessage(m.chatId, "✓ Conversation reset") } catch (_: Throwable) {}
            return
        }

        val (convId, wasCreated) = lookupOrCreateConversation(cfg, m.chatId)
        android.util.Log.i(TAG, "handleIncoming: routing to conv=$convId wasCreated=$wasCreated text='${m.text.take(80)}' photos=${m.photoFileIds.size}")
        // UX: tell Telegram "the bot is typing" so the user sees activity while we generate.
        try { client.sendChatAction(m.chatId, "typing") } catch (_: Throwable) {}
        chatService.initializeConversation(convId)
        // First message of a new Telegram chat gets a context preamble so the LLM knows
        // (a) that this conversation originates on Telegram, and (b) the chat_id to route
        // any scheduled jobs / notifications it creates on the user's behalf. Subsequent
        // messages don't repeat it — the LLM has it in conversation history.
        val text = if (wasCreated) {
            "[telegram_context: This conversation originates on Telegram. The user's chat_id is ${m.chatId}. " +
            "For ANY scheduled jobs, recurring tasks, notifications, or proactive messages you create on the user's behalf, " +
            "use telegram_send_message with chat_id=${m.chatId} so the result is delivered to this Telegram chat. " +
            "When the user says things like 'every day at 9am tell me the weather' or 'remind me later', schedule a cron job " +
            "whose prompt explicitly instructs you to call telegram_send_message(chat_id=${m.chatId}, text=...) with the result.]\n\n" +
            m.text
        } else {
            m.text
        }
        // Download any inbound photos to the app cache and attach as UIMessagePart.Image so
        // the assistant's vision pipeline can see them (FileEncoder reads file:// only).
        val imageParts = downloadInboundPhotos(m.photoFileIds)
        val parts = buildList<UIMessagePart> {
            addAll(imageParts)
            // Only emit a Text part when there is actual content; an empty text triggers
            // the "no reply" UX downstream and confuses the LLM.
            if (text.isNotEmpty()) add(UIMessagePart.Text(text))
            else if (imageParts.isNotEmpty()) add(UIMessagePart.Text("(photo)"))
        }
        chatService.sendMessage(convId, parts)

        // Wait for the generation Job to become null (pipeline finished). Re-tickle the
        // Telegram typing indicator every ~4 seconds while we wait so it stays visible.
        val typingJob = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                try { client.sendChatAction(m.chatId, "typing") } catch (_: Throwable) {}
                delay(4_000)
            }
        }
        try {
            chatService.getGenerationJobStateFlow(convId).first { it == null }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleIncoming: generation flow ended with error", e)
        } finally {
            typingJob.cancel()
        }

        val reply = readLatestAssistantText(convId)
        android.util.Log.i(TAG, "handleIncoming: replying ${reply.length} chars to chat=${m.chatId}")
        if (reply.isNotBlank()) {
            sendChunked(m.chatId, reply, replyTo = m.messageId)
        } else {
            // Don't leave the user staring at nothing — tell them generation produced no text
            // (most often the LLM only made tool calls and didn't add a final user-facing text).
            try {
                client.sendMessage(m.chatId, "(no reply text — tool ran but produced no message)")
            } catch (_: Throwable) {}
        }
        chatRepo.touch(m.chatId, System.currentTimeMillis())
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

    /**
     * Resolve each Telegram photo file_id to a downloaded file in the app cache, then return
     * UIMessagePart.Image entries pointing at file:// URIs. Failures on individual photos are
     * logged and skipped (so a transient network blip on one image does not drop the whole
     * message).
     */
    private suspend fun downloadInboundPhotos(fileIds: List<String>): List<UIMessagePart.Image> {
        if (fileIds.isEmpty()) return emptyList()
        val dir = java.io.File(cacheDir, "telegram-incoming").apply { mkdirs() }
        // Prune anything older than 24h to keep cache bounded.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }

        val out = mutableListOf<UIMessagePart.Image>()
        for (fileId in fileIds) {
            try {
                val info = client.getFile(fileId)
                val filePath = info["file_path"]?.jsonPrimitive?.contentOrNull
                if (filePath == null) {
                    android.util.Log.w(TAG, "downloadInboundPhotos: getFile returned no file_path for id=$fileId")
                    continue
                }
                val ext = filePath.substringAfterLast('.', "jpg")
                val dest = java.io.File(dir, "tg-${System.currentTimeMillis()}-${fileId.takeLast(8)}.$ext")
                client.downloadFile(filePath, dest)
                out.add(UIMessagePart.Image(url = "file://${dest.absolutePath}"))
                android.util.Log.i(TAG, "downloadInboundPhotos: saved ${dest.name} (${dest.length()} bytes)")
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "downloadInboundPhotos: failed for $fileId", e)
            }
        }
        return out
    }

    /** Telegram caps a single sendMessage at 4096 chars; split on newlines where possible. */
    private suspend fun sendChunked(chatId: Long, text: String, replyTo: Long?) {
        val chunks = chunk(text, MAX_CHARS)
        for ((idx, chunk) in chunks.withIndex()) {
            try {
                client.sendMessage(
                    chatId = chatId,
                    text = chunk,
                    parseMode = null,   // markdown is finicky on Telegram; send plain
                    replyToMessageId = if (idx == 0) replyTo else null,
                )
            } catch (_: Throwable) { /* best effort */ }
        }
    }

    private fun chunk(s: String, n: Int): List<String> {
        if (s.length <= n) return listOf(s)
        val out = mutableListOf<String>()
        var rem = s
        while (rem.length > n) {
            val cut = rem.lastIndexOf('\n', n).let { if (it > n / 2) it else n }
            out.add(rem.substring(0, cut))
            rem = rem.substring(cut).trimStart('\n')
        }
        if (rem.isNotEmpty()) out.add(rem)
        return out
    }

    companion object {
        const val TAG = "TelegramBotService"
        const val CHANNEL_ID = "rikkahub_telegram_bot"
        const val NOTIF_ID = 0xA1B2

        const val MAX_CHARS = 4000   // Telegram limit is 4096; leave headroom

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
