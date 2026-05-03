package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "RikkaAccService"
private const val GESTURE_TIMEOUT_MS = 5_000L
private const val LOG_RING_SIZE = 50

data class ActionLogEntry(
    val type: String,
    val paramsSummary: String,
    val success: Boolean,
    val timestampMs: Long,
)

class RikkaAccessibilityService : AccessibilityService() {

    private val gestureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RikkaAcc-Gesture").apply { isDaemon = true }
    }
    private val gestureHandlerThread = HandlerThread("RikkaAcc-Callback").apply { start() }
    private val gestureHandler = Handler(gestureHandlerThread.looper)

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _lastActions = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val lastActions = _lastActions.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _running.value = false
        _lastActions.value = emptyList()
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _running.value = false
        gestureHandlerThread.quitSafely()
        gestureExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty - we don't react to events; we drive the device via tool calls.
    }

    override fun onInterrupt() {
        // Required override; no-op.
    }

    fun appendLog(entry: ActionLogEntry) {
        val current = _lastActions.value
        val next = (current + entry).takeLast(LOG_RING_SIZE)
        _lastActions.value = next
    }

    /**
     * Dispatches a gesture and suspends until it completes / cancels / times out (5s).
     * Serializes through a single-thread executor so concurrent calls queue behind each other
     * (the OS rejects overlapping dispatchGesture calls).
     */
    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val gate = serializeGate.incrementAndGet()
        try {
            // Wait for our turn in the queue (serializes overlapping callers).
            while (gateCurrent.get() != gate - 1) {
                kotlinx.coroutines.delay(10)
            }
            val ok = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onCancelled(d: GestureDescription) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val dispatched = dispatchGesture(gesture, callback, gestureHandler)
                    if (!dispatched && cont.isActive) cont.resume(false)
                }
            } ?: false
            return ok
        } finally {
            gateCurrent.set(gate)
        }
    }

    fun buildTapPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    fun buildSwipePath(sx: Float, sy: Float, ex: Float, ey: Float): Path = Path().apply {
        moveTo(sx, sy)
        lineTo(ex, ey)
    }

    /**
     * Walk the active window's node tree depth-first. `filter` decides whether to emit a node;
     * traversal stops once `cap` nodes have been emitted. Returns (emitted, totalSeen, truncated).
     */
    fun traverseTree(
        root: AccessibilityNodeInfo,
        filter: (AccessibilityNodeInfo, depth: Int) -> Boolean,
        cap: Int,
        emit: (AccessibilityNodeInfo, depth: Int, traversalIndex: Int) -> Unit,
    ): Triple<Int, Int, Boolean> {
        var emitted = 0
        var seen = 0
        var truncated = false
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (truncated) return
            seen++
            if (filter(n, depth)) {
                emit(n, depth, seen)
                emitted++
                if (emitted >= cap) {
                    truncated = true
                    return
                }
            }
            for (i in 0 until n.childCount) {
                if (truncated) return
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return Triple(emitted, seen, truncated)
    }

    /**
     * Walks up the parent chain looking for a clickable node. Returns the original if it's
     * already clickable, or the nearest clickable ancestor, or null if none exists.
     */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /**
     * Captures a screenshot of the given display. Suspends until callback fires; returns the
     * Bitmap (or null on failure). Caller must recycle when done.
     */
    suspend fun captureScreenshot(displayId: Int): ScreenshotOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotOutcome.Failure("api_too_low")
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                gestureExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (cont.isActive) {
                                cont.resume(
                                    if (bmp != null) ScreenshotOutcome.Success(bmp)
                                    else ScreenshotOutcome.Failure("bitmap_decode_failed")
                                )
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(ScreenshotOutcome.Failure("exception:${t.message}"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_access"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal_error"
                            else -> "error_code_$errorCode"
                        }
                        if (cont.isActive) cont.resume(ScreenshotOutcome.Failure(reason))
                    }
                }
            )
        }
    }

    sealed class ScreenshotOutcome {
        data class Success(val bitmap: Bitmap) : ScreenshotOutcome()
        data class Failure(val reason: String) : ScreenshotOutcome()
    }

    companion object {
        @Volatile
        var instance: RikkaAccessibilityService? = null
            private set

        // Used by dispatchGestureAsync to serialize overlapping callers without blocking threads.
        private val serializeGate = AtomicInteger(0)
        private val gateCurrent = AtomicInteger(0)
    }
}
