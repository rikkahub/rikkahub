package me.rerere.rikkahub.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The real Android backend for the #187 v1 UI-automation runtime — the ONLY importer of the
 * `android.accessibility` / `android.accessibilityservice` APIs (design I10). It implements the pure
 * [AutomationBackend] seam by walking the live [AccessibilityWindowInfo] forest into the value-type
 * [RawTree] that `:automation`'s [me.rerere.automation.observe.SnapshotProjector] then projects. It
 * exposes NO write verbs and dispatches NO gestures — read-only by construction (write verbs are
 * #198).
 *
 * The service is instantiated by the Android system, not by Koin. It publishes itself as a
 * process-singleton ([instance]) on connect and clears it on teardown, so
 * [AutomationRuntimeRegistry] can hand the live, connected instance to the per-generation tool
 * factory. It is inert until the user enables it in system accessibility settings.
 *
 * Concurrency (design §6 / [AutomationBackend] kdoc): a tool's `execute` runs under
 * `Dispatchers.IO`, so [snapshotRawTree] marshals to a single dedicated service thread
 * ([serviceDispatcher]) and serializes concurrent captures with a [Mutex] — the accessibility node
 * tree must be read off one thread and every [AccessibilityNodeInfo] recycled on every path.
 *
 * Cancellation (design I9): the capture runs as a child of the *caller's* coroutine job (via
 * `withContext(serviceDispatcher)`, a plain dispatcher hop that preserves the Job — NOT a detached
 * scope). So when a kill-switch or in-app Stop cancels conversation A's generation job, A's
 * in-flight capture is torn down by structured concurrency, while conversation B's capture (a child
 * of B's job) is untouched. There is intentionally no process-global "cancel all captures" hammer:
 * a single-session stop cancelling every session's backend work was the bug. The capability guard's
 * `revoke()` denies future authorize; cancelling the owning job cancels the work already running.
 */
class AccessibilityRuntime : AccessibilityService(), AutomationBackend {

    // Monotonic per design I6/P11: bumped on every window state/content change. The core treats a
    // regressing seq as a backend bug, so this only ever increases.
    private val stateSeq = AtomicLong(0L)

    // One dedicated thread for all node-tree reads. A capture hops onto this via
    // withContext(serviceDispatcher), which preserves the caller's Job — so cancelling the caller
    // (a stopped/killed generation) cancels its own capture and no other's. Shut down on teardown.
    private val serviceExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rikkahub-a11y-snapshot").apply { isDaemon = true }
    }
    private val serviceDispatcher = serviceExecutor.asCoroutineDispatcher()

    // Serializes concurrent snapshot captures (one node-tree read at a time).
    private val captureMutex = Mutex()

    // Floating STOP kill-switch. WindowManager add/remove must run on the service main thread.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: KillSwitchOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Multi-window awareness (app + system dialogs) + the two events that advance stateSeq.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> stateSeq.incrementAndGet()
        }
    }

    override fun onInterrupt() {
        // No queued feedback to abandon: this service only reads.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        mainHandler.post {
            overlay?.hide()
            overlay = null
        }
        // Stop accepting/serving captures; in-flight captures are children of their generation jobs
        // and fail out as the service dies (every node is recycled on every path).
        serviceExecutor.shutdownNow()
        if (instance === this) instance = null
    }

    /**
     * Show the floating STOP kill-switch (design §7) and report whether it is actually displayed.
     * Called by `ChatService` (via [AutomationRuntimeRegistry] -> activation tracker) on the 0→1
     * edge — the first active automation session. [onStop] trips the kill-switch (revoke every
     * active guard + cancel their generations). A `false` return (overlay could not attach) is
     * load-bearing: the caller fails closed and revokes the lease so `ui_observe` is not exposed
     * without a reachable STOP.
     *
     * The `WindowManager` add must run on the service main thread. The activation that calls this is
     * dispatched on `AppScope`'s `Dispatchers.Main` (RikkaHubApp.kt), so the calling thread IS the
     * main thread — posting to [mainHandler] and then blocking on the result would deadlock (the
     * Runnable can only run on the now-blocked main thread). [runOnMainSync] therefore runs the
     * `WindowManager` add inline when already on the main looper, and only posts-then-blocks when
     * genuinely off-main (where blocking is safe).
     */
    fun showOverlay(onStop: () -> Unit): Boolean = runOnMainSync {
        if (overlay == null) overlay = KillSwitchOverlay(this, onStop)
        overlay?.show() == true
    }

    /** Hide the floating STOP kill-switch (design §7) on the 1→0 edge — the last session ended. */
    fun hideOverlay() {
        runOnMainSync {
            overlay?.hide()
            overlay = null
        }
    }

    /**
     * Run [block] on the service main thread, reusing the current thread when it already IS the main
     * looper (so a main-dispatched caller never self-deadlocks waiting on a Runnable that can only run
     * on the blocked main thread). Off-main callers post-then-block, which is safe. The block touches
     * `WindowManager`, which is main-thread-only.
     */
    private fun <T> runOnMainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = CompletableFuture<T>()
        mainHandler.post { result.complete(block()) }
        return result.get()
    }

    /** The package owning the active window; null when nothing is reachable (fails closed upstream). */
    val foregroundPackage: String?
        get() = rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString()
            root.recycle()
            pkg
        }

    override suspend fun snapshotRawTree(): RawTree = captureMutex.withLock {
        // serviceDispatcher (NOT a detached scope): hops onto the single service thread while keeping
        // the caller's Job, so cancelling the owning generation cancels exactly this capture (I9).
        withContext(serviceDispatcher) {
            val seq = stateSeq.get()
            val foreground = foregroundPackage ?: HOST_PACKAGE
            // getWindows() hands out live AccessibilityWindowInfo handles; recycle each after copying
            // its subtree into the value RawWindow (resource discipline — release on every path). On
            // API 33+ recycle() is a no-op, but minSdk is 26 where leaking windows is real.
            val rawWindows = windows.orEmpty().mapNotNull { window ->
                try {
                    window.toRawWindow()
                } finally {
                    @Suppress("DEPRECATION")
                    window.recycle()
                }
            }
            RawTree(stateSeq = seq, foregroundPkg = foreground, windows = rawWindows)
        }
    }

    override fun windowContentHash(stateSeq: Long): String {
        // Structural/content hash of the active window for the v2 TOCTOU close (design §5, gate
        // finding #7). Contract-only in v1 (no write verb lands), but implemented from day one so the
        // real backend ships the hook before any write verb exists. A dropped WINDOW_STATE event
        // leaves stateSeq stale-but-equal, so the v2 act-assert must compare BOTH this hash and the
        // expected seq.
        val root = rootInActiveWindow ?: return "empty:$stateSeq"
        val acc = StringBuilder()
        try {
            foldStructure(root, acc)
        } finally {
            root.recycle()
        }
        return acc.toString().hashCode().toString(16)
    }

    private fun foldStructure(node: AccessibilityNodeInfo, acc: StringBuilder) {
        acc.append(node.className ?: "?")
            .append(':')
            .append(node.text?.length ?: 0)
            .append(':')
            .append(node.childCount)
            .append('|')
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    foldStructure(child, acc)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun AccessibilityWindowInfo.toRawWindow(): RawWindow? {
        val root = this.root ?: return null // secure/inaccessible window: no content to project
        return try {
            val pkg = root.packageName?.toString() ?: return null
            RawWindow(
                pkg = pkg,
                // The a11y framework exposes NO reliable FLAG_SECURE signal on a window, so this
                // backend cannot set `secure` truthfully and reports false rather than fabricate a
                // flag it cannot read. This is a genuine platform limitation, NOT a guarantee that a
                // FLAG_SECURE window is empty: FLAG_SECURE blocks screenshots, not necessarily the
                // a11y tree, so such a window MAY still expose non-password nodes here. The defenses
                // that DO hold are node-level: password nodes are masked by SnapshotProjector (I1/P1),
                // a window with no readable content is dropped by the null-root branch above, and the
                // v1 surface is empty-by-default so nothing is projected without an explicit grant.
                // (The projector's window-level `secure` short-circuit is wired for a future backend
                // that CAN read the flag, e.g. an ADB backend; it is intentionally never true here.)
                secure = false,
                systemWindow = type == AccessibilityWindowInfo.TYPE_SYSTEM || pkg.isSystemUiPackage(),
                root = root.toRawNode(),
            )
        } finally {
            root.recycle()
        }
    }

    /** Recursive value-copy of a node subtree. Recycles every child it descends into. */
    private fun AccessibilityNodeInfo.toRawNode(): RawNode {
        val bounds = android.graphics.Rect().also { getBoundsInScreen(it) }
        val children = ArrayList<RawNode>(childCount)
        for (i in 0 until childCount) {
            getChild(i)?.let { child ->
                try {
                    children.add(child.toRawNode())
                } finally {
                    child.recycle()
                }
            }
        }
        return RawNode(
            resourceId = viewIdResourceName,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            className = className?.toString(),
            visible = isVisibleToUser,
            hasArea = !bounds.isEmpty,
            clickable = isClickable,
            editable = isEditable,
            scrollable = isScrollable,
            checkable = isCheckable,
            checked = isChecked,
            password = isPassword,
            children = children,
        )
    }

    private fun String.isSystemUiPackage(): Boolean =
        this == "com.android.systemui" || this.endsWith("packageinstaller")

    companion object {
        const val HOST_PACKAGE = "me.rerere.rikkahub"

        /**
         * The live, connected service or null. Set on [onServiceConnected], cleared on teardown.
         * `@Volatile` because the kill-switch / tool factory may read it off another thread.
         */
        @Volatile
        var instance: AccessibilityRuntime? = null
            private set
    }
}
