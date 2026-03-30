package me.rerere.rikkahub.data.ai.tools

import androidx.annotation.StringRes
import me.rerere.rikkahub.R

data class LocalToolMeta(
    val option: LocalToolOption,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
)

object LocalToolCatalog {
    val all: List<LocalToolMeta> = listOf(
        LocalToolMeta(
            option = LocalToolOption.JavascriptEngine,
            titleRes = R.string.assistant_page_local_tools_javascript_engine_title,
            descRes = R.string.assistant_page_local_tools_javascript_engine_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.TimeInfo,
            titleRes = R.string.assistant_page_local_tools_time_info_title,
            descRes = R.string.assistant_page_local_tools_time_info_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.Clipboard,
            titleRes = R.string.assistant_page_local_tools_clipboard_title,
            descRes = R.string.assistant_page_local_tools_clipboard_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.TermuxExec,
            titleRes = R.string.assistant_page_local_tools_termux_exec_title,
            descRes = R.string.assistant_page_local_tools_termux_exec_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.TermuxPython,
            titleRes = R.string.assistant_page_local_tools_termux_python_title,
            descRes = R.string.assistant_page_local_tools_termux_python_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.Tts,
            titleRes = R.string.assistant_page_local_tools_tts_title,
            descRes = R.string.assistant_page_local_tools_tts_desc,
        ),
        LocalToolMeta(
            option = LocalToolOption.AskUser,
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
