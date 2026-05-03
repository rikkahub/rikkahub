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
import kotlinx.serialization.json.longOrNull
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
        // Refresh the slash-command menu Telegram shows users every time the bot starts
        // (and any time the BUILT_IN_COMMANDS list changes between releases). Idempotent
        // and cheap; failures are logged but not fatal.
        scope.launch { registerBuiltInCommandsWithTelegram() }
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
        while (true) {
            val cfg = try { prefs.current() } catch (e: Throwable) {
                android.util.Log.e(TAG, "pollLoop: prefs.current() failed", e); null
            }
            if (cfg == null || !cfg.isUsable) {
                android.util.Log.w(TAG, "pollLoop: cfg unusable (token_set=${cfg?.token?.isNotBlank()} enabled=${cfg?.enabled}); stopping")
                stopSelf(); return
            }
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
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Throwable) {}
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
        // Built-in slash commands. These are handled entirely on-device — they never reach
        // the LLM, never spend tokens, and resolve in single-digit milliseconds.
        if (m.text.startsWith("/")) {
            if (handleBuiltInCommand(cfg, m)) return
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

        // Live streaming: send a placeholder reply, then edit it in place every ~1.5s with
        // the latest accumulated assistant text + tool-call summary so the user sees real-time
        // progress on Telegram (matching how the in-app chat already streams). The typing
        // indicator continues to show up between edits as a UX backstop.
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

        val typingJob = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                try { client.sendChatAction(m.chatId, "typing") } catch (_: Throwable) {}
                delay(4_000)
            }
        }
        val editJob = if (placeholderId != null) scope.launch {
            var lastSent = ""
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                delay(STREAM_EDIT_INTERVAL_MS)
                val rendered = renderAssistantStream(convId, finalizing = false)
                if (rendered.isBlank() || rendered == lastSent) continue
                // Edits are capped at ~4096 chars; truncate live edits, the final send will
                // chunk anything longer.
                val capped = if (rendered.length > MAX_CHARS) rendered.substring(0, MAX_CHARS) + "..." else rendered
                val ok = try {
                    client.editMessageText(m.chatId, placeholderId, capped) != null
                } catch (_: Throwable) { false }
                if (ok) lastSent = rendered
            }
        } else null

        try {
            chatService.getGenerationJobStateFlow(convId).first { it == null }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "handleIncoming: generation flow ended with error", e)
        } finally {
            typingJob.cancel()
            editJob?.cancel()
        }

        val finalReply = renderAssistantStream(convId, finalizing = true)
        android.util.Log.i(TAG, "handleIncoming: finalizing ${finalReply.length} chars to chat=${m.chatId}")

        when {
            finalReply.isBlank() -> {
                // Nothing to say. If we have a placeholder, replace it with a fallback note;
                // otherwise send a fresh message so the user is not left waiting silently.
                val fallback = "(no reply text - tool ran but produced no message)"
                if (placeholderId != null) {
                    try { client.editMessageText(m.chatId, placeholderId, fallback) } catch (_: Throwable) {}
                } else {
                    try { client.sendMessage(m.chatId, fallback) } catch (_: Throwable) {}
                }
            }
            placeholderId != null && finalReply.length <= MAX_CHARS -> {
                // Final fits in one message; just edit the placeholder one last time.
                try { client.editMessageText(m.chatId, placeholderId, finalReply) } catch (_: Throwable) {}
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
     */
    private suspend fun renderAssistantStream(convId: kotlin.uuid.Uuid, finalizing: Boolean): String {
        val conv = conversationRepo.getConversationById(convId) ?: return ""
        val lastAssistant = conv.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return ""
        val text = assistantTextOf(lastAssistant).trim()
        val toolSummary = assistantToolSummary(lastAssistant)
        val streamMarker = if (!finalizing && text.isNotEmpty()) " $STREAM_TICK" else ""
        return buildString {
            if (toolSummary.isNotEmpty()) {
                append(toolSummary)
                if (text.isNotEmpty()) append("\n\n")
            }
            if (text.isNotEmpty()) append(text)
            if (streamMarker.isNotEmpty()) append(streamMarker)
        }.trimEnd()
    }

    /**
     * Compact one-line "tool: name(args) -> summary" entries for every tool the assistant
     * ran in this turn. Skips internal-only tools whose details add noise (e.g. ask_user
     * which surfaces its own UI). Falls back to "(running)" for not-yet-completed calls so
     * the live edit shows progress.
     */
    private fun assistantToolSummary(m: UIMessage): String {
        val tools = m.parts.filterIsInstance<UIMessagePart.Tool>()
        if (tools.isEmpty()) return ""
        return buildString {
            append("🔧 Tools:\n")  // wrench emoji + Tools:
            for (t in tools) {
                append(" - ")
                append(t.toolName)
                val argSummary = t.input.take(60).replace("\n", " ").trim()
                if (argSummary.isNotEmpty()) append("(").append(argSummary).append(")")
                if (!t.isExecuted) {
                    append(" ... running")
                } else {
                    val outText = t.output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                        .take(80)
                        .replace("\n", " ")
                        .trim()
                    if (outText.isNotEmpty()) append(" -> ").append(outText)
                }
                append('\n')
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

        return when (cmd) {
            "/start" -> { sendStart(m.chatId); true }
            "/help", "/?" -> { sendHelp(m.chatId); true }
            "/new", "/reset", "/clear" -> { handleResetCommand(m.chatId); true }
            "/stop", "/cancel" -> { handleStopCommand(m.chatId); true }
            "/status" -> { handleStatusCommand(m.chatId); true }
            "/model" -> { handleModelCommand(m.chatId, arg); true }
            "/ratelimit" -> { handleRateLimitCommand(m.chatId, arg); true }
            else -> false
        }
    }

    private suspend fun sendStart(chatId: Long) {
        val msg = """
            👋 RikkaHub agent online.

            Send any message to talk to the model. Built-in commands:
            /help - list all commands
            /new - start a fresh conversation
            /stop - cancel the current generation
            /status - show current model + assistant
            /model - show or switch chat model
            /ratelimit - show or set the assistant's max output tokens
        """.trimIndent()
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    private suspend fun sendHelp(chatId: Long) {
        val msg = buildString {
            appendLine("Built-in commands (handled directly by the app, no LLM):")
            appendLine()
            BUILT_IN_COMMANDS.forEach { (c, d) ->
                appendLine("/${c.padEnd(11)} - $d")
            }
            appendLine()
            appendLine("Anything else is sent to the LLM as usual.")
        }
        try { client.sendMessage(chatId, msg.trim()) } catch (_: Throwable) {}
    }

    private suspend fun handleResetCommand(chatId: Long) {
        chatRepo.deleteByChatId(chatId)
        try { client.sendMessage(chatId, "✓ Conversation reset.") } catch (_: Throwable) {}
    }

    private suspend fun handleStopCommand(chatId: Long) {
        val mapping = chatRepo.getByChatId(chatId)
        if (mapping == null) {
            try { client.sendMessage(chatId, "Nothing to stop - no active conversation for this chat.") } catch (_: Throwable) {}
            return
        }
        val convId = try { Uuid.parse(mapping.conversationId) } catch (_: Throwable) {
            try { client.sendMessage(chatId, "Could not resolve conversation id.") } catch (_: Throwable) {}
            return
        }
        chatService.stopGeneration(convId)
        try { client.sendMessage(chatId, "⏹ Generation stopped.") } catch (_: Throwable) {}
    }

    private suspend fun handleStatusCommand(chatId: Long) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val model = s.providers.flatMap { it.models }.firstOrNull { it.id == effectiveModelId }
        val msg = buildString {
            appendLine("Bot status:")
            appendLine("- Service: ${if (isRunning) "running" else "stopped"}")
            appendLine("- Assistant: ${assistant.name.ifBlank { "(default)" }}")
            appendLine("- Model: ${model?.displayName ?: model?.modelId ?: "(none configured)"}")
            appendLine("- Max output tokens: ${assistant.maxTokens?.toString() ?: "(provider default)"}")
            appendLine("- Whitelist size: ${cfgSafe()?.whitelist?.size ?: 0}")
        }
        try { client.sendMessage(chatId, msg.trim()) } catch (_: Throwable) {}
    }

    private suspend fun cfgSafe(): me.rerere.rikkahub.data.telegram.TelegramBotConfig? = try {
        prefs.current()
    } catch (_: Throwable) { null }

    private suspend fun handleModelCommand(chatId: Long, arg: String) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val allModels = s.providers
            .filter { it.enabled }
            .flatMap { p -> p.models.map { p to it } }
            .filter { (_, m) -> m.type == me.rerere.ai.provider.ModelType.CHAT }

        if (arg.isBlank()) {
            // No arg - show current + list available
            val effectiveModelId = assistant.chatModelId ?: s.chatModelId
            val current = allModels.firstOrNull { (_, m) -> m.id == effectiveModelId }
            val msg = buildString {
                appendLine("Current model: ${current?.second?.displayName ?: current?.second?.modelId ?: "(unset)"}")
                if (current != null) appendLine("Provider: ${current.first.name}")
                appendLine()
                if (allModels.isEmpty()) {
                    appendLine("No chat models configured. Add a provider in the app settings first.")
                } else {
                    appendLine("Available chat models (use /model <name> to switch):")
                    allModels.take(50).forEach { (p, m) ->
                        appendLine("- ${m.displayName.ifBlank { m.modelId }}  [${p.name}]")
                    }
                    if (allModels.size > 50) appendLine("... and ${allModels.size - 50} more")
                }
            }
            try { client.sendMessage(chatId, msg.trim()) } catch (_: Throwable) {}
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
                client.sendMessage(chatId, "No chat model matches \"$arg\". Send /model with no argument to see the list.")
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
            client.sendMessage(
                chatId,
                "✓ Switched assistant \"${assistant.name.ifBlank { "default" }}\" to model \"${model.displayName.ifBlank { model.modelId }}\" [${provider.name}]."
            )
        } catch (_: Throwable) {}
    }

    private suspend fun handleRateLimitCommand(chatId: Long, arg: String) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        if (arg.isBlank()) {
            val current = assistant.maxTokens?.toString() ?: "(provider default - unlimited within model context)"
            try {
                client.sendMessage(
                    chatId,
                    "Max output tokens for assistant \"${assistant.name.ifBlank { "default" }}\": $current\nUse /ratelimit <number> to set, or /ratelimit clear to remove the cap."
                )
            } catch (_: Throwable) {}
            return
        }
        val newCap: Int? = when {
            arg.equals("clear", ignoreCase = true) || arg.equals("none", ignoreCase = true) ||
                arg.equals("off", ignoreCase = true) || arg.equals("0", ignoreCase = true) -> null
            else -> arg.toIntOrNull()?.takeIf { it in 1..200_000 }
        }
        if (arg.toIntOrNull() != null && newCap == null) {
            try { client.sendMessage(chatId, "Value out of range. Use 1..200000, or 'clear' to remove the cap.") } catch (_: Throwable) {}
            return
        }
        if (arg.toIntOrNull() == null && newCap != null) {
            // Defensive — should not happen given the when above.
            try { client.sendMessage(chatId, "Could not parse \"$arg\". Use a number or 'clear'.") } catch (_: Throwable) {}
            return
        }
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistant.id) it.copy(maxTokens = newCap) else it
                }
            )
        }
        val msg = if (newCap == null) "✓ Removed max-token cap on assistant \"${assistant.name.ifBlank { "default" }}\"."
        else "✓ Max output tokens for assistant \"${assistant.name.ifBlank { "default" }}\" set to $newCap."
        try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
    }

    /**
     * Push the canonical built-in command list to Telegram so the user sees them in the
     * autocomplete menu when typing "/". Called once on bot service start. Failures are
     * non-fatal.
     */
    private suspend fun registerBuiltInCommandsWithTelegram() {
        try {
            val list = BUILT_IN_COMMANDS.map { (c, d) -> c to d }
            val ok = client.setMyCommands(list)
            android.util.Log.i(TAG, "registerBuiltInCommandsWithTelegram: setMyCommands ok=$ok")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "registerBuiltInCommandsWithTelegram failed", e)
        }
    }

    companion object {
        const val TAG = "TelegramBotService"
        const val CHANNEL_ID = "rikkahub_telegram_bot"
        const val NOTIF_ID = 0xA1B2

        const val MAX_CHARS = 4000   // Telegram limit is 4096; leave headroom

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

        /** Telegram silently rate-limits edits around 1/sec; 1500ms gives steady progress
         *  without tripping the limiter. */
        const val STREAM_EDIT_INTERVAL_MS: Long = 1_500L

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
