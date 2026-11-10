package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Hourglass
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.ScrollVertical
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.SwipeRight01
import me.rerere.hugeicons.stroke.Tap01
import me.rerere.hugeicons.stroke.Touch01
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PHONE_USE_TOOL_NAME

/**
 * Phone Use: 一轮里会连续调用很多次, 标题和图标按 action 区分, 并带上该动作的关键参数
 *
 * 输出是给模型看的界面元素文本 (不是 JSON), 详情沿用默认渲染
 */
object PhoneUseToolUI : ToolUIRenderer {
    private const val TEXT_MAX_CHARS = 40

    override val toolName: String = PHONE_USE_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector =
        when (context.arguments.getStringContent("action")) {
            "observe" -> HugeIcons.View
            "screenshot" -> HugeIcons.Camera01
            "tap" -> HugeIcons.Tap01
            "long_press" -> HugeIcons.Touch01
            "swipe" -> HugeIcons.SwipeRight01
            "scroll" -> HugeIcons.ScrollVertical
            "type" -> HugeIcons.Keyboard
            "open_app" -> HugeIcons.Rocket01
            "list_apps" -> HugeIcons.GridView
            "wait" -> HugeIcons.Hourglass
            else -> HugeIcons.SmartPhone01
        }

    @Composable
    override fun title(context: ToolUIContext): String {
        val args = context.arguments
        // 参数还在流式输出, 或模型给了未知动作/参数时用通用标题
        val generic = stringResource(R.string.chat_message_tool_call_generic, toolName)
        return when (args.getStringContent("action")) {
            "observe" -> stringResource(R.string.chat_message_tool_phone_use_observe)
            "screenshot" -> stringResource(R.string.chat_message_tool_phone_use_screenshot)
            "tap" -> target(context)
                ?.let { stringResource(R.string.chat_message_tool_phone_use_tap_target, it) }
                ?: stringResource(R.string.chat_message_tool_phone_use_tap)

            "long_press" -> target(context)
                ?.let { stringResource(R.string.chat_message_tool_phone_use_long_press_target, it) }
                ?: stringResource(R.string.chat_message_tool_phone_use_long_press)

            "swipe" -> {
                val from = point(context, "x", "y")
                val to = point(context, "to_x", "to_y")
                if (from != null && to != null) {
                    stringResource(R.string.chat_message_tool_phone_use_swipe_path, from, to)
                } else {
                    stringResource(R.string.chat_message_tool_phone_use_swipe)
                }
            }

            // 不传 direction 时工具按 down 执行
            "scroll" -> when (args.getStringContent("direction") ?: "down") {
                "up" -> stringResource(R.string.chat_message_tool_phone_use_scroll_up)
                "down" -> stringResource(R.string.chat_message_tool_phone_use_scroll_down)
                "left" -> stringResource(R.string.chat_message_tool_phone_use_scroll_left)
                "right" -> stringResource(R.string.chat_message_tool_phone_use_scroll_right)
                else -> generic
            }

            "type" -> args.getStringContent("text")
                ?.let { if (it.length > TEXT_MAX_CHARS) it.take(TEXT_MAX_CHARS) + "…" else it }
                ?.let { stringResource(R.string.chat_message_tool_phone_use_type_text, it) }
                ?: stringResource(R.string.chat_message_tool_phone_use_type)

            "press" -> when (args.getStringContent("key")?.lowercase()) {
                "back" -> stringResource(R.string.chat_message_tool_phone_use_press_back)
                "home" -> stringResource(R.string.chat_message_tool_phone_use_press_home)
                "recents" -> stringResource(R.string.chat_message_tool_phone_use_press_recents)
                "notifications" -> stringResource(R.string.chat_message_tool_phone_use_press_notifications)
                "enter" -> stringResource(R.string.chat_message_tool_phone_use_press_enter)
                else -> generic
            }

            "open_app" -> args.getStringContent("app")
                ?.let { stringResource(R.string.chat_message_tool_phone_use_open_app_name, it) }
                ?: stringResource(R.string.chat_message_tool_phone_use_open_app)

            "list_apps" -> stringResource(R.string.chat_message_tool_phone_use_list_apps)
            // 不传 seconds 时工具等 2 秒
            "wait" -> stringResource(
                R.string.chat_message_tool_phone_use_wait,
                args.getStringContent("seconds")?.trimNumber() ?: "2",
            )

            else -> generic
        }
    }

    /** 点击目标: 优先元素编号, 否则网格坐标 */
    private fun target(context: ToolUIContext): String? =
        context.arguments.getStringContent("element")?.let { "#${it.trimNumber()}" }
            ?: point(context, "x", "y")

    private fun point(context: ToolUIContext, xKey: String, yKey: String): String? {
        val x = context.arguments.getStringContent(xKey) ?: return null
        val y = context.arguments.getStringContent(yKey) ?: return null
        return "(${x.trimNumber()}, ${y.trimNumber()})"
    }

    // 模型常把整数写成 500.0
    private fun String.trimNumber(): String = removeSuffix(".0")
}
