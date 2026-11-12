package me.rerere.rikkahub.data.ai.tools.local

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.phoneuse.PhoneController
import me.rerere.rikkahub.phoneuse.PhoneElement
import me.rerere.rikkahub.phoneuse.PhoneKey
import me.rerere.rikkahub.phoneuse.PhoneScreen
import me.rerere.rikkahub.phoneuse.PhoneUseManager

const val PHONE_USE_TOOL_NAME = "phone_use"

// 坐标统一用 0-1000 的网格：截图发给模型前会被缩放，像素坐标对不上
private const val GRID = 1000

// 悬浮窗收到通知后要过一帧才真正从屏幕上消失
private const val OVERLAY_HIDE_MS = 120L

// 操作之后等界面稳定再读屏幕，结果直接带回新界面，省掉模型再调一次 observe
private const val SETTLE_MS = 800L
private const val OPEN_APP_SETTLE_MS = 1500L
private const val TAP_MS = 60L
private const val LONG_PRESS_MS = 700L
private const val SWIPE_MS = 350L
private const val SCREENSHOT_MAX_SIDE = 1568

/**
 * 不允许 AI 操作的应用：支付和银行类
 * 不能打开，从别的应用跳转进去后也不能读屏和操作，只能返回或回到桌面
 */
internal val PHONE_USE_BLOCKED_APPS = setOf(
    "com.eg.android.AlipayGphone", // 支付宝
    "com.unionpay", // 云闪付
    "com.tencent.mm", // 微信
    "com.paypal.android.p2pmobile", // PayPal
    "com.google.android.apps.walletnfcrel", // Google Wallet
    "com.google.android.apps.nbu.paisa.user", // Google Pay
    "com.icbc", // 工商银行
    "com.chinamworld.main", // 建设银行
    "com.android.bankabc", // 农业银行
    "com.chinamworld.bocmbci", // 中国银行
    "com.bankcomm.Bankcomm", // 交通银行
    "cmb.pb", // 招商银行
    "com.yitong.mbank.psbc", // 邮储银行
)

private val ACTIONS = listOf(
    "observe", "screenshot", "tap", "long_press", "swipe", "scroll", "type", "press", "open_app", "list_apps", "wait",
)

/** 悬浮窗和通知里显示当前在做哪个动作；参数还在流式输出时解析不出来，返回 null */
fun phoneUseActionOf(input: String): String? = runCatching {
    Json.parseToJsonElement(input).jsonObject["action"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

/**
 * [saveScreenshot] 把 JPEG 存成聊天文件并返回地址；[isSelfInForeground] 判断本应用是否在前台，单测里替换。
 */
internal fun buildPhoneUseTool(
    ownPackageName: String,
    manager: PhoneUseManager,
    saveScreenshot: (ByteArray) -> String,
    isFloatingWindowOn: () -> Boolean,
    isSelfInForeground: suspend () -> Boolean = ::isAppInForeground,
): Tool {
    val session = PhoneUseSession(ownPackageName, manager, saveScreenshot, isFloatingWindowOn, isSelfInForeground)
    return Tool(
        name = PHONE_USE_TOOL_NAME,
        description = """
            Operate the user's Android phone: read the screen, tap, swipe, type and open apps.
            Workflow: open_app (or press home) first, then observe to get the on-screen elements, then act.
            Every action that changes the screen returns the new element list, so you rarely need to call observe again.
            Prefer targeting elements by their index from the latest element list; indexes are only valid for that list.
            Coordinates are on a $GRID x $GRID grid: (0,0) is the top-left of the screen and ($GRID,$GRID) the bottom-right,
            in both the element list and screenshots.
            Use screenshot only when the element list is not enough (images, canvas, games, custom views).
            This app itself cannot be operated; leave it with open_app or press home before acting.
            Payment and banking apps are blocked: you cannot open, read or operate them. If a task ends up in one,
            press back or home and tell the user to finish that step themselves.
            Stop and ask the user before irreversible steps such as paying, sending messages or deleting data,
            unless they already told you to do exactly that.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { ACTIONS.forEach { add(it) } })
                        put(
                            "description",
                            "observe: list on-screen elements. screenshot: capture the screen as an image. " +
                                "tap / long_press: on 'element' or at 'x','y'. " +
                                "swipe: drag from 'x','y' to 'to_x','to_y'. " +
                                "scroll: scroll the content in 'direction', optionally inside 'element'. " +
                                "type: replace the text of an input field ('element', or the focused field) with 'text'. " +
                                "press: press 'key'. open_app: launch an app by 'app' (name or package). " +
                                "list_apps: list installed apps. wait: wait 'seconds' for loading."
                        )
                    })
                    put("element", buildJsonObject {
                        put("type", "integer")
                        put("description", "Element index from the latest element list")
                    })
                    put("x", buildJsonObject { put("type", "number"); put("description", "0-$GRID") })
                    put("y", buildJsonObject { put("type", "number"); put("description", "0-$GRID") })
                    put("to_x", buildJsonObject { put("type", "number"); put("description", "0-$GRID, swipe end") })
                    put("to_y", buildJsonObject { put("type", "number"); put("description", "0-$GRID, swipe end") })
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { listOf("up", "down", "left", "right").forEach { add(it) } })
                        put("description", "For scroll: 'down' reveals content further down the page")
                    })
                    put("text", buildJsonObject { put("type", "string"); put("description", "Text for type") })
                    put("submit", buildJsonObject {
                        put("type", "boolean")
                        put("description", "For type: press enter afterwards (search, send)")
                    })
                    put("key", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { PhoneKey.entries.forEach { add(it.name.lowercase()) } })
                    })
                    put("app", buildJsonObject {
                        put("type", "string")
                        put("description", "App name or package name for open_app")
                    })
                    put("seconds", buildJsonObject { put("type", "number"); put("description", "For wait, up to 10") })
                },
                required = listOf("action"),
            )
        },
        execute = { session.execute(it.jsonObject) },
    )
}

private suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Main) {
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

private class PhoneUseSession(
    private val ownPackageName: String,
    private val manager: PhoneUseManager,
    private val saveScreenshot: (ByteArray) -> String,
    private val isFloatingWindowOn: () -> Boolean,
    private val isSelfInForeground: suspend () -> Boolean,
) {
    // 模型可能一次发出多个调用，手势和元素编号都依赖顺序
    private val mutex = Mutex()

    // 元素编号和坐标换算都以模型最后看到的那份为准
    private var lastScreen: PhoneScreen? = null

    suspend fun execute(params: JsonObject): List<UIMessagePart> = mutex.withLock {
        // 开启工具时已经要求过，这里防的是之后又把悬浮窗或它的权限关掉
        check(isFloatingWindowOn()) {
            "The background floating window is turned off, so the user cannot watch or stop you. " +
                "Ask them to turn it on in Settings > Notifications (and grant the overlay permission) first."
        }
        val controller = manager.controller.value ?: error(
            "Phone Use is not enabled. Ask the user to turn on \"RikkaHub Phone Use\" in the system accessibility settings."
        )
        when (val action = params.string("action") ?: error("action is required")) {
            "list_apps" -> text(
                controller.listApps()
                    .filterNot { it.packageName in PHONE_USE_BLOCKED_APPS }
                    .joinToString("\n") { "${it.label} (${it.packageName})" }
            )

            "open_app" -> {
                val app = resolveApp(controller, params.string("app") ?: error("app is required"))
                check(app.packageName != ownPackageName) { "This app itself cannot be operated." }
                check(app.packageName !in PHONE_USE_BLOCKED_APPS) {
                    "${app.label} is blocked for safety. Tell the user to do this step themselves."
                }
                check(controller.openApp(app.packageName)) { "Failed to open ${app.label}" }
                delay(OPEN_APP_SETTLE_MS)
                text("Opened ${app.label}.\n" + describeCurrent(controller))
            }

            "wait" -> {
                delay(((params.number("seconds") ?: 2.0).coerceIn(0.5, 10.0) * 1000).toLong())
                text(describeCurrent(controller))
            }

            "press" -> {
                val key = params.string("key")?.let { name -> PhoneKey.entries.find { it.name.equals(name, true) } }
                    ?: error("key must be one of ${PhoneKey.entries.joinToString { it.name.lowercase() }}")
                when (key) {
                    // 回到桌面是离开本应用和被禁应用的办法
                    PhoneKey.HOME -> {}
                    // 返回也能离开被禁的应用，但本应用在前台时会落在本应用上
                    PhoneKey.BACK -> ensureNotSelf()
                    else -> ensureOperable(controller)
                }
                act(controller, "press ${key.name.lowercase()}") { controller.pressKey(key) }
            }

            "observe" -> {
                ensureOperable(controller)
                text(describeCurrent(controller))
            }

            "screenshot" -> {
                ensureOperable(controller)
                val screen = capture(controller)
                val bytes = hidingOverlays { controller.screenshot(SCREENSHOT_MAX_SIDE) }
                listOf(
                    UIMessagePart.Image(url = saveScreenshot(bytes)),
                    UIMessagePart.Text(
                        "Screenshot of ${screen.appName()}. Locate points on a $GRID x $GRID grid: " +
                            "x = horizontal position / image width * $GRID, y = vertical position / image height * $GRID."
                    ),
                )
            }

            "tap", "long_press" -> {
                ensureOperable(controller)
                val (x, y) = targetPoint(controller, params)
                val duration = if (action == "tap") TAP_MS else LONG_PRESS_MS
                act(controller, action) { controller.gesture(x, y, x, y, duration) }
            }

            "swipe" -> {
                ensureOperable(controller)
                val screen = lastScreen ?: capture(controller)
                val (fromX, fromY) = targetPoint(controller, params)
                val toX = screen.gridToX(params.number("to_x") ?: error("to_x is required"))
                val toY = screen.gridToY(params.number("to_y") ?: error("to_y is required"))
                act(controller, action) { controller.gesture(fromX, fromY, toX, toY, SWIPE_MS) }
            }

            "scroll" -> {
                ensureOperable(controller)
                val screen = lastScreen ?: capture(controller)
                val area = params.int("element")?.let { screen.element(it) }
                val left = area?.left ?: 0
                val top = area?.top ?: 0
                val width = area?.let { it.right - it.left } ?: screen.width
                val height = area?.let { it.bottom - it.top } ?: screen.height
                val centerX = left + width / 2f
                val centerY = top + height / 2f
                // 手指移动方向和内容滚动方向相反；只滑中间一段，避开屏幕边缘的系统手势
                val dx = width * 0.3f
                val dy = height * 0.3f
                val (fromX, fromY, toX, toY) = when (params.string("direction") ?: "down") {
                    "down" -> listOf(centerX, centerY + dy, centerX, centerY - dy)
                    "up" -> listOf(centerX, centerY - dy, centerX, centerY + dy)
                    "right" -> listOf(centerX + dx, centerY, centerX - dx, centerY)
                    "left" -> listOf(centerX - dx, centerY, centerX + dx, centerY)
                    else -> error("direction must be one of up, down, left, right")
                }
                act(controller, action) { controller.gesture(fromX, fromY, toX, toY, SWIPE_MS) }
            }

            "type" -> {
                ensureOperable(controller)
                val input = params.string("text") ?: error("text is required")
                val element = params.int("element")?.also { (lastScreen ?: capture(controller)).element(it) }
                act(controller, action) {
                    controller.setText(element, input) &&
                        (params.boolean("submit") != true || controller.pressKey(PhoneKey.ENTER))
                }
            }

            else -> error("unknown action: $action, must be one of $ACTIONS")
        }
    }

    /** 执行一个会改变界面的动作，返回执行后的新界面 */
    private suspend fun act(
        controller: PhoneController,
        name: String,
        block: suspend () -> Boolean,
    ): List<UIMessagePart> {
        val done = hidingOverlays { block() }
        if (!done) {
            return text("$name failed: the system rejected it. Observe the screen and try another way.")
        }
        delay(SETTLE_MS)
        return text("$name done.\n" + describeCurrent(controller))
    }

    private suspend fun <T> hidingOverlays(block: suspend () -> T): T {
        manager.setActing(true)
        try {
            delay(OVERLAY_HIDE_MS)
            return block()
        } finally {
            manager.setActing(false)
        }
    }

    private suspend fun capture(controller: PhoneController): PhoneScreen =
        controller.captureScreen().also { lastScreen = it }

    // 本应用在前台时不操作：模型不能替用户点自己的工具审批，也不该改动应用设置
    private suspend fun ensureNotSelf() {
        check(!isSelfInForeground()) {
            "This app is in the foreground and cannot be operated. Use open_app or press home first."
        }
    }

    // 每次操作前都看一眼屏幕上有谁：被禁的应用可能是从别的应用跳转进来的（比如购物应用拉起支付）。
    // 要看所有可见窗口：分屏或下拉通知栏时最上层是别的应用，被禁应用的界面照样会被读到、点到、截进图里
    private suspend fun ensureOperable(controller: PhoneController) {
        ensureNotSelf()
        controller.visiblePackages().blocked()?.let { error(blockedMessage(it)) }
    }

    /** 读取并描述当前界面；到了被禁的应用里不把界面内容交给模型 */
    private suspend fun describeCurrent(controller: PhoneController): String {
        val screen = capture(controller)
        return screen.packages.blocked()?.let(::blockedMessage) ?: describe(screen)
    }

    private fun Set<String>.blocked(): String? = firstOrNull { it in PHONE_USE_BLOCKED_APPS }

    private fun blockedMessage(packageName: String) =
        "$packageName is now on screen and is blocked for safety: you cannot read or operate anything " +
            "while it is visible. Press back or home, and tell the user to finish this step themselves."

    private suspend fun targetPoint(controller: PhoneController, params: JsonObject): Pair<Float, Float> {
        val screen = lastScreen ?: capture(controller)
        params.int("element")?.let { index ->
            val element = screen.element(index)
            return (element.left + element.right) / 2f to (element.top + element.bottom) / 2f
        }
        val x = params.number("x") ?: error("element or x,y is required")
        val y = params.number("y") ?: error("element or x,y is required")
        return screen.gridToX(x) to screen.gridToY(y)
    }

    private fun resolveApp(controller: PhoneController, query: String) = controller.listApps().let { apps ->
        apps.find { it.packageName == query }
            ?: apps.find { it.label.equals(query, ignoreCase = true) }
            ?: apps.find { it.label.contains(query, ignoreCase = true) }
            ?: error("App not found: $query. Use list_apps to see installed apps.")
    }
}

private fun text(text: String) = listOf(UIMessagePart.Text(text))

private fun PhoneScreen.element(index: Int): PhoneElement =
    elements.getOrNull(index) ?: error("Element $index is not in the latest element list. Observe the screen again.")

private fun PhoneScreen.gridToX(value: Double) = (value.coerceIn(0.0, GRID.toDouble()) / GRID * width).toFloat()

private fun PhoneScreen.gridToY(value: Double) = (value.coerceIn(0.0, GRID.toDouble()) / GRID * height).toFloat()

private fun PhoneScreen.appName() = when {
    packageName == null -> "unknown app"
    appLabel != null -> "$appLabel ($packageName)"
    else -> packageName
}

internal fun describe(screen: PhoneScreen): String = buildString {
    appendLine("App: ${screen.appName()}")
    if (screen.keyboardVisible) appendLine("Keyboard: visible")
    if (screen.elements.isEmpty()) {
        append("No elements found. The screen may still be loading (wait), or needs a screenshot.")
        return@buildString
    }
    appendLine("Elements as [index] type \"label\" (or #resource-id when unlabeled) flags @(x,y):")
    screen.elements.forEach { element ->
        append("[${element.index}] ${element.className}")
        if (element.label.isNotEmpty()) {
            append(" \"${element.label}\"")
        } else {
            // 没有文字的图标按钮只剩资源 ID 能说明用途；有文字时不带，省些篇幅
            element.resourceId?.let { append(" #$it") }
        }
        if (element.clickable) append(" clickable")
        if (element.editable) append(" editable")
        if (element.scrollable) append(" scrollable")
        element.checked?.let { append(if (it) " checked" else " unchecked") }
        if (element.focused) append(" focused")
        val x = (element.left + element.right) / 2 * GRID / screen.width.coerceAtLeast(1)
        val y = (element.top + element.bottom) / 2 * GRID / screen.height.coerceAtLeast(1)
        appendLine(" @($x,$y)")
    }
    if (screen.truncated) append("More elements were omitted; scroll or take a screenshot to see the rest.")
}.trimEnd()

// 模型有时把数字写成字符串，按内容解析
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String) = string(key)?.toDoubleOrNull()

private fun JsonObject.int(key: String) = number(key)?.toInt()

private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()
