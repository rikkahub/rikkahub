package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.notifications.NotificationEntry
import me.rerere.rikkahub.data.notifications.NotificationListenerPreferences
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.RikkaNotificationListenerService

private fun NotificationEntry.toJson(): JsonObject = buildJsonObject {
    put("key", key)
    put("package", packageName)
    put("label", label)
    put("title", title)
    put("text", text)
    if (subText.isNotEmpty()) put("sub_text", subText)
    put("post_time_ms", postTimeMs)
    if (actionTitles.isNotEmpty()) {
        put("action_titles", buildJsonArray { actionTitles.forEach { add(it) } })
    }
}

fun listRecentNotificationsTool(): Tool = Tool(
    name = "list_recent_notifications",
    description = """
        Return notifications captured by the in-app listener since it was bound. Backed by a
        100-entry ring buffer, ordered oldest -> newest. Use limit (default 50, max 100),
        package_name (case-insensitive substring match against package or app label), and
        since_unix_ms (only entries posted after this) to narrow the result. Returns
        {error, recovery} if the listener is not bound.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned entries (default 50, max 100)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring filter against package or label")
                })
                put("since_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Only entries with post_time_ms >= this value")
                })
            }
        )
    },
    execute = { input ->
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 50
        val pkgNeedle = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val sinceMs = input.jsonObject["since_unix_ms"]?.jsonPrimitive?.longOrNull ?: 0L

        val payload = NotificationListenerHandle.withListener { svc ->
            val all = svc.recent.value
            val filtered = all.asSequence()
                .filter { it.postTimeMs >= sinceMs }
                .filter {
                    pkgNeedle == null ||
                        it.packageName.lowercase().contains(pkgNeedle) ||
                        it.label.lowercase().contains(pkgNeedle)
                }
                .toList()
                .takeLast(limit)
            buildJsonObject {
                put("notifications", buildJsonArray { filtered.forEach { add(it.toJson()) } })
                put("total_in_buffer", all.size)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun listActiveNotificationsTool(): Tool = Tool(
    name = "list_active_notifications",
    description = "Notifications currently in the status bar / shade (vs list_recent_notifications = historical ring buffer). Use when you need to act on something the user can see now (dismiss, click action). Filters: limit (default 50, max 100), package_name (case-insensitive substring), since_unix_ms.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned entries (default 50, max 100)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring filter against package or label")
                })
                put("since_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Only entries with post_time_ms >= this value")
                })
            }
        )
    },
    execute = { input ->
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 50
        val pkgNeedle = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val sinceMs = input.jsonObject["since_unix_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val payload = NotificationListenerHandle.withListener { svc ->
            val all = svc.listActive()
            val filtered = all.asSequence()
                .filter { it.postTimeMs >= sinceMs }
                .filter {
                    pkgNeedle == null ||
                        it.packageName.lowercase().contains(pkgNeedle) ||
                        it.label.lowercase().contains(pkgNeedle)
                }
                .toList()
                .take(limit)
            buildJsonObject {
                put("notifications", buildJsonArray { filtered.forEach { add(it.toJson()) } })
                put("total_active", all.size)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun dismissNotificationTool(): Tool = Tool(
    name = "dismiss_notification",
    description = """
        Dismiss an active notification by its key (obtained from list_active_notifications or
        list_recent_notifications). Only works on currently active notifications; ring-buffer
        keys for already-dismissed notifications return {error: not_found}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("notification_key", buildJsonObject {
                    put("type", "string")
                    put("description", "The .key field from a previous notifications list call")
                })
            },
            required = listOf("notification_key")
        )
    },
    execute = { input ->
        val key = input.jsonObject["notification_key"]?.jsonPrimitive?.contentOrNull
        if (key.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "notification_key is required") }.toString()
                )
            )
        }
        val payload = NotificationListenerHandle.withListener { svc ->
            val ok = svc.dismissByKey(key)
            buildJsonObject {
                put("success", ok)
                if (!ok) put("reason", "not_found")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun notificationActionClickTool(): Tool = Tool(
    name = "notification_action_click",
    description = """
        Fire one of a notification's action buttons (e.g. Reply, Mark as read). Pass either
        action_index (0-based) or action_title (case-insensitive exact match). If the action
        requires text input (e.g. WhatsApp Reply with RemoteInput), returns
        {error: requires_input} - in that case fall back to launch_app + set_text via the
        screen automation tools.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("notification_key", buildJsonObject {
                    put("type", "string")
                    put("description", "The .key field from a previous notifications list call")
                })
                put("action_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based index of the action; mutually exclusive with action_title")
                })
                put("action_title", buildJsonObject {
                    put("type", "string")
                    put("description", "Case-insensitive title of the action")
                })
            },
            required = listOf("notification_key")
        )
    },
    execute = { input ->
        val key = input.jsonObject["notification_key"]?.jsonPrimitive?.contentOrNull
        val idx = input.jsonObject["action_index"]?.jsonPrimitive?.intOrNull
        val title = input.jsonObject["action_title"]?.jsonPrimitive?.contentOrNull
        if (key.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "notification_key is required") }.toString()
                )
            )
        }
        if (idx == null && title.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "either action_index or action_title is required")
                    }.toString()
                )
            )
        }
        val payload = NotificationListenerHandle.withListener { svc ->
            when (val res = svc.triggerAction(key, idx, title)) {
                is RikkaNotificationListenerService.TriggerResult.Success -> buildJsonObject {
                    put("success", true)
                    put("action_used", res.actionTitle)
                }
                RikkaNotificationListenerService.TriggerResult.NotFound -> buildJsonObject {
                    put("error", "not_found")
                }
                RikkaNotificationListenerService.TriggerResult.NoAction -> buildJsonObject {
                    put("error", "no_action")
                }
                is RikkaNotificationListenerService.TriggerResult.RequiresInput -> buildJsonObject {
                    put("error", "requires_input")
                    put("action_title", res.actionTitle)
                    put("recovery", "This action takes user input (e.g. typing a reply). Use launch_app + set_text + click_node from the screen automation tools to drive the input UI directly.")
                }
                is RikkaNotificationListenerService.TriggerResult.SendFailed -> buildJsonObject {
                    put("error", "send_failed")
                    put("reason", res.reason)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun notificationReplyTool(): Tool = Tool(
    name = "notification_reply",
    description = """
        Reply to a notification in one call. Fills the notification's direct-reply action
        (RemoteInput) with text and fires it — works for WhatsApp / Messages / Telegram and
        any app exposing an inline reply. Pass notification_key (from a notifications list
        call) and text. Returns {error: no_action} if the notification has no reply action
        (fall back to launch_app + set_text via screen automation), {error: not_found} if
        the notification is no longer active.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("notification_key", buildJsonObject {
                    put("type", "string")
                    put("description", "The .key field from a previous notifications list call")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The reply text to send")
                })
            },
            required = listOf("notification_key", "text")
        )
    },
    execute = { input ->
        val key = input.jsonObject["notification_key"]?.jsonPrimitive?.contentOrNull
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        if (key.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "notification_key is required") }.toString()
                )
            )
        }
        if (text.isNullOrEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "text is required") }.toString()
                )
            )
        }
        val payload = NotificationListenerHandle.withListener { svc ->
            when (val res = svc.triggerReplyAction(key, text)) {
                is RikkaNotificationListenerService.TriggerResult.Success -> buildJsonObject {
                    put("success", true)
                    put("action_used", res.actionTitle)
                }
                RikkaNotificationListenerService.TriggerResult.NotFound -> buildJsonObject {
                    put("error", "not_found")
                    put("recovery", "The notification is no longer in the status bar. Re-list active notifications.")
                }
                RikkaNotificationListenerService.TriggerResult.NoAction -> buildJsonObject {
                    put("error", "no_action")
                    put("recovery", "This notification has no direct-reply action. Use launch_app + set_text + click_node from the screen automation tools.")
                }
                is RikkaNotificationListenerService.TriggerResult.RequiresInput -> buildJsonObject {
                    // triggerReplyAction never returns RequiresInput, but the sealed class
                    // demands exhaustiveness — treat it as a no-action fallback.
                    put("error", "no_action")
                    put("recovery", "Use launch_app + set_text + click_node from the screen automation tools.")
                }
                is RikkaNotificationListenerService.TriggerResult.SendFailed -> buildJsonObject {
                    put("error", "send_failed")
                    put("reason", res.reason)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun notificationStatusTool(
    listenerPrefs: NotificationListenerPreferences,
    telegramPrefs: TelegramBotPreferences,
): Tool = Tool(
    name = "notification_status",
    description = """
        Returns a snapshot of the notification listener subsystem: whether the OS has bound
        the service, ring buffer size, current whitelist size, and whether a default Telegram
        chat is configured (which auto-route forwarding requires).
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val bound = NotificationListenerHandle.isBound()
        val ringSize = RikkaNotificationListenerService.instance?.recent?.value?.size ?: 0
        val cfg = try { listenerPrefs.current() } catch (_: Throwable) { null }
        val tg = try { telegramPrefs.current() } catch (_: Throwable) { null }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("service_bound", bound)
                    put("ring_buffer_count", ringSize)
                    put("whitelist_count", cfg?.whitelist?.size ?: 0)
                    put("has_default_telegram_chat", tg?.defaultChatId != null && tg.enabled)
                }.toString()
            )
        )
    }
)
