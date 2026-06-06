package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.telegram.TelegramCallbackQuery
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import me.rerere.rikkahub.data.telegram.TelegramIncomingMessage
import me.rerere.rikkahub.service.TelegramBotService.Companion.ApprovalPromptRegistry
import me.rerere.rikkahub.service.TelegramBotService.Companion.BUILT_IN_COMMANDS
import me.rerere.rikkahub.service.TelegramBotService.Companion.DOCTOR_FIX_CB_PREFIX
import me.rerere.rikkahub.service.TelegramBotService.Companion.MAX_CHARS
import me.rerere.rikkahub.service.TelegramBotService.Companion.PARSE_MODE_HTML
import me.rerere.rikkahub.service.TelegramBotService.Companion.SlashCommandLog
import me.rerere.rikkahub.service.TelegramBotService.Companion.TAG
import me.rerere.rikkahub.service.TelegramBotService.Companion.isRunning
import kotlin.uuid.Uuid

/**
 * Built-in slash-command handlers + the per-turn helpers (auto-cancel, image rescue,
 * approval-keyboard cleanup, etc.) that pair with them. Implemented as extension functions
 * on TelegramBotService so they can read the service's DI-injected dependencies and
 * shared state (chatMutexes, turnJobs, ApprovalPromptRegistry, ...) without a wrapper
 * class — TelegramBotService stays the one orchestrator, and these extensions just live
 * in a separate physical file to keep that orchestrator focused on lifecycle + the
 * poll-loop / LLM-turn pipeline.
 *
 * Companion-object members of TelegramBotService (TAG, BUILT_IN_COMMANDS, MAX_CHARS,
 * PARSE_MODE_HTML, SlashCommandLog, ApprovalPromptRegistry, isRunning,
 * DOCTOR_FIX_CB_PREFIX) are accessed via `TelegramBotService.X` — slightly noisier than
 * the bare references the in-class versions used, but explicit and correct from outside.
 */

/**
 * Dispatch a built-in slash command. Returns true when the message was handled by the
 * app (no LLM round-trip), false if the command is unknown and should fall through to
 * the LLM. Built-in commands NEVER spend tokens.
 */
internal suspend fun TelegramBotService.handleBuiltInCommand(
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

internal suspend fun TelegramBotService.sendStart(chatId: Long) {
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

internal suspend fun TelegramBotService.sendHelp(chatId: Long) {
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
internal suspend fun TelegramBotService.cancelStaleApprovalKeyboards(chatId: Long, reason: String) {
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
            Log.w(TAG, "cancelStaleApprovalKeyboards: edit failed for $toolCallId", e)
        }
    }
    ApprovalPromptRegistry.clearChat(chatId)
}

internal suspend fun TelegramBotService.handleResetCommand(chatId: Long) {
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
    // Edit dead approval keyboards in place so the user knows tapping them won't
    // do anything. Then drop the registry entries.
    cancelStaleApprovalKeyboards(chatId, reason = "/new")
    // Drop any unanswered ask_user clarify so the user's next message starts fresh instead
    // of being swallowed as the answer to a question from the discarded conversation.
    clearClarifyForChat(chatId)
    chatRepo.deleteByChatId(chatId)
    val (modelName, _) = activeModelDisplay()
    val msg = """
        🆕 Fresh conversation started.

        I'm running $modelName. What's up?
    """.trimIndent()
    try { client.sendMessage(chatId, msg) } catch (_: Throwable) {}
}

internal suspend fun TelegramBotService.handleStopCommand(chatId: Long) {
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
    // Drop any unanswered ask_user clarify so the user's next message starts a new turn
    // instead of being consumed as the answer to the stopped turn's question.
    clearClarifyForChat(chatId)
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

internal suspend fun TelegramBotService.handleStatusCommand(chatId: Long) {
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
internal fun TelegramBotService.activeModelDisplay(): Pair<String, String> {
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

internal suspend fun TelegramBotService.cfgSafe(): me.rerere.rikkahub.data.telegram.TelegramBotConfig? = try {
    prefs.current()
} catch (_: Throwable) { null }

internal suspend fun TelegramBotService.handleModelCommand(chatId: Long, arg: String) {
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

internal suspend fun TelegramBotService.handleRateLimitCommand(chatId: Long, arg: String) {
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
internal fun TelegramBotService.recentFailedRunsOf(
    convId: Uuid,
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
internal suspend fun TelegramBotService.hasInFlightApprovedTool(chatId: Long): Boolean {
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
internal suspend fun TelegramBotService.autoCancelStuckTurn(chatId: Long) {
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
internal suspend fun TelegramBotService.tryRescueImageFromTurn(
    convId: Uuid,
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
            Log.i(TAG, "tryRescueImageFromTurn: sent ${tool.toolName} artifact to chat=$chatId path=$path")
            true
        }.getOrElse {
            Log.w(TAG, "tryRescueImageFromTurn: sendPhoto failed", it)
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
internal suspend fun TelegramBotService.handleDoctorCommand(chatId: Long) {
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
        val html = "<pre>${TelegramHtmlRenderer.escape(c)}</pre>"
        runCatching {
            client.sendMessage(chatId, html, parseMode = PARSE_MODE_HTML)
        }.onFailure {
            // Fallback to plain text if HTML send fails for any reason.
            runCatching { client.sendMessage(chatId, c) }
        }
    }
    // Surface AutoFix actions as inline-keyboard buttons. Without this, the /doctor
    // Telegram surface tells the user what's broken but not that an in-app one-tap
    // remedy exists — they had to know to open Settings → Doctor to find the button.
    // The handler re-runs the checks at tap time, looks up by check.id, and executes
    // FixAction.AutoFix in-process.
    // Bot API caps callback_data at 64 BYTES. Drop any AutoFix whose `dfix:<id>` payload
    // would overflow — better to surface no button than ship a keyboard Telegram silently
    // rejects. Author-controlled (DoctorChecks ids) so this is future-proofing; today's
    // ids leave ~33 bytes of headroom.
    val fixable = results.filter { c ->
        val matchesSeverity = c.severity == me.rerere.rikkahub.ui.pages.setting.doctor.Severity.FAIL ||
            c.severity == me.rerere.rikkahub.ui.pages.setting.doctor.Severity.WARN
        val hasAutoFix = c.fix is me.rerere.rikkahub.ui.pages.setting.doctor.FixAction.AutoFix
        if (!matchesSeverity || !hasAutoFix) return@filter false
        val payloadBytes = (DOCTOR_FIX_CB_PREFIX + c.id).toByteArray(Charsets.UTF_8).size
        if (payloadBytes > 64) {
            android.util.Log.w(
                TAG,
                "handleDoctorCommand: skipping AutoFix button for check.id=${c.id} " +
                    "($payloadBytes bytes > 64 cap)",
            )
            false
        } else true
    }
    if (fixable.isNotEmpty()) {
        val markup = buildJsonObject {
            put("inline_keyboard", buildJsonArray {
                fixable.forEach { c ->
                    val af = c.fix as me.rerere.rikkahub.ui.pages.setting.doctor.FixAction.AutoFix
                    add(buildJsonArray {
                        addJsonObject {
                            put("text", "🔧 ${af.label}")
                            put("callback_data", "${DOCTOR_FIX_CB_PREFIX}${c.id}")
                        }
                    })
                }
            })
        }
        val body = "🩺 ${fixable.size} issue${if (fixable.size == 1) "" else "s"} with an in-app fix — tap to run:"
        runCatching {
            client.sendMessage(chatId, body, replyMarkup = markup)
        }
    }
}

/**
 * Handle a `dfix:<check_id>` callback. Re-runs the full DoctorChecks suite at tap-time
 * (rather than caching the FixAction.run lambda from the /doctor invocation, which
 * would leak across restarts and grow unbounded), locates the matching check by id,
 * and executes its FixAction.AutoFix.
 *
 * The button message is edited in place: "Running…" → result. If the underlying check
 * no longer surfaces an AutoFix (the corruption auto-cleared, the user already fixed
 * it from the app, etc.), the message is replaced with a "no longer applicable" note.
 */
internal suspend fun TelegramBotService.handleDoctorFixCallback(cq: TelegramCallbackQuery) {
    val checkId = cq.data.removePrefix(DOCTOR_FIX_CB_PREFIX).trim()
    if (checkId.isEmpty()) {
        client.answerCallbackQuery(cq.callbackQueryId, "malformed fix callback")
        return
    }
    // Ack fast so Telegram's button spinner clears. The actual fix may take seconds.
    client.answerCallbackQuery(cq.callbackQueryId, "Running…")
    val msgId = cq.messageId
    runCatching { client.editMessageText(cq.chatId, msgId, "🔧 Running fix for $checkId…") }
    val results = runCatching { doctorChecks.runAll() }.getOrElse {
        val msg = "🩺 Doctor re-run failed: ${it::class.simpleName}: ${it.message ?: "(no message)"}"
        runCatching { client.editMessageText(cq.chatId, msgId, msg) }
        return
    }
    val match = results.firstOrNull { it.id == checkId }
    val af = match?.fix as? me.rerere.rikkahub.ui.pages.setting.doctor.FixAction.AutoFix
    if (af == null) {
        val msg = "✅ ${match?.label ?: checkId}: no longer needs a fix."
        runCatching { client.editMessageText(cq.chatId, msgId, msg) }
        return
    }
    val outcome = runCatching { af.run() }
    val body = outcome.fold(
        onSuccess = { r ->
            val icon = if (r.ok) "✅" else "❌"
            "$icon ${match.label}: ${r.message}"
        },
        onFailure = { t ->
            "❌ ${match.label}: ${t::class.simpleName}: ${t.message ?: "(no message)"}"
        },
    )
    runCatching { client.editMessageText(cq.chatId, msgId, body) }
}

/**
 * `/stream` — show or toggle whether tool screenshots auto-stream to this chat.
 * No arg = show + toggle. Arg `on` / `off` = set explicitly. Stored globally on the
 * bot config (not per-chat) since users with one Telegram account → one bot expect
 * one knob; both streamers read the same flag.
 */
internal suspend fun TelegramBotService.handleStreamCommand(chatId: Long, arg: String) {
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
internal suspend fun TelegramBotService.registerBuiltInCommandsWithTelegram() {
    try {
        val custom = try { prefs.current().customCommands } catch (_: Throwable) { emptyList() }
        val merged = BUILT_IN_COMMANDS + custom
        val ok = client.setMyCommands(merged)
        Log.i(
            TAG,
            "registerBuiltInCommandsWithTelegram: setMyCommands ok=$ok " +
                "(builtins=${BUILT_IN_COMMANDS.size}, custom=${custom.size})"
        )
    } catch (e: Throwable) {
        Log.w(TAG, "registerBuiltInCommandsWithTelegram failed", e)
    }
}
