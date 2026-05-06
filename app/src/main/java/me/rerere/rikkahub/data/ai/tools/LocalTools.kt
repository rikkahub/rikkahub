package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
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
import me.rerere.rikkahub.data.ai.tools.local.MediaPlayerHolder
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
import me.rerere.rikkahub.data.ai.tools.local.dismissNotificationTool
import me.rerere.rikkahub.data.ai.tools.local.listActiveNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.listRecentNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.notificationActionClickTool
import me.rerere.rikkahub.data.ai.tools.local.notificationStatusTool
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
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val mediaPlayerHolder: MediaPlayerHolder,
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
                        val payload = buildJsonObject {
                            put("text", context.readClipboardText())
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
                error("ask_user tool should be handled by HITL flow")
            }
        )
    }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
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
            tools.add(toastTool(context))
        }
        if (options.contains(LocalToolOption.Notification)) {
            tools.add(notificationTool(context))
        }
        if (options.contains(LocalToolOption.Share)) {
            tools.add(shareTool(context))
        }
        if (options.contains(LocalToolOption.Torch)) {
            tools.add(torchTool(context))
        }
        if (options.contains(LocalToolOption.Vibrate)) {
            tools.add(vibrateTool(context))
        }
        if (options.contains(LocalToolOption.Brightness)) {
            tools.add(getBrightnessTool(context))
            tools.add(setBrightnessTool(context))
        }
        if (options.contains(LocalToolOption.Volume)) {
            tools.add(getVolumeTool(context))
            tools.add(setVolumeTool(context))
        }
        if (options.contains(LocalToolOption.MediaPlayer)) {
            tools.add(playMediaTool(context))
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
            tools.add(tapTool())
            tools.add(longPressTool())
            tools.add(swipeTool())
            tools.add(readWindowTreeTool())
            tools.add(findNodeTool())
            tools.add(clickNodeTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.setTextTool())
            tools.add(scrollTool())
            tools.add(globalActionTool())
            tools.add(takeScreenshotTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.wakeScreenTool(context))
        }
        if (options.contains(LocalToolOption.AppLauncher)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.launchAppTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listInstalledAppsTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.openUrlTool(context))
        }
        if (options.contains(LocalToolOption.Termux)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool(context))
        }
        if (options.contains(LocalToolOption.NotificationListener)) {
            tools.add(listRecentNotificationsTool())
            tools.add(listActiveNotificationsTool())
            tools.add(dismissNotificationTool())
            tools.add(notificationActionClickTool())
            tools.add(notificationStatusTool(notificationListenerPreferences, telegramBotPreferences))
        }
        // Centralised opt-in to needsApproval. Tool factories themselves don't have to know
        // whether their op is destructive — ToolApprovalDefaults is the single source of
        // truth, and the GenerationHandler / Telegram/in-app prompt path keys off needsApproval.
        return tools.map { t ->
            if (!t.needsApproval && ToolApprovalDefaults.requiresApproval(t.name)) {
                t.copy(needsApproval = true)
            } else t
        }
    }
}
