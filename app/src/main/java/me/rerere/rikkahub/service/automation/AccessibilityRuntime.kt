package me.rerere.rikkahub.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    // Settle signal for the act path (#198 slice 8, design D3/P13). Each window state/content event
    // completes-then-replaces this deferred; awaitSettle parks on it for the quiet window, so the
    // ONLINE settle is an event-stream debounce, NEVER a fixed sleep. Starts already-armed (a fresh
    // deferred) so the first awaitSettle has something to wait on. Atomic because the event callback
    // and the act coroutine touch it from different threads.
    private val settleSignal = AtomicReference(CompletableDeferred<Unit>())

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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                stateSeq.incrementAndGet()
                // Pulse the settle signal: install a fresh deferred and complete the old one, so an
                // awaitSettle parked on the previous deferred wakes and a new arrival resets its quiet
                // window. getAndSet is atomic against the act coroutine's waiter (design D3/P13).
                settleSignal.getAndSet(CompletableDeferred()).complete(Unit)
            }
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
            // Capture the active window's package AND the TOCTOU content hash from ONE read of
            // rootInActiveWindow, inside this same locked frame as the window walk — so the grounding's
            // nodes and its token describe one instant (gate finding: the token must not be built by a
            // SECOND live read). The hash definition mirrors windowContentHash (active window only) so
            // the act-assert's live re-read compares like-for-like.
            val (foreground, contentHash) = rootInActiveWindow?.let { root ->
                try {
                    val acc = StringBuilder()
                    foldStructure(root, acc)
                    (root.packageName?.toString() ?: HOST_PACKAGE) to acc.toString().hashCode().toString(16)
                } finally {
                    root.recycle()
                }
            } ?: (HOST_PACKAGE to "empty:$seq")
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
            RawTree(stateSeq = seq, foregroundPkg = foreground, windows = rawWindows, contentHash = contentHash)
        }
    }

    override fun windowContentHash(stateSeq: Long): String {
        // Structural/content hash of the active window for the act-path TOCTOU close (design §5, gate
        // finding #7). Now live: AutomationCore.act's assert compares BOTH this hash AND the expected
        // seq before any dispatch (#198 slice 8) — a dropped WINDOW_STATE event leaves stateSeq
        // stale-but-equal, and the hash is what catches it. Pure metadata read of the live tree, no
        // capture; safe outside the guard (S2 protects snapshotRawTree/perform, not this).
        val root = rootInActiveWindow ?: return "empty:$stateSeq"
        val acc = StringBuilder()
        try {
            foldStructure(root, acc)
        } finally {
            root.recycle()
        }
        return acc.toString().hashCode().toString(16)
    }

    override fun currentStateSeq(): Long = stateSeq.get()

    /**
     * Block until the UI settles after an act, or a hard cap elapses (design §1 step 6 / D3 / P13) —
     * the ONLINE form of [me.rerere.automation.act.SettlePolicy]: a quiet-window debounce over the
     * window state/content events the service already handles, NEVER a fixed sleep. Each event pulses
     * [settleSignal]; we wait up to [SETTLE_QUIET_WINDOW_MS] for the NEXT event — if none arrives the
     * window is quiet and we return; if one does we loop and wait again. The whole wait is bounded by
     * [SETTLE_HARD_CAP_MS]: reaching the cap is a NORMAL terminal ("settled enough"), the one place a
     * timeout is not a failure (design D3) — so we catch only that outer cap and return, while any
     * other cancellation (a revoke tearing the act down via the owning Job) propagates untouched.
     */
    override suspend fun awaitSettle() {
        try {
            withTimeout(SETTLE_HARD_CAP_MS) {
                while (true) {
                    val pending = settleSignal.get()
                    // No event within the quiet window ⇒ settled. withTimeoutOrNull returns null on the
                    // quiet-window timeout (the common, expected path) and Unit when an event pulses the
                    // signal first (reset the quiet window and loop).
                    if (withTimeoutOrNull(SETTLE_QUIET_WINDOW_MS) { pending.await() } == null) return@withTimeout
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Hard cap reached: the screen is still churning but we have waited long enough — return
            // normally so the act path proceeds to its re-snapshot (design D3). This is NOT swallowing
            // a real error: the cap IS the terminal. The act success is the re-snapshot, not settle.
        }
    }

    /**
     * Dispatch a write action against the live UI (#198 slice 8, design §1 step 5 / D1). Runs on the
     * single service thread (like [snapshotRawTree]) via [serviceDispatcher] — which preserves the
     * caller's Job, so a revoke/Stop cancelling the owning generation tears this down in flight (I9 /
     * I-act-10). [captureMutex] serializes it against concurrent captures (one node-tree walk at a
     * time). The boolean is a best-effort dispatch ack only; act success is the core's post-act
     * re-snapshot (design D4), never this return.
     *
     *  - [PerformAction.Global] → [performGlobalAction] (BACK/HOME/RECENTS), no node target.
     *  - [PerformAction.Node] → re-walk the live windows in the EXACT [SnapshotProjector] projection
     *    order to the tid-th projected node and perform the scroll on it (coordinate-free — a resolved
     *    node, not a screen point). Out-of-range tid ⇒ false (the core treats dispatch as a best-effort
     *    ack; the re-snapshot is the ground truth).
     *  - [PerformAction.SetText] (#198 slice 9) → the SAME carried-stateSeq re-check + projection-order
     *    walk as [PerformAction.Node], but the leaf op is [AccessibilityNodeInfo.ACTION_SET_TEXT] with
     *    the text [Bundle] (still a resolved node, never a coordinate). The headline input-sink guard
     *    (password/system-UI DENY) already ran in the core before this is ever dispatched.
     */
    override suspend fun perform(action: PerformAction): Boolean = captureMutex.withLock {
        withContext(serviceDispatcher) {
            when (action) {
                is PerformAction.Global -> performGlobalAction(
                    when (action.nav) {
                        GlobalNav.BACK -> GLOBAL_ACTION_BACK
                        GlobalNav.HOME -> GLOBAL_ACTION_HOME
                        GlobalNav.RECENTS -> GLOBAL_ACTION_RECENTS
                    },
                )

                is PerformAction.Node -> {
                    // Re-verify the carried stateSeq at dispatch, atomically with the walk (under the
                    // same captureMutex + serviceDispatcher frame). The core's pre-dispatch assert
                    // (currentStateSeq + windowContentHash) is necessary but not load-bearing on its
                    // own: a WINDOW_STATE/CONTENT event between that assert and this re-walk would make
                    // performOnProjectedNode scroll the tid-th node of a NEWER tree than the one
                    // asserted (I-act-1 / MR3). The carried action.stateSeq is the freshness token; a
                    // mismatch means the grounding moved under us, so do NOT dispatch — return false
                    // (the documented best-effort no-op ack; the core's re-snapshot is ground truth, D4).
                    if (stateSeq.get() != action.stateSeq) {
                        false
                    } else {
                        val actionId = when (action.kind) {
                            NodeActionKind.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            NodeActionKind.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        }
                        performOnProjectedNode(action.tid) { it.performAction(actionId) }
                    }
                }

                // Slice 9 (the input sink): same carried-stateSeq re-check and same projection-order
                // walk as Node, but the per-node operation is ACTION_SET_TEXT with the text payload
                // (a node operation, never a coordinate). performOnProjectedNode owns the walk + node/
                // window recycling discipline; only the leaf operation differs.
                is PerformAction.SetText -> {
                    if (stateSeq.get() != action.stateSeq) {
                        false
                    } else {
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                action.text,
                            )
                        }
                        performOnProjectedNode(action.tid) {
                            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-walk the live window forest in the SAME projection order the snapshot used and run [perform]
     * on the [tid]-th projected node. The walk MUST mirror [SnapshotProjector] exactly: visible
     * windows (host excluded) in `windows`-order, each pre-order DFS, a node counted iff
     * `(isVisibleToUser && !boundsInScreen.isEmpty) || hasId || hasText` — the same predicate
     * [SnapshotProjector.isTarget] applies over the same fields [toRawNode] reads. Drift between this
     * predicate and the projector's is exactly what slice 12's instrumented parity test guards.
     *
     * [perform] is the node operation the caller wants on the resolved node — a scroll
     * ([AccessibilityNodeInfo.performAction] with a scroll action id) or the slice-9 set_text
     * ([AccessibilityNodeInfo.ACTION_SET_TEXT] with a text [Bundle]). Parameterizing the leaf op keeps
     * the projection-order walk + node/window recycling discipline SHARED across every act verb, so a
     * new verb adds a lambda, not a second walk.
     *
     * Resource discipline: every [AccessibilityNodeInfo] and [AccessibilityWindowInfo] is recycled on
     * EVERY path. The op runs INSIDE the walk (the frame that owns the node), so no live handle escapes
     * the walk — that removes the double-recycle the "return a live node" shape invited when the match
     * is the window root. The carried `stateSeq` is re-verified by [perform]'s caller immediately
     * before this walk (atomically under the same lock), so the tree this walks is the one the core
     * asserted. Out-of-range tid ⇒ false (the core treats dispatch as a best-effort ack).
     */
    private fun performOnProjectedNode(tid: Int, perform: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val cursor = intArrayOf(0) // running projected-node index, mutated across the recursive walk
        for (window in windows.orEmpty()) {
            try {
                val root = window.root ?: continue // secure/inaccessible window: skip (matches toRawWindow)
                try {
                    if (root.packageName?.toString() == HOST_PACKAGE) continue // host excluded (projector P2)
                    walkAndPerform(root, tid, cursor, perform)?.let { return it }
                } finally {
                    root.recycle()
                }
            } finally {
                @Suppress("DEPRECATION")
                window.recycle()
            }
        }
        return false // tid out of range: best-effort dispatch ack only (the re-snapshot is ground truth)
    }

    /**
     * Pre-order DFS mirroring [SnapshotProjector.collect]: count [node] as a projected target iff
     * [isProjectedTarget], running [perform] on it when its index equals [tid]; then descend into
     * every child (incl. non-projected containers, so a projected descendant is not skipped —
     * projector P4). Returns the [perform] result once the target is hit (and stops), or null if the
     * target is not in this subtree. The op runs in the frame that owns the node, and every child
     * handle is recycled here — [node] itself is owned/recycled by the caller.
     */
    private fun walkAndPerform(
        node: AccessibilityNodeInfo,
        tid: Int,
        cursor: IntArray,
        perform: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean? {
        if (isProjectedTarget(node)) {
            if (cursor[0] == tid) return perform(node)
            cursor[0]++
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walkAndPerform(child, tid, cursor, perform)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    /**
     * The projection predicate, mirroring [SnapshotProjector.isTarget] over the live-node fields
     * [toRawNode] reads: a node is projected iff `(visible && hasArea) || hasId || hasText`.
     */
    private fun isProjectedTarget(node: AccessibilityNodeInfo): Boolean {
        val hasArea = android.graphics.Rect().also { node.getBoundsInScreen(it) }.let { !it.isEmpty }
        val hasId = !node.viewIdResourceName.isNullOrEmpty()
        val hasText = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()
        return (node.isVisibleToUser && hasArea) || hasId || hasText
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

        // Online settle bounds for the act path (design D3 / P13), matching the pure SettlePolicy
        // defaults (quiet 250ms, hard cap 1500ms). awaitSettle returns once no window event has
        // arrived for the quiet window, or the hard cap elapses — never a fixed sleep.
        private const val SETTLE_QUIET_WINDOW_MS = 250L
        private const val SETTLE_HARD_CAP_MS = 1500L

        /**
         * The live, connected service or null. Set on [onServiceConnected], cleared on teardown.
         * `@Volatile` because the kill-switch / tool factory may read it off another thread.
         */
        @Volatile
        var instance: AccessibilityRuntime? = null
            private set
    }
}
