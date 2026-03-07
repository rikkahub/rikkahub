package me.rerere.rikkahub.data.ai.tools

import androidx.annotation.StringRes
import me.rerere.rikkahub.R

data class LocalToolMeta(
    val option: LocalToolOption,
    val toolName: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
)

object LocalToolCatalog {
    val all: List<LocalToolMeta> = listOf(
        LocalToolMeta(
            option = LocalToolOption.JavascriptEngine,
            toolName = "eval_javascript",
            titleRes = R.string.assistant_page_local_tools_javascript_engine_title,
            descRes = R.string.assistant_page_local_tools_javascript_engine_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.TimeInfo,
            toolName = "get_time_info",
            titleRes = R.string.assistant_page_local_tools_time_info_title,
            descRes = R.string.assistant_page_local_tools_time_info_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.Clipboard,
            toolName = "clipboard_tool",
            titleRes = R.string.assistant_page_local_tools_clipboard_title,
            descRes = R.string.assistant_page_local_tools_clipboard_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.TermuxExec,
            toolName = "termux_exec",
            titleRes = R.string.assistant_page_local_tools_termux_exec_title,
            descRes = R.string.assistant_page_local_tools_termux_exec_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.TermuxPython,
            toolName = "termux_python",
            titleRes = R.string.assistant_page_local_tools_termux_python_title,
            descRes = R.string.assistant_page_local_tools_termux_python_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.Tts,
            toolName = "text_to_speech",
            titleRes = R.string.assistant_page_local_tools_tts_title,
            descRes = R.string.assistant_page_local_tools_tts_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.AskUser,
            toolName = "ask_user",
            titleRes = R.string.assistant_page_local_tools_ask_user_title,
            descRes = R.string.assistant_page_local_tools_ask_user_desc,
        ),
    )

    private val optionToMeta: Map<LocalToolOption, LocalToolMeta> = all.associateBy { it.option }

    val options: List<LocalToolOption> = all.map { it.option }

    fun get(option: LocalToolOption): LocalToolMeta {
        return checkNotNull(optionToMeta[option]) { "Unknown local tool option: $option" }
    }
}
