package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.CameraResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.rikkahub.data.ai.tools.local.audioInfoTool
import me.rerere.rikkahub.data.ai.tools.local.batteryTool
import me.rerere.rikkahub.data.ai.tools.local.callLogTool
import me.rerere.rikkahub.data.ai.tools.local.cameraPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.clickNodeTool
import me.rerere.rikkahub.data.ai.tools.local.downloadTool
import me.rerere.rikkahub.data.ai.tools.local.fingerprintTool
import me.rerere.rikkahub.data.ai.tools.local.findNodeTool
import me.rerere.rikkahub.data.ai.tools.local.getBrightnessTool
import me.rerere.rikkahub.data.ai.tools.local.getVolumeTool
import me.rerere.rikkahub.data.ai.tools.local.globalActionTool
import me.rerere.rikkahub.data.ai.tools.local.listContactsTool
import me.rerere.rikkahub.data.ai.tools.local.listSensorsTool
import me.rerere.rikkahub.data.ai.tools.local.listSmsInboxTool
import me.rerere.rikkahub.data.ai.tools.local.locationTool
import me.rerere.rikkahub.data.ai.tools.local.longPressTool
import me.rerere.rikkahub.data.ai.tools.local.mediaScannerTool
import me.rerere.rikkahub.data.ai.tools.local.micRecorderTool
import me.rerere.rikkahub.data.ai.tools.local.notificationTool
import me.rerere.rikkahub.data.ai.tools.local.getMediaStatusTool
import me.rerere.rikkahub.data.ai.tools.local.pauseMediaTool
import me.rerere.rikkahub.data.ai.tools.local.playMediaTool
import me.rerere.rikkahub.data.ai.tools.local.resumeMediaTool
import me.rerere.rikkahub.data.ai.tools.local.seekMediaTool
import me.rerere.rikkahub.data.ai.tools.local.readSensorTool
import me.rerere.rikkahub.data.ai.tools.local.readWindowTreeTool
import me.rerere.rikkahub.data.ai.tools.local.scrollTool
import me.rerere.rikkahub.data.ai.tools.local.searchContactsTool
import me.rerere.rikkahub.data.ai.tools.local.searchSmsTool
import me.rerere.rikkahub.data.ai.tools.local.setBrightnessTool
import me.rerere.rikkahub.data.ai.tools.local.setVolumeTool
import me.rerere.rikkahub.data.ai.tools.local.shareTool
import me.rerere.rikkahub.data.ai.tools.local.speechToTextTool
import me.rerere.rikkahub.data.ai.tools.local.stopMediaTool
import me.rerere.rikkahub.data.ai.tools.local.storageTool
import me.rerere.rikkahub.data.ai.tools.local.swipeTool
import me.rerere.rikkahub.data.ai.tools.local.takeScreenshotTool
import me.rerere.rikkahub.data.ai.tools.local.tapTool
import me.rerere.rikkahub.data.ai.tools.local.telephonyInfoTool
import me.rerere.rikkahub.data.ai.tools.local.toastTool
import me.rerere.rikkahub.data.ai.tools.local.torchTool
import me.rerere.rikkahub.data.ai.tools.local.vibrateTool
import me.rerere.rikkahub.data.ai.tools.local.wifiInfoTool
import me.rerere.rikkahub.data.ai.tools.local.deleteSshHostTool
import me.rerere.rikkahub.data.ai.tools.local.forgetSshHostKeyTool
import me.rerere.rikkahub.data.ai.tools.local.listSshHostsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramAddWhitelistTool
import me.rerere.rikkahub.data.ai.tools.local.telegramDeleteCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramDisableTool
import me.rerere.rikkahub.data.ai.tools.local.telegramEnableTool
import me.rerere.rikkahub.data.ai.tools.local.telegramGetCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramRemoveWhitelistTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendDocumentTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendMessageTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetAssistantTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetDefaultChatTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetTokenTool
import me.rerere.rikkahub.data.ai.tools.local.telegramStatusTool
import me.rerere.rikkahub.data.ai.tools.local.saveSshHostTool
import me.rerere.rikkahub.data.ai.tools.local.sshDownloadTool
import me.rerere.rikkahub.data.ai.tools.local.sshExecSavedTool
import me.rerere.rikkahub.data.ai.tools.local.sshExecTool
import me.rerere.rikkahub.data.ai.tools.local.sshUploadTool
import me.rerere.rikkahub.data.ai.tools.local.writeTextFileTool
import me.rerere.rikkahub.data.ai.tools.local.showImageTool
import me.rerere.rikkahub.data.ai.tools.local.openFileTool
import me.rerere.rikkahub.data.ai.tools.local.transcribeAudioFileTool
import me.rerere.rikkahub.data.ai.tools.local.whisperStatusTool
import me.rerere.rikkahub.data.ai.tools.local.listFilesTool
import me.rerere.rikkahub.data.ai.tools.local.readFileTool
import me.rerere.rikkahub.data.ai.tools.local.writeBinaryFileTool
import me.rerere.rikkahub.data.ai.tools.local.deleteFileTool
import me.rerere.rikkahub.data.ai.tools.local.moveFileTool
import me.rerere.rikkahub.data.ai.tools.local.copyFileTool
import me.rerere.rikkahub.data.ai.tools.local.createDirectoryTool
import me.rerere.rikkahub.data.ai.tools.local.fileInfoTool
import me.rerere.rikkahub.data.ai.tools.local.findFilesTool
import me.rerere.rikkahub.data.ai.tools.local.dismissNotificationTool
import me.rerere.rikkahub.data.ai.tools.local.listActiveNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.listRecentNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.notificationActionClickTool
import me.rerere.rikkahub.data.ai.tools.local.notificationReplyTool
import me.rerere.rikkahub.data.ai.tools.local.notificationStatusTool
import me.rerere.rikkahub.data.ai.tools.local.batchCopyTool
import me.rerere.rikkahub.data.ai.tools.local.batchMoveTool
import me.rerere.rikkahub.data.ai.tools.local.batchDeleteTool
import me.rerere.rikkahub.data.ai.tools.local.webFetchTool
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable @SerialName("battery")        data object Battery        : LocalToolOption()
    @Serializable @SerialName("audio_info")     data object AudioInfo      : LocalToolOption()
    @Serializable @SerialName("telephony_info") data object TelephonyInfo  : LocalToolOption()
    @Serializable @SerialName("wifi_info")      data object WifiInfo       : LocalToolOption()
    @Serializable @SerialName("sensors")        data object Sensors        : LocalToolOption()
    @Serializable @SerialName("storage_info")   data object StorageInfo    : LocalToolOption()
    @Serializable @SerialName("toast")          data object Toast          : LocalToolOption()
    @Serializable @SerialName("notification")   data object Notification   : LocalToolOption()
    @Serializable @SerialName("share")          data object Share          : LocalToolOption()
    @Serializable @SerialName("torch")          data object Torch          : LocalToolOption()
    @Serializable @SerialName("vibrate")        data object Vibrate        : LocalToolOption()
    @Serializable @SerialName("brightness")     data object Brightness     : LocalToolOption()
    @Serializable @SerialName("volume")         data object Volume         : LocalToolOption()
    @Serializable @SerialName("media_player")   data object MediaPlayer    : LocalToolOption()
    @Serializable @SerialName("media_scanner")  data object MediaScanner   : LocalToolOption()
    @Serializable @SerialName("download")       data object Download       : LocalToolOption()

    @Serializable @SerialName("location")        data object Location       : LocalToolOption()
    @Serializable @SerialName("contacts")        data object Contacts       : LocalToolOption()
    @Serializable @SerialName("call_log")        data object CallLog        : LocalToolOption()
    @Serializable @SerialName("sms_inbox")       data object SmsInbox       : LocalToolOption()
    @Serializable @SerialName("camera_photo")    data object CameraPhoto    : LocalToolOption()
    @Serializable @SerialName("mic_recorder")    data object MicRecorder    : LocalToolOption()
    @Serializable @SerialName("speech_to_text")  data object SpeechToText   : LocalToolOption()
    @Serializable @SerialName("fingerprint")     data object Fingerprint    : LocalToolOption()
    @Serializable @SerialName("cron_jobs")       data object CronJobs       : LocalToolOption()
    @Serializable @SerialName("ssh")             data object Ssh            : LocalToolOption()
    @Serializable @SerialName("telegram_bot")    data object TelegramBot    : LocalToolOption()
    @Serializable @SerialName("screen_automation") data object ScreenAutomation : LocalToolOption()
    @Serializable @SerialName("app_launcher")      data object AppLauncher       : LocalToolOption()
    @Serializable @SerialName("termux")            data object Termux            : LocalToolOption()
    @Serializable @SerialName("notification_listener") data object NotificationListener : LocalToolOption()
    @Serializable @SerialName("files")               data object Files              : LocalToolOption()
    @Serializable @SerialName("mcp_control")         data object McpControl         : LocalToolOption()
    @Serializable @SerialName("external_automation") data object ExternalAutomation : LocalToolOption()
    @Serializable @SerialName("reliability")         data object Reliability        : LocalToolOption()
    @Serializable @SerialName("sub_agents")          data object SubAgents          : LocalToolOption()
    @Serializable @SerialName("cost_guards")         data object CostGuards         : LocalToolOption()
    @Serializable @SerialName("workflows")           data object Workflows          : LocalToolOption()
    @Serializable @SerialName("skill_import")        data object SkillImport        : LocalToolOption()
    @Serializable @SerialName("js_skills")           data object JsSkills           : LocalToolOption()
    @Serializable @SerialName("system_intents")      data object SystemIntents      : LocalToolOption()
    @Serializable @SerialName("browser")             data object Browser            : LocalToolOption()
    @Serializable @SerialName("web_fetch")           data object WebFetch           : LocalToolOption()

    // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
    @Serializable @SerialName("sms_send")             data object SmsSend             : LocalToolOption()
    @Serializable @SerialName("wallpaper")            data object Wallpaper           : LocalToolOption()
    @Serializable @SerialName("keystore")             data object Keystore            : LocalToolOption()
    @Serializable @SerialName("nfc")                  data object Nfc                 : LocalToolOption()
    @Serializable @SerialName("external_storage")     data object ExternalStorage     : LocalToolOption()
    @Serializable @SerialName("archive")              data object Archive             : LocalToolOption()
    @Serializable @SerialName("keyboard_control")     data object KeyboardControl     : LocalToolOption()
}

private val TOP_TOOL_EXAMPLES: Map<String, String> = mapOf(
    "get_battery_status" to "get_battery_status()",
    "get_audio_info" to "get_audio_info()",
    "get_telephony_info" to "get_telephony_info()",
    "get_wifi_info" to "get_wifi_info()",
    "list_sensors" to "list_sensors()",
    "read_sensor" to "read_sensor(type=\"accelerometer\", duration_ms=500)",
    "get_storage_info" to "get_storage_info()",
    "show_toast" to "show_toast(text=\"Done\")",
    "post_notification" to "post_notification(title=\"Reminder\", body=\"Take a break\")",
    "share" to "share(text=\"Hello\")",
    "set_torch" to "set_torch(enabled=true)",
    "vibrate" to "vibrate(duration_ms=250)",
    "get_brightness" to "get_brightness()",
    "set_brightness" to "set_brightness(value=160)",
    "get_volume" to "get_volume(stream=\"media\")",
    "set_volume" to "set_volume(stream=\"media\", percent=50)",
    "play_media" to "play_media(source=\"file:///sdcard/Music/song.mp3\", title=\"Song\")",
    "stop_media" to "stop_media()",
    "pause_media" to "pause_media()",
    "resume_media" to "resume_media()",
)

internal fun appendTopToolExample(tool: Tool): Tool {
    val example = TOP_TOOL_EXAMPLES[tool.name] ?: return tool
    if (tool.description.contains("Example:", ignoreCase = true)) return tool
    return tool.copy(description = "${tool.description.trim()} Example: $example.")
}

internal fun appendHumanErrorToToolResult(part: UIMessagePart): UIMessagePart {
    if (part !is UIMessagePart.Text) return part
    val jsonObject = runCatching {
        Json.parseToJsonElement(part.text).jsonObject
    }.getOrNull() ?: return part
    if ("error" !in jsonObject) return part

    val detail = jsonObject["detail"] ?: jsonObject["reason"]
    val recovery = jsonObject["recovery"]
    val humanError = jsonObject["human_error"]?.jsonPrimitive?.contentOrNull
        ?: humanizeToolError(jsonObject)

    return part.copy(
        text = buildJsonObject {
            jsonObject["error"]?.let { put("error", it) }
            detail?.let { put("detail", it) }
            recovery?.let { put("recovery", it) }
            put("human_error", humanError)
            jsonObject.forEach { (key, value) ->
                if (key !in STANDARD_ERROR_KEYS) {
                    put(key, value)
                }
            }
        }.toString()
    )
}

internal fun addHumanErrorEnvelopes(tool: Tool): Tool = tool.copy(
    execute = { input ->
        tool.execute(input).map(::appendHumanErrorToToolResult)
    }
)

private fun humanizeToolError(jsonObject: JsonObject): String {
    val error = jsonObject["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val detail = jsonObject["detail"]?.jsonPrimitive?.contentOrNull
        ?: jsonObject["reason"]?.jsonPrimitive?.contentOrNull
        ?: jsonObject["recovery"]?.jsonPrimitive?.contentOrNull
    val readableError = error.replace('_', ' ').ifBlank { "Tool error" }
    return if (detail.isNullOrBlank()) {
        readableError.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    } else {
        "$readableError: $detail"
    }
}

private val STANDARD_ERROR_KEYS = setOf("error", "detail", "reason", "recovery", "human_error")

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val cameraResultBuffer: CameraResultBuffer,
    private val biometricResultBuffer: BiometricResultBuffer,
    private val scheduledJobRepository: me.rerere.rikkahub.data.repository.ScheduledJobRepository,
    private val scheduledJobRunRepository: me.rerere.rikkahub.data.repository.ScheduledJobRunRepository,
    private val cronJobScheduler: me.rerere.rikkahub.service.CronJobScheduler,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    private val sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    private val telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    private val telegramBotClient: me.rerere.rikkahub.data.telegram.TelegramBotClient,
    private val notificationListenerPreferences: me.rerere.rikkahub.data.notifications.NotificationListenerPreferences,
    private val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager,
    private val externalAutomationConfig: me.rerere.rikkahub.automation.ExternalAutomationConfig,
    private val gitHubReleaseChecker: me.rerere.rikkahub.reliability.GitHubReleaseChecker,
    private val bugReportBuilder: me.rerere.rikkahub.reliability.BugReportBuilder,
    private val subAgentEngine: me.rerere.rikkahub.subagent.SubAgentEngine,
    private val subAgentRegistry: me.rerere.rikkahub.subagent.SubAgentRegistry,
    private val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
    private val workflowRepository: me.rerere.rikkahub.workflow.repository.WorkflowRepository,
    private val workflowEngine: me.rerere.rikkahub.workflow.execution.WorkflowEngine,
    private val skillUrlImporter: me.rerere.rikkahub.skills.SkillUrlImporter,
    private val skillManager: me.rerere.rikkahub.data.files.SkillManager,
    private val jsSkillRunner: me.rerere.rikkahub.skills.js.JsSkillRunner,
    private val skillSecretsStore: me.rerere.rikkahub.skills.js.SkillSecretsStore,
    // Browser per-tool toggle store. Pass 2 reads a [snapshotBlocking] of the map so each
    // tool factory gates its own registration on whether the user has flipped it on. Master
    // toggle ([LocalToolOption.Browser]) acts as the group on/off; per-tool toggles act as
    // a sub-allow-list. Both must be true for a tool to register.
    private val browserPreferences: me.rerere.rikkahub.browser.BrowserPreferences,
    // TermuxPreferences is injected here solely to force Koin to construct it at first tool
    // use (same trick as browserPreferences above). Koin singles are lazy; without this
    // constructor reference the singleton — and its init{} sync seed + async collectors —
    // would never build in headless sessions that skip Settings -> Termux.
    @Suppress("UNUSED_PARAMETER")
    termuxPreferences: me.rerere.rikkahub.data.preferences.TermuxPreferences,
    // Post-action screenshot streamer for headless mode (Telegram bot / cron / sub-agent).
    // Injected rather than Koin-resolved inside each factory so JVM tests can pass a mock.
    private val interactiveToolStreamer: InteractiveToolStreamer,
    // Phase 25 — NFC / SAF Activity-bridge buffers + the SAF tree-grant store.
    private val nfcResultBuffer: me.rerere.rikkahub.data.ai.tools.local.NfcResultBuffer,
    private val safPickerResultBuffer: me.rerere.rikkahub.data.ai.tools.local.SafPickerResultBuffer,
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore,
    // Shared OkHttp singleton (NetworkChangeMonitor-registered) — backs the web_fetch tool.
    private val okHttpClient: okhttp3.OkHttpClient,
    // agent-keyboard IPC client — backs the keyboard_* tools (drives the active text field).
    private val keyboardApiClient: me.rerere.rikkahub.data.keyboard.KeyboardApiClient,
) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = """
                Execute JavaScript code using QuickJS engine (ES2020).
                The result is the value of the last expression in the code.
                For calculations with decimals, use toFixed() to control precision.
                Console output (log/info/warn/error) is captured and returned in 'logs' field.
                No DOM or Node.js APIs available.
                Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
                        })
                    },
                    required = listOf("code")
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val context = QuickJSContext.create()
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) {
                        logs.add("[LOG] $info")
                    }

                    override fun info(info: String?) {
                        logs.add("[INFO] $info")
                    }

                    override fun warn(info: String?) {
                        logs.add("[WARN] $info")
                    }

                    override fun error(info: String?) {
                        logs.add("[ERROR] $info")
                    }
                })
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val timeTool by lazy {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val clipboardTool by lazy {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val text = context.readClipboardText()
                        val hits = me.rerere.rikkahub.data.ai.tools.local
                            .SensitiveContentDetector.scan(text)
                        val payload = buildJsonObject {
                            put("text", text)
                            if (hits.isNotEmpty()) {
                                put("sensitive_content_detected", true)
                                put(
                                    "warning",
                                    "Clipboard appears to contain sensitive content " +
                                        "(${hits.joinToString { it.name.lowercase() }}). " +
                                        "Do NOT echo the value back to the user, log it, " +
                                        "or include it in summaries or URLs unless they " +
                                        "explicitly ask for it."
                                )
                            }
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

    val ttsTool by lazy {
        Tool(
            name = "text_to_speech",
            description = """
                Speak text aloud to the user using the device's text-to-speech engine.
                Use this when the user asks you to read something aloud, or when audio output is appropriate.
                The tool returns immediately; audio plays in the background on the device.
                Provide natural, readable text without markdown formatting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "The text to speak aloud")
                        })
                    },
                    required = listOf("text")
                )
            },
            execute = {
                val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required")
                eventBus.emit(AppEvent.Speak(text))
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val askUserTool by lazy {
        Tool(
            name = "ask_user",
            description = """
                Ask the user one or more questions when you need clarification, additional information, or confirmation.
                Each question can optionally provide a list of suggested options for the user to choose from.
                The user may select an option or provide their own free-text answer for each question.
                The answers will be returned as a JSON object mapping question IDs to the user's responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                        )
                                    })
                                })
                                put("required", kotlinx.serialization.json.buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = true,
            execute = {
                // Reached only when no human-in-the-loop surface intercepted this call. The
                // in-app question card and the Telegram clarify flow both handle ask_user before
                // execute() runs; any other context (cron jobs, sub-agents) has nobody to answer,
                // so return a graceful envelope telling the model to ask in plain text rather than
                // throwing an opaque tool_failed.
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "ask_user_unavailable")
                            put(
                                "detail",
                                "Interactive questions aren't available in this context. Ask your question in your normal reply text instead; the user will read it and answer."
                            )
                        }.toString()
                    )
                )
            }
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.Battery)) {
            tools.add(batteryTool(context))
        }
        if (options.contains(LocalToolOption.AudioInfo)) {
            tools.add(audioInfoTool(context))
        }
        if (options.contains(LocalToolOption.TelephonyInfo)) {
            tools.add(telephonyInfoTool(context))
        }
        if (options.contains(LocalToolOption.WifiInfo)) {
            tools.add(wifiInfoTool(context))
        }
        if (options.contains(LocalToolOption.Sensors)) {
            tools.add(listSensorsTool(context))
            tools.add(readSensorTool(context))
        }
        if (options.contains(LocalToolOption.StorageInfo)) {
            tools.add(storageTool(context))
        }
        if (options.contains(LocalToolOption.Toast)) {
            tools.add(toastTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.Notification)) {
            tools.add(notificationTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.Share)) {
            tools.add(shareTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.Torch)) {
            tools.add(torchTool(context))
        }
        if (options.contains(LocalToolOption.Vibrate)) {
            tools.add(vibrateTool(context))
        }
        if (options.contains(LocalToolOption.Brightness)) {
            tools.add(getBrightnessTool(context))
            tools.add(setBrightnessTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.Volume)) {
            tools.add(getVolumeTool(context))
            tools.add(setVolumeTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.MediaPlayer)) {
            tools.add(playMediaTool(context, invocationContext, interactiveToolStreamer))
            tools.add(stopMediaTool(context))
            tools.add(pauseMediaTool(context))
            tools.add(resumeMediaTool(context))
            tools.add(seekMediaTool(context))
            tools.add(getMediaStatusTool())
        }
        if (options.contains(LocalToolOption.MediaScanner)) {
            tools.add(mediaScannerTool(context))
        }
        if (options.contains(LocalToolOption.Download)) {
            tools.add(downloadTool(context))
            tools.add(writeTextFileTool(context))
        }
        if (options.contains(LocalToolOption.Location)) {
            tools.add(locationTool(context))
        }
        if (options.contains(LocalToolOption.Contacts)) {
            tools.add(searchContactsTool(context))
            tools.add(listContactsTool(context))
        }
        if (options.contains(LocalToolOption.CallLog)) {
            tools.add(callLogTool(context))
        }
        if (options.contains(LocalToolOption.SmsInbox)) {
            tools.add(listSmsInboxTool(context))
            tools.add(searchSmsTool(context))
        }
        if (options.contains(LocalToolOption.CameraPhoto)) {
            tools.add(cameraPhotoTool(context, cameraResultBuffer))
        }
        if (options.contains(LocalToolOption.MicRecorder)) {
            tools.add(micRecorderTool(context))
        }
        if (options.contains(LocalToolOption.SpeechToText)) {
            tools.add(speechToTextTool(context))
        }
        if (options.contains(LocalToolOption.Fingerprint)) {
            tools.add(fingerprintTool(context, biometricResultBuffer))
        }
        if (options.contains(LocalToolOption.Ssh)) {
            tools.add(sshExecTool(context))
            tools.add(saveSshHostTool(sshHostRepository))
            tools.add(listSshHostsTool(sshHostRepository))
            tools.add(deleteSshHostTool(sshHostRepository))
            tools.add(sshExecSavedTool(context, sshHostRepository))
            tools.add(sshUploadTool(context, sshHostRepository))
            tools.add(sshDownloadTool(context, sshHostRepository))
            tools.add(forgetSshHostKeyTool(context))
        }
        if (options.contains(LocalToolOption.TelegramBot)) {
            tools.add(telegramSetTokenTool(telegramBotPreferences, telegramBotClient))
            tools.add(telegramStatusTool(context, telegramBotPreferences, telegramBotClient))
            tools.add(telegramEnableTool(context, telegramBotPreferences))
            tools.add(telegramDisableTool(context, telegramBotPreferences))
            tools.add(telegramAddWhitelistTool(telegramBotPreferences))
            tools.add(telegramRemoveWhitelistTool(telegramBotPreferences))
            tools.add(telegramSetDefaultChatTool(telegramBotPreferences))
            tools.add(telegramSetAssistantTool(telegramBotPreferences))
            tools.add(telegramSendMessageTool(telegramBotPreferences, telegramBotClient))
            tools.add(telegramSendPhotoTool(telegramBotPreferences, telegramBotClient))
            tools.add(telegramSendDocumentTool(telegramBotPreferences, telegramBotClient))
            tools.add(telegramSetCommandsTool(telegramBotPreferences, telegramBotClient))
            tools.add(telegramGetCommandsTool(telegramBotClient))
            tools.add(telegramDeleteCommandsTool(telegramBotPreferences, telegramBotClient))
        }
        if (options.contains(LocalToolOption.CronJobs)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.scheduleJobTool(scheduledJobRepository, cronJobScheduler, settingsStore,
                knownToolNamesProvider = { tools.map { it.name } }))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listJobsTool(scheduledJobRepository))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.deleteJobTool(scheduledJobRepository, scheduledJobRunRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.pauseJobTool(scheduledJobRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.resumeJobTool(scheduledJobRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.triggerJobNowTool(scheduledJobRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.getJobHistoryTool(scheduledJobRepository, scheduledJobRunRepository))
        }
        if (options.contains(LocalToolOption.ScreenAutomation)) {
            tools.add(tapTool(invocationContext, interactiveToolStreamer))
            tools.add(longPressTool(invocationContext, interactiveToolStreamer))
            tools.add(swipeTool(invocationContext, interactiveToolStreamer))
            tools.add(readWindowTreeTool(invocationContext, interactiveToolStreamer))
            tools.add(findNodeTool(invocationContext, interactiveToolStreamer))
            tools.add(clickNodeTool(invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.setTextTool(invocationContext, interactiveToolStreamer))
            tools.add(scrollTool(invocationContext, interactiveToolStreamer))
            tools.add(globalActionTool(invocationContext, interactiveToolStreamer))
            tools.add(takeScreenshotTool(context))  // take_screenshot IS the screenshot; skip auto-stream
            tools.add(me.rerere.rikkahub.data.ai.tools.local.wakeScreenTool(context))
        }
        if (options.contains(LocalToolOption.AppLauncher)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.launchAppTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listInstalledAppsTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.openUrlTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.Termux)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool(context))
            // Persistent interactive (tmux-backed) sessions: ssh-with-prompts, sudo, REPLs,
            // stateful shells. start is approval-gated; send is hardline-guarded per call.
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionStartTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionSendTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionReadTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionKillTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionListTool(context))
            // transcribe_audio_file shells out to whisper-cli via Termux's RUN_COMMAND
            // service — it has a hard transitive dependency on Termux being present. No
            // separate toggle; it lives under the Termux toggle.
            tools.add(transcribeAudioFileTool(context))
            // whisper_status is a free read-only pre-flight check — no approval needed.
            // The LLM calls this BEFORE attempting transcription to know what's set up.
            tools.add(whisperStatusTool(context, settingsStore))
        }
        if (options.contains(LocalToolOption.NotificationListener)) {
            tools.add(listRecentNotificationsTool())
            tools.add(listActiveNotificationsTool())
            tools.add(dismissNotificationTool())
            tools.add(notificationActionClickTool())
            tools.add(notificationReplyTool())
            tools.add(notificationStatusTool(notificationListenerPreferences, telegramBotPreferences))
        }
        if (options.contains(LocalToolOption.Files)) {
            tools.add(listFilesTool())
            tools.add(readFileTool())
            tools.add(writeBinaryFileTool())
            tools.add(deleteFileTool())
            tools.add(moveFileTool())
            tools.add(copyFileTool())
            tools.add(createDirectoryTool())
            tools.add(fileInfoTool())
            tools.add(findFilesTool())
            tools.add(showImageTool(context, invocationContext.modelCanSeeImages))  // inline image display; no separate auto-stream needed
            tools.add(openFileTool(context, invocationContext, interactiveToolStreamer))
            // Batch ops (item 5.5) — list-or-glob copy / move / delete. Same toggle group
            // as the single-path file tools; every path still goes through PathSafetyGuard.
            tools.add(batchCopyTool())
            tools.add(batchMoveTool())
            tools.add(batchDeleteTool())
        }
        if (options.contains(LocalToolOption.McpControl)) {
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpListTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpGetTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpAddTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpUpdateTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpDeleteTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpSetEnabledTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpTestTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpListToolsTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpSetToolApprovalTool(settingsStore))
        }
        if (options.contains(LocalToolOption.ExternalAutomation)) {
            tools.add(me.rerere.rikkahub.automation.externalAutomationStatusTool(externalAutomationConfig))
            tools.add(me.rerere.rikkahub.automation.externalAutomationSetEnabledTool(externalAutomationConfig))
            tools.add(me.rerere.rikkahub.automation.externalAutomationAddTrustedPackageTool(externalAutomationConfig))
            tools.add(me.rerere.rikkahub.automation.externalAutomationRemoveTrustedPackageTool(externalAutomationConfig))
        }
        if (options.contains(LocalToolOption.Reliability)) {
            tools.add(me.rerere.rikkahub.reliability.checkAppUpdatesTool(gitHubReleaseChecker))
            tools.add(me.rerere.rikkahub.reliability.generateBugReportTool(context, bugReportBuilder))
        }
        if (options.contains(LocalToolOption.SubAgents)) {
            // Pass the caller context so the recursion guard inside SubAgentEngine.dispatch
            // can fire — the dispatch tool itself can't read its own coroutine context, but
            // ChatService / cron / workflow / external-automation know who's calling at the
            // moment they construct the tool list.
            tools.add(me.rerere.rikkahub.subagent.subagentDispatchTool(subAgentEngine, invocationContext))
            tools.add(me.rerere.rikkahub.subagent.subagentListTool(subAgentRegistry))
            tools.add(me.rerere.rikkahub.subagent.subagentGetTool(subAgentRegistry))
            tools.add(me.rerere.rikkahub.subagent.subagentCancelTool(subAgentRegistry))
        }
        if (options.contains(LocalToolOption.CostGuards)) {
            tools.add(me.rerere.rikkahub.costguards.checkTokenUsageTool(settingsStore, conversationRepo))
        }
        if (options.contains(LocalToolOption.SkillImport)) {
            tools.add(me.rerere.rikkahub.skills.skillInstallFromUrlTool(skillUrlImporter, settingsStore, skillManager))
            tools.add(me.rerere.rikkahub.skills.skillInstallFromTextTool(skillUrlImporter, settingsStore, skillManager))
        }
        if (options.contains(LocalToolOption.JsSkills)) {
            tools.add(me.rerere.rikkahub.skills.js.runJsTool(
                context, skillManager, jsSkillRunner, skillSecretsStore,
            ))
        }
        if (options.contains(LocalToolOption.SystemIntents)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.createCalendarEventTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.createContactTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.sendEmailIntentTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.sendSmsIntentTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.openWifiSettingsTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.showLocationOnMapTool(context, invocationContext, interactiveToolStreamer))
        }
        if (options.contains(LocalToolOption.Workflows)) {
            // workflow_create persists the authoringAssistantId from [context] so the
            // engine can resolve the right tool surface at fire time (not "any assistant
            // with the Workflows toggle on", which is non-deterministic across UI reorder).
            tools.add(me.rerere.rikkahub.workflow.tools.workflowCreateTool(
                workflowRepository,
                knownToolNamesProvider = { tools.map { it.name } },
                callerContext = invocationContext,
            ))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowListTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowGetTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowUpdateTool(
                workflowRepository,
                knownToolNamesProvider = { tools.map { it.name } },
                callerContext = invocationContext,
            ))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowDeleteTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowSetEnabledTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowRunTool(workflowEngine, workflowRepository))
        }
        if (options.contains(LocalToolOption.Browser)) {
            // Per-tool registration. The user can grant only the tools they trust — read
            // tools default ON, write tools default OFF (see BrowserToolDefaults.DEFAULT_ENABLED).
            // snapshotBlocking() reads DataStore once; steady-state cost is microseconds because
            // DataStore caches the latest Preferences instance after the first decode.
            val browserPrefs = browserPreferences.snapshotBlocking()
            me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS.forEach { name ->
                if (browserPrefs[name] == true) {
                    me.rerere.rikkahub.data.ai.tools.local.createBrowserTool(
                        toolName = name,
                        context = context,
                        // Pass 3: thread the caller context so browser_open can pick the
                        // foreground vs headless mode by reading HeadlessConversations.
                        invocationContext = invocationContext,
                    )?.let { tools.add(it) }
                }
            }
        }
        if (options.contains(LocalToolOption.WebFetch)) {
            // Lightweight HTTP GET/POST (item 1.2) — backed by the shared OkHttp singleton.
            tools.add(webFetchTool(okHttpClient))
        }
        // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
        if (options.contains(LocalToolOption.SmsSend)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.smsSendTool(context))
        }
        if (options.contains(LocalToolOption.Wallpaper)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.setWallpaperTool(context))
        }
        if (options.contains(LocalToolOption.Keystore)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreGenerateKeyTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreSignTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreVerifyTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreEncryptTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreDecryptTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreDeleteKeyTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreListKeysTool())
        }
        if (options.contains(LocalToolOption.Nfc)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.nfcReadTagTool(context, nfcResultBuffer, invocationContext))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.nfcWriteTagTool(context, nfcResultBuffer, invocationContext))
        }
        if (options.contains(LocalToolOption.ExternalStorage)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listStorageVolumesTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listGrantedDirectoriesTool(context, storageVolumeGrantStore))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.grantDirectoryAccessTool(
                context, storageVolumeGrantStore, safPickerResultBuffer, invocationContext,
            ))
        }
        if (options.contains(LocalToolOption.Archive)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.zipFilesTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.unzipFileTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listZipContentsTool(context))
        }
        if (options.contains(LocalToolOption.KeyboardControl)) {
            // Drives the active text field through the co-signed agent-keyboard IME.
            // Write tools are approval-gated via ToolApprovalDefaults; the two read tools
            // (keyboard_read_field, keyboard_editor_info) are not.
            tools.add(keyboardTypeTool(keyboardApiClient))
            tools.add(keyboardReadFieldTool(keyboardApiClient))
            tools.add(keyboardPressKeyTool(keyboardApiClient))
            tools.add(keyboardDeleteTool(keyboardApiClient))
            tools.add(keyboardClearTool(keyboardApiClient))
            tools.add(keyboardEditorInfoTool(keyboardApiClient))
            tools.add(keyboardSetCursorTool(keyboardApiClient))
            tools.add(keyboardSelectRangeTool(keyboardApiClient))
        }
        // Centralised opt-in to needsApproval. Tool factories themselves don't have to know
        // whether their op is destructive — ToolApprovalDefaults is the single source of
        // truth, and the GenerationHandler / Telegram/in-app prompt path keys off needsApproval.
        return tools.map { t ->
            val withApproval = if (!t.needsApproval && ToolApprovalDefaults.requiresApproval(t.name)) {
                t.copy(needsApproval = true)
            } else {
                t
            }
            addHumanErrorEnvelopes(appendTopToolExample(withApproval))
        }
    }
}
