package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
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
        // Only expose tools that are both enabled and runnable on this device.
        // Synced preferences may keep intent ON without local permissions.
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
        if (options.contains(LocalToolOption.ScreenTime) && context.hasUsageStatsPermission()) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar) && hasCalendarPermissions(context)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        return tools
    }
}

fun hasCalendarPermissions(context: Context): Boolean {
    val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    return read && write
}

/**
 * Whether a local-tool preference can currently run on this device.
 * Preference may still be stored as enabled for sync; UI should use this for switch state.
 */
fun LocalToolOption.isEffectivelyEnabled(context: Context, enabledOptions: List<LocalToolOption>): Boolean {
    if (!enabledOptions.contains(this)) return false
    return when (this) {
        LocalToolOption.ScreenTime -> context.hasUsageStatsPermission()
        LocalToolOption.Calendar -> hasCalendarPermissions(context)
        else -> true
    }
}
