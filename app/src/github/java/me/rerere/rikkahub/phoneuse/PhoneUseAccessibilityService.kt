package me.rerere.rikkahub.phoneuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PhoneUseService"
private const val MAX_ELEMENTS = 150
private const val MAX_LABEL_CHARS = 80

// 没有自己文字的可点击容器，用里面的前几段文字当名字
private const val MAX_LABEL_PARTS = 3
private const val SCREENSHOT_RETRY_DELAY_MS = 1000L
private const val JPEG_QUALITY = 80

/**
 * Phone Use 的执行端：用户在系统无障碍设置里开启后，把自己登记到 [PhoneUseManager]。
 * 不监听无障碍事件，只在工具调用时读取窗口、派发手势。
 */
class PhoneUseAccessibilityService : AccessibilityService(), PhoneController {
    private val manager: PhoneUseManager by inject()
    private val indicator by lazy { GestureIndicator(this) }

    // 和最近一次 captureScreen 返回的元素一一对应，setText 靠它找到节点
    @Volatile
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        manager.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        manager.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        manager.detach(this)
        indicator.dismiss()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---- 读取屏幕 ----

    override fun visiblePackages(): Set<String> = targetRoots().packages()

    private fun List<AccessibilityNodeInfo>.packages(): Set<String> =
        mapNotNullTo(mutableSetOf()) { it.packageName?.toString() }

    override suspend fun captureScreen(): PhoneScreen = withContext(Dispatchers.Default) {
        val size = screenSize()
        val screenBounds = Rect(0, 0, size.x, size.y)
        val roots = targetRoots()
        val elements = mutableListOf<PhoneElement>()
        val collected = mutableListOf<AccessibilityNodeInfo>()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, absorbed: Boolean) {
            if (elements.size >= MAX_ELEMENTS) {
                truncated = true
                return
            }
            var childrenAbsorbed = absorbed
            if (node.isVisibleToUser) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val interactive = node.isClickable || node.isLongClickable || node.isEditable ||
                    node.isScrollable || node.isCheckable
                var label = node.ownLabel()
                if (label.isEmpty() && node.isClickable) {
                    label = node.descendantLabels().joinToString(" ")
                    // 文字已经算进这个容器了，子节点里的纯文字不再单独列一遍
                    childrenAbsorbed = label.isNotEmpty()
                }
                val listed = interactive || (label.isNotEmpty() && !absorbed)
                if (listed && bounds.intersect(screenBounds)) {
                    elements += PhoneElement(
                        index = elements.size,
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        label = label.clean(),
                        // 形如 com.example:id/btn_send，只留名字
                        resourceId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        focused = node.isEditable && node.isFocused,
                    )
                    collected += node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childrenAbsorbed) }
            }
        }
        roots.forEach { visit(it, absorbed = false) }
        nodes = collected

        val topPackage = roots.firstOrNull()?.packageName?.toString()
        PhoneScreen(
            packageName = topPackage,
            appLabel = topPackage?.let(::appLabel),
            packages = roots.packages(),
            width = size.x,
            height = size.y,
            keyboardVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            elements = elements,
            truncated = truncated,
        )
    }

    /**
     * 要读取的窗口根节点，最上层的在前。只看应用窗口和拿着焦点的系统窗口（比如下拉的通知栏），
     * 状态栏、导航栏、输入法的按键不列出来；本应用自己的窗口（悬浮窗）也跳过。
     */
    private fun targetRoots(): List<AccessibilityNodeInfo> {
        val roots = windows
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused)
            }
            .mapNotNull { it.root }
        // 个别系统拿不到窗口列表，退回当前活动窗口
        return roots.ifEmpty { listOfNotNull(rootInActiveWindow) }.filter { it.packageName != packageName }
    }

    private fun AccessibilityNodeInfo.ownLabel(): String =
        text?.toString()?.takeIf { it.isNotBlank() }
            ?: contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: hintText?.toString()?.takeIf { it.isNotBlank() }
            ?: ""

    private fun AccessibilityNodeInfo.descendantLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            for (i in 0 until node.childCount) {
                if (labels.size >= MAX_LABEL_PARTS) return
                val child = node.getChild(i) ?: continue
                // 可点击的子节点自己会单独列出
                if (child.isClickable) continue
                child.ownLabel().takeIf { it.isNotEmpty() }?.let(labels::add)
                collect(child)
            }
        }
        collect(this)
        return labels
    }

    private fun String.clean() = replace('\n', ' ').trim().let {
        if (it.length > MAX_LABEL_CHARS) it.take(MAX_LABEL_CHARS) + "…" else it
    }

    private fun screenSize(): Point {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return Point().also { windowManager.defaultDisplay.getRealSize(it) }
    }

    private fun appLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    // ---- 截图 ----

    override suspend fun screenshot(maxSide: Int): ByteArray {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Screenshots need Android 11 or later." }
        val bitmap = try {
            takeScreenshot()
        } catch (e: ScreenshotIntervalException) {
            // 系统限制了两次截图的最小间隔
            delay(SCREENSHOT_RETRY_DELAY_MS)
            takeScreenshot()
        }
        return withContext(Dispatchers.Default) {
            val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (ratio < 1f) {
                bitmap.scale((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
            } else {
                bitmap
            }
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                .toByteArray()
        }
    }

    private class ScreenshotIntervalException : IllegalStateException("Screenshot requested too frequently.")

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(): Bitmap = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // 硬件位图不能缩放和压缩，转成普通位图
                    val bitmap = screenshot.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    if (bitmap != null) {
                        continuation.resume(bitmap)
                    } else {
                        continuation.resumeWithException(IllegalStateException("Screenshot failed."))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resumeWithException(
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            ScreenshotIntervalException()
                        } else {
                            IllegalStateException("Screenshot failed (code $errorCode).")
                        }
                    )
                }
            },
        )
    }

    // ---- 操作 ----

    override suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        // 坐标不能为负；点击也补一段到原地的线，保证路径不为空
        val path = Path().apply {
            moveTo(fromX.coerceAtLeast(0f), fromY.coerceAtLeast(0f))
            lineTo(toX.coerceAtLeast(0f), toY.coerceAtLeast(0f))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        // 效果和手势同时开始；覆盖层不接收触摸，不会挡住手势
        withContext(Dispatchers.Main) { indicator.show(fromX, fromY, toX, toY, durationMs) }
        return suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    override suspend fun setText(elementIndex: Int?, text: String): Boolean = withContext(Dispatchers.Default) {
        val target = if (elementIndex != null) nodes.getOrNull(elementIndex) else focusedInput()
        // 模型可能选中了输入框外面的容器
        val input = target?.let { if (it.isEditable) it else it.firstEditableDescendant() }
            ?: return@withContext false
        // 拿到输入焦点，之后按回车才会落在这个输入框上
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        targetRoots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }

    private fun AccessibilityNodeInfo.firstEditableDescendant(): AccessibilityNodeInfo? {
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            if (child.isEditable) return child
            child.firstEditableDescendant()?.let { return it }
        }
        return null
    }

    override suspend fun pressKey(key: PhoneKey): Boolean = when (key) {
        PhoneKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        PhoneKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        PhoneKey.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        PhoneKey.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        PhoneKey.ENTER -> withContext(Dispatchers.Default) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Enter needs Android 11 or later. Tap the search or send button on screen instead."
            }
            focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }
    }

    // ---- 应用 ----

    override fun listApps(): List<PhoneApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    // 开启了无障碍服务的应用可以从后台启动 Activity
    override fun openApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
