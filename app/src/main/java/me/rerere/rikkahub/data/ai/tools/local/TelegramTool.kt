package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.telegram.TelegramApiException
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import java.io.File

private fun textPart(o: JsonObject) = listOf(UIMessagePart.Text(o.toString()))

private inline fun safeApi(block: () -> JsonObject): JsonObject = try {
    block()
} catch (e: TelegramApiException) {
    buildJsonObject {
        put("error", "telegram api ${e.errorCode}: ${e.description}")
    }
} catch (e: Throwable) {
    buildJsonObject { put("error", e.message ?: e::class.simpleName ?: "unknown") }
}

/**
 * Classify the failure of a token-verification getMe() call into a structured
 * `{error, detail, recovery}` envelope. The distinction matters because the LLM
 * retry policy differs:
 *
 *  - HTTP 401 from the Telegram API => the token is permanently invalid. Retrying
 *    the SAME token will always fail; the model must go get a fresh token from
 *    @BotFather. error = "token_invalid".
 *  - Anything else (network failure, timeout, DNS, non-401 HTTP error) => transient
 *    or unknown. Retrying is reasonable. error = "network_error".
 *
 * Pure function: takes the thrown exception, returns the envelope. Unit-tested.
 */
internal fun classifyTokenVerifyError(t: Throwable): JsonObject {
    val isInvalidToken = t is TelegramApiException && t.errorCode == 401
    return if (isInvalidToken) {
        buildJsonObject {
            put("error", "token_invalid")
            put("detail", "Telegram API rejected the token (HTTP 401: ${(t as TelegramApiException).description}).")
            put(
                "recovery",
                "The token is permanently invalid — do NOT retry with the same value. " +
                    "Get a fresh token from @BotFather and call telegram_set_token again with it."
            )
        }
    } else {
        val detail = when (t) {
            is TelegramApiException -> "Telegram API error ${t.errorCode}: ${t.description}"
            else -> t.message ?: t::class.simpleName ?: "unknown network failure"
        }
        buildJsonObject {
            put("error", "network_error")
            put("detail", detail)
            put(
                "recovery",
                "Verification failed for a transient reason (network / timeout / non-401 server error). " +
                    "The token may still be valid — retry telegram_set_token, optionally after a short delay."
            )
        }
    }
}

/** Set/replace the bot token. Triggers a getMe to verify before persisting. */
fun telegramSetTokenTool(prefs: TelegramBotPreferences, client: TelegramBotClient): Tool = Tool(
    name = "telegram_set_token",
    description = "Save the Telegram bot token (the long string from BotFather, e.g. 1234567890:ABC...). Verifies via getMe before persisting; on success returns bot info.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("token", buildJsonObject { put("type", "string"); put("description", "Bot token from @BotFather") })
            },
            required = listOf("token")
        )
    },
    execute = { input ->
        val token = input.jsonObject["token"]?.jsonPrimitive?.contentOrNull
            ?: error("token is required")
        // Provisionally persist; the client uses tokenProvider() lazily so getMe will use it.
        prefs.update { it.copy(token = token) }
        // Verify the token. A 401 from Telegram means the token is permanently invalid
        // (don't retry); a network/timeout/non-401 failure is transient (retry OK). The
        // classifier produces distinct {error, detail, recovery} envelopes so the model
        // doesn't burn turns retrying a dead token.
        val payload = try {
            val me = client.getMe()
            buildJsonObject {
                put("success", true)
                put("bot", me)
            }
        } catch (t: Throwable) {
            classifyTokenVerifyError(t)
        }
        // If verification failed, roll back the token so we don't leave a bad value behind.
        if (payload["error"] != null) {
            prefs.update { it.copy(token = "") }
        }
        textPart(payload)
    }
)

/** Return current bot config + bot info if token is set + whether the long-poll service is alive. */
fun telegramStatusTool(
    context: android.content.Context,
    prefs: TelegramBotPreferences,
    client: TelegramBotClient,
): Tool = Tool(
    name = "telegram_status",
    description = "Get current Telegram bot configuration AND whether the long-poll service is actually running. The 'enabled' flag means the user wants the bot on; 'service_running' means it's actively listening for messages right now. If enabled=true but service_running=false, the OS killed the service — call telegram_enable to bring it back.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val cfg = prefs.current()
        val botInfo = if (cfg.token.isNotBlank()) {
            try { client.getMe() } catch (e: Throwable) {
                buildJsonObject { put("error", "getMe failed: ${e.message ?: "unknown"}") }
            }
        } else null
        // Best-effort runtime check: the service writes to its companion isRunning flag while
        // the poll loop is alive. If the process was killed the flag's also cleared (it's
        // process-local), so this naturally reflects "service alive in *this* process".
        val serviceRunning = me.rerere.rikkahub.service.TelegramBotService.isRunning
        textPart(buildJsonObject {
            put("token_set", cfg.token.isNotBlank())
            put("enabled", cfg.enabled)
            put("service_running", serviceRunning)
            if (cfg.enabled && !serviceRunning) {
                put("hint", "User wants the bot on but the service is down. Call telegram_enable to re-start it.")
            }
            cfg.defaultChatId?.let { put("default_chat_id", it) }
            put("whitelist", buildJsonArray { cfg.whitelist.sorted().forEach { add(it) } })
            cfg.assistantId?.let { put("assistant_id", it) }
            if (botInfo != null) put("bot", botInfo)
        })
    }
)

/** Start the foreground long-poll service. Requires a token to already be set. */
fun telegramEnableTool(context: Context, prefs: TelegramBotPreferences): Tool = Tool(
    name = "telegram_enable",
    description = "Enable the Telegram bot — starts a foreground service that long-polls for incoming messages. Requires telegram_set_token to have been called first.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val cfg = prefs.current()
        if (cfg.token.isBlank()) {
            return@Tool textPart(buildJsonObject { put("error", "no token set — call telegram_set_token first") })
        }
        prefs.update { it.copy(enabled = true) }
        TelegramBotService.start(context)
        textPart(buildJsonObject { put("success", true) })
    }
)

/** Stop the long-poll service and clear the enabled flag. */
fun telegramDisableTool(context: Context, prefs: TelegramBotPreferences): Tool = Tool(
    name = "telegram_disable",
    description = "Stop the Telegram bot service and clear the enabled flag. Token, whitelist, and other config are preserved.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        prefs.update { it.copy(enabled = false) }
        TelegramBotService.stop(context)
        textPart(buildJsonObject { put("success", true) })
    }
)

/** Add a Telegram user/chat id to the whitelist. */
fun telegramAddWhitelistTool(prefs: TelegramBotPreferences): Tool = Tool(
    name = "telegram_add_whitelist",
    description = "Add a Telegram user id (or chat id) to the bot's whitelist of allowed senders. Without a non-empty whitelist the bot ignores all incoming messages.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "integer"); put("description", "Telegram user id or chat id to allow") })
            },
            required = listOf("id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.longOrNull
            ?: error("id is required")
        prefs.update { it.copy(whitelist = it.whitelist + id) }
        textPart(buildJsonObject { put("success", true); put("whitelist", buildJsonArray { prefs.current().whitelist.sorted().forEach { add(it) } }) })
    }
)

/** Remove an id from the whitelist. */
fun telegramRemoveWhitelistTool(prefs: TelegramBotPreferences): Tool = Tool(
    name = "telegram_remove_whitelist",
    description = "Remove a Telegram user id (or chat id) from the bot's whitelist.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "integer"); put("description", "Telegram user id or chat id to remove") })
            },
            required = listOf("id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.longOrNull
            ?: error("id is required")
        prefs.update { it.copy(whitelist = it.whitelist - id) }
        textPart(buildJsonObject { put("success", true); put("whitelist", buildJsonArray { prefs.current().whitelist.sorted().forEach { add(it) } }) })
    }
)

/** Set the default chat id used by telegram_send_message when chat_id is omitted. */
fun telegramSetDefaultChatTool(prefs: TelegramBotPreferences): Tool = Tool(
    name = "telegram_set_default_chat",
    description = "Set the default chat id for outbound messages. telegram_send_message and friends use this when no chat_id is provided. Useful for cron jobs that should DM you the result.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("chat_id", buildJsonObject { put("type", "integer"); put("description", "Telegram chat id. Pass 0 to clear.") })
            },
            required = listOf("chat_id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["chat_id"]?.jsonPrimitive?.longOrNull
            ?: error("chat_id is required")
        prefs.update { it.copy(defaultChatId = if (id == 0L) null else id) }
        textPart(buildJsonObject { put("success", true); put("default_chat_id", prefs.current().defaultChatId ?: 0L) })
    }
)

/** Bind a specific assistant to handle inbound Telegram messages. */
fun telegramSetAssistantTool(prefs: TelegramBotPreferences): Tool = Tool(
    name = "telegram_set_assistant",
    description = "Bind a specific assistant (by UUID) to handle inbound Telegram messages. If unset, the bot uses the user's currently-selected assistant in the app.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("assistant_id", buildJsonObject { put("type", "string"); put("description", "Assistant UUID. Empty string to clear.") })
            },
            required = listOf("assistant_id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["assistant_id"]?.jsonPrimitive?.contentOrNull
            ?: error("assistant_id is required")
        prefs.update { it.copy(assistantId = id.ifBlank { null }) }
        textPart(buildJsonObject { put("success", true); put("assistant_id", prefs.current().assistantId ?: "") })
    }
)

/** Send a text message via the bot. */
fun telegramSendMessageTool(prefs: TelegramBotPreferences, client: TelegramBotClient): Tool = Tool(
    name = "telegram_send_message",
    description = "Send a text message via the bot. If chat_id is omitted, uses the default chat. Markdown parse_mode = 'MarkdownV2' or 'HTML' if you want formatting.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("description", "Message text (max ~4000 chars)") })
                put("chat_id", buildJsonObject { put("type", "integer"); put("description", "Optional — defaults to telegram_set_default_chat value") })
                put("parse_mode", buildJsonObject { put("type", "string"); put("description", "Optional — 'MarkdownV2' or 'HTML'") })
            },
            required = listOf("text")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val text = p["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
        val chatId = p["chat_id"]?.jsonPrimitive?.longOrNull ?: prefs.current().defaultChatId
            ?: return@Tool textPart(buildJsonObject { put("error", "no chat_id and no default_chat_id set") })
        val parseMode = p["parse_mode"]?.jsonPrimitive?.contentOrNull
        textPart(safeApi { buildJsonObject {
            put("success", true)
            put("result", client.sendMessage(chatId, text, parseMode))
        } })
    }
)

/**
 * Telegram Bot API hard caps for media uploads (https://core.telegram.org/bots/api#sending-files).
 * Photos via sendPhoto: 10 MB. Anything larger via sendPhoto returns 413
 * Request Entity Too Large after the upload completes — wasting bandwidth +
 * battery. Documents via sendDocument: 50 MB.
 *
 * The 2 GB cap that some Telegram clients show is for the LOCAL Bot API
 * server (self-hosted), not the public api.telegram.org endpoint we hit.
 */
private const val TG_BOT_PHOTO_CAP_BYTES = 10L * 1024L * 1024L
private const val TG_BOT_DOC_CAP_BYTES = 50L * 1024L * 1024L

private fun fileTooLargeEnvelope(
    file: File,
    capBytes: Long,
    kind: String,
): JsonObject = buildJsonObject {
    put("error", "file_too_large_for_telegram_bot")
    put("kind", kind)
    put("path", file.absolutePath)
    put("size_bytes", file.length())
    put("cap_bytes", capBytes)
    put(
        "recovery",
        "Telegram Bot API caps $kind uploads at ${capBytes / (1024 * 1024)} MB. The public " +
            "api.telegram.org endpoint enforces this; uploading anyway returns 413. Options: " +
            "(a) split with `split -b 45m file part-` then send each part as a separate " +
            "document; (b) run a temporary HTTP server on the source machine and share the URL " +
            "(e.g. `python3 -m http.server` over SSH-forwarded port); (c) upload to a cloud " +
            "share (rclone, file.io, etc.) and send the link via telegram_send_message; (d) " +
            "compress harder if the content is compressible (zip / tar.zst). Don't retry the " +
            "same path."
    )
}

/** Send a photo from a local file path. */
fun telegramSendPhotoTool(prefs: TelegramBotPreferences, client: TelegramBotClient): Tool = Tool(
    name = "telegram_send_photo",
    description = ("Send a photo via the bot from a local file path. Caption is optional. " +
        "chat_id defaults to the configured default chat. HARD CAP: 10 MB — Telegram Bot API " +
        "rejects larger photos. For larger images use telegram_send_document (50 MB cap) or " +
        "split / link to them.").trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Absolute local file path to the image") })
                put("chat_id", buildJsonObject { put("type", "integer"); put("description", "Optional — defaults to telegram_set_default_chat value") })
                put("caption", buildJsonObject { put("type", "string"); put("description", "Optional caption") })
            },
            required = listOf("path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val path = p["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        val chatId = p["chat_id"]?.jsonPrimitive?.longOrNull ?: prefs.current().defaultChatId
            ?: return@Tool textPart(buildJsonObject { put("error", "no chat_id and no default_chat_id set") })
        val caption = p["caption"]?.jsonPrimitive?.contentOrNull
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return@Tool textPart(buildJsonObject { put("error", "file not found: $path") })
        }
        // Pre-flight size check so we don't waste an upload + battery on a request that will
        // 413 at the end. Same-shape envelope as the document tool so the model can branch
        // generically on { error: "file_too_large_for_telegram_bot" }.
        if (file.length() > TG_BOT_PHOTO_CAP_BYTES) {
            return@Tool textPart(fileTooLargeEnvelope(file, TG_BOT_PHOTO_CAP_BYTES, kind = "photo"))
        }
        textPart(safeApi { buildJsonObject {
            put("success", true)
            put("result", client.sendPhoto(chatId, file, caption))
        } })
    }
)

/** Send a document (any file). */
fun telegramSendDocumentTool(prefs: TelegramBotPreferences, client: TelegramBotClient): Tool = Tool(
    name = "telegram_send_document",
    description = ("Send a file as a document via the bot from a local file path. " +
        "chat_id defaults to the configured default chat. HARD CAP: 50 MB — Telegram Bot API " +
        "rejects larger files. For files over 50 MB, split with `split -b 45m FILE part-`, " +
        "send each part separately, then `cat part-* > FILE` on the receiver to reassemble. " +
        "Or upload to a cloud share and send the link via telegram_send_message. Don't blindly " +
        "retry the same large file — the cap doesn't get raised on retry.").trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Absolute local file path") })
                put("chat_id", buildJsonObject { put("type", "integer"); put("description", "Optional — defaults to telegram_set_default_chat value") })
                put("caption", buildJsonObject { put("type", "string"); put("description", "Optional caption") })
            },
            required = listOf("path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val path = p["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        val chatId = p["chat_id"]?.jsonPrimitive?.longOrNull ?: prefs.current().defaultChatId
            ?: return@Tool textPart(buildJsonObject { put("error", "no chat_id and no default_chat_id set") })
        val caption = p["caption"]?.jsonPrimitive?.contentOrNull
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return@Tool textPart(buildJsonObject { put("error", "file not found: $path") })
        }
        // Pre-flight size check. Without this, large files get fully uploaded to
        // api.telegram.org before the 413 response — wastes ~minutes of bandwidth + battery,
        // and fragmented retries multiply the cost. Fail fast with a structured envelope so
        // the model can pick a recovery path on the FIRST attempt instead of the second.
        if (file.length() > TG_BOT_DOC_CAP_BYTES) {
            return@Tool textPart(fileTooLargeEnvelope(file, TG_BOT_DOC_CAP_BYTES, kind = "document"))
        }
        textPart(safeApi { buildJsonObject {
            put("success", true)
            put("result", client.sendDocument(chatId, file, caption))
        } })
    }
)

/** Add to / update the bot's slash-commands autocomplete menu. */
fun telegramSetCommandsTool(prefs: TelegramBotPreferences, client: TelegramBotClient): Tool = Tool(
    name = "telegram_set_commands",
    description = """
        Add custom commands to the Telegram /commands autocomplete menu. The built-in
        commands (/start, /help, /new, /stop, /status, /model, /ratelimit) are ALWAYS
        preserved — this tool merges your additions on top of them rather than replacing
        the whole menu, so the user never loses their built-in surface. Entries whose
        command name collides with a built-in are dropped (built-ins can't be shadowed).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("commands", buildJsonObject {
                    put("type", "array")
                    put("description", "Array of { command, description } objects. Commands without leading slash, lowercase, 1-32 chars.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("command", buildJsonObject { put("type", "string") })
                            put("description", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add("command"); add("description") })
                    })
                })
            },
            required = listOf("commands")
        )
    },
    execute = { input ->
        val arr = input.jsonObject["commands"]?.jsonArray ?: error("commands is required")
        val userList = arr.map { it.jsonObject }.mapNotNull { o ->
            val c = o["command"]?.jsonPrimitive?.contentOrNull?.removePrefix("/")
            val d = o["description"]?.jsonPrimitive?.contentOrNull
            if (c.isNullOrBlank() || d.isNullOrBlank()) null else c to d
        }
        // Built-ins are reserved — strip any user entry that collides on name. Then
        // dedupe within the user list (last wins). This is what makes the tool safe to
        // call without the model knowing the built-in list — Telegram's setMyCommands is
        // a full REPLACE, so without this merge a single innocuous /weather call would
        // wipe /start /help /new /stop /status /model /ratelimit.
        val builtinNames = TelegramBotService.BUILT_IN_COMMANDS.map { it.first }.toSet()
        // Merge with any previously-persisted custom commands so the model can ADD a
        // command without wiping ones it added in earlier turns. Latest wins on name
        // conflict (re-saving a command updates its description).
        val existing = prefs.current().customCommands
        val deduped = linkedMapOf<String, String>()
        for ((c, d) in existing) deduped[c] = d
        for ((c, d) in userList) if (c.lowercase() !in builtinNames) deduped[c] = d
        val customList = deduped.toList()
        // Persist FIRST so a subsequent app restart re-registers the same set via the
        // bot service's startup hook. THEN push to Telegram.
        prefs.update { it.copy(customCommands = customList) }
        val merged = TelegramBotService.BUILT_IN_COMMANDS + customList
        val skipped = userList.size - userList.count { it.first.lowercase() !in builtinNames }
        textPart(safeApi { buildJsonObject {
            put("success", client.setMyCommands(merged))
            put("builtin_count", TelegramBotService.BUILT_IN_COMMANDS.size)
            put("custom_count", customList.size)
            put("total", merged.size)
            if (skipped > 0) {
                put("note", "Skipped $skipped entry(ies) that collided with built-in command names. Built-ins are reserved.")
            }
        } })
    }
)

/** Read the current /commands menu. */
fun telegramGetCommandsTool(client: TelegramBotClient): Tool = Tool(
    name = "telegram_get_commands",
    description = "Get the current /commands menu the bot exposes to Telegram users.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        textPart(safeApi { buildJsonObject {
            put("commands", client.getMyCommands())
        } })
    }
)

/** Reset the menu to just the built-in commands (clears any custom additions). */
fun telegramDeleteCommandsTool(prefs: TelegramBotPreferences, client: TelegramBotClient): Tool = Tool(
    name = "telegram_delete_commands",
    description = "Reset the bot's /commands menu to ONLY the built-in commands (/start, /help, /new, /stop, /status, /model, /ratelimit). This drops any custom commands previously added via telegram_set_commands (including across restarts — the persistent store is cleared). Built-ins themselves cannot be removed.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        // Drop persisted custom commands BEFORE re-pushing so a crash between the two
        // doesn't leave a phantom registered on Telegram with no record locally.
        prefs.update { it.copy(customCommands = emptyList()) }
        textPart(safeApi { buildJsonObject {
            put("success", client.setMyCommands(TelegramBotService.BUILT_IN_COMMANDS))
            put("kept", TelegramBotService.BUILT_IN_COMMANDS.size)
            put("note", "Custom commands cleared. Built-in commands are preserved.")
        } })
    }
)
