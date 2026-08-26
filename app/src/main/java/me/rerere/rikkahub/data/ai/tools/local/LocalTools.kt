package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val grantStore: StorageVolumeGrantStore,
    private val safPickerBuffer: SafPickerResultBuffer,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

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
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ExternalStorage)) {
            tools.add(listStorageVolumesTool(context))
            tools.add(listGrantedDirectoriesTool(grantStore))
            tools.add(grantDirectoryAccessTool(context, grantStore, safPickerBuffer))
            tools.add(listFilesTool())
            tools.add(readFileTool())
            tools.add(writeTextFileTool())
            tools.add(writeBinaryFileTool())
            tools.add(deleteFileTool())
            tools.add(moveFileTool())
            tools.add(copyFileTool())
            tools.add(createDirectoryTool())
            tools.add(fileInfoTool())
            tools.add(findFilesTool())
        }
        if (options.contains(LocalToolOption.Termux)) {
            tools.add(termuxRunCommandTool(context))
            tools.add(termuxSessionStartTool(context))
            tools.add(termuxSessionSendTool(context))
            tools.add(termuxSessionReadTool(context))
            tools.add(termuxSessionKillTool(context))
            tools.add(termuxSessionListTool(context))
        }
        return tools
    }
}
