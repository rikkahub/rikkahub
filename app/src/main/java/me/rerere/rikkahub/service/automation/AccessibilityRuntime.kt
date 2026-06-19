package me.rerere.rikkahub.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.automation.act.ConfirmChannel
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.BindingRequest
import me.rerere.automation.backend.BindingResolution
import me.rerere.automation.backend.FreshnessDecision
import me.rerere.automation.backend.FreshnessEventImpact
import me.rerere.automation.backend.FreshnessEventKind
import me.rerere.automation.backend.FreshnessReducer
import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.backend.PerformResult
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.TargetBinding
import me.rerere.automation.observe.UiSnapshot
import me.rerere.automation.observe.UiTarget
import me.rerere.automation.observe.UNKNOWN_WINDOW_ID
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

    // Pure projector used by [snapshotRawTree], [resolveBinding] and [perform] to project the raw
    // tree / re-compute UiTarget fields on live nodes. Stateless, so safe to share across calls. Seeded
    // with this service's REAL package (`packageName` — carries the `.debug` suffix on debug builds) so
    // the host-exclusion matches the running app. Lazy: `packageName` needs the Context attached, which
    // it is by the time any projection runs (well after onServiceConnected).
    private val projector by lazy { SnapshotProjector(hostPackage = packageName) }

    // Floating STOP kill-switch. WindowManager add/remove must run on the service main thread.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: KillSwitchOverlay? = null

    // Real-input typing path (API 33+, FLAG_INPUT_METHOD_EDITOR). The service acts as a supplementary
    // input method so it can commit text through the focused editor's InputConnection — REAL input
    // events that live-search controllers honor, unlike ACTION_SET_TEXT which only mutates the node's
    // text (verified: Instagram explore search ignores ACTION_SET_TEXT, reacts to real input). The
    // connection is established/torn down by the InputMethod callbacks on the MAIN thread; these fields
    // are read (volatile) from the service dispatcher and the connection is only ever USED on main
    // (runOnMainSync), since an InputConnection is main-thread-bound.
    @Volatile private var imeConnection: InputMethod.AccessibilityInputConnection? = null
    @Volatile private var imeEditorInfo: EditorInfo? = null
    // Completed by onStartInput so a focus-then-commit can await the editor session starting WITHOUT a
    // fixed sleep. Installed BEFORE ACTION_FOCUS so a session that starts after focus cannot be missed;
    // captureMutex serializes perform() so there is never a concurrent writer of this field.
    @Volatile private var imeStartSignal: CompletableDeferred<Unit>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Multi-window awareness (app + system dialogs) + the two events that advance stateSeq.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // FLAG_INPUT_METHOD_EDITOR (API 33+) lets this service supply an InputMethod so the type
            // path can commit text through the focused editor's InputConnection (real input). It rides
            // the already-granted accessibility permission — no separate IME to enable/select. Older
            // devices keep the ACTION_SET_TEXT path. No gesture flag is added: still coordinate-free.
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                } else {
                    0
                }
            notificationTimeout = 100
        }
        instance = this
    }

    /**
     * Supply the input method used by the API 33+ real-input type path (FLAG_INPUT_METHOD_EDITOR). The
     * framework calls this only on API 33+. The returned [InputMethod] tracks the focused editor's
     * [InputMethod.AccessibilityInputConnection] (set on the main thread in onStartInput, cleared in
     * onFinishInput) so [trySetTextViaIme] can commit text into whatever editable currently has focus.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            imeConnection = currentInputConnection
            imeEditorInfo = currentInputEditorInfo
            imeStartSignal?.complete(Unit)
        }

        override fun onFinishInput() {
            imeConnection = null
            imeEditorInfo = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The eyes-open freshness reducer (spec §8): classify the incoming event and let the pure
        // FreshnessReducer decide whether to bump the monotonic epoch / pulse the settle signal. The
        // old code bumped UNCONDITIONALLY on every WINDOW_STATE/CONTENT event, which made the previous
        // seq+hash freshness gate refuse a targeted act whenever ANY subscribed window churned
        // (status-bar / non-active system-window changes). The reducer suppresses the bump+pulse for
        // a CLASSIFIED non-active system window (status bar / shade / non-active dialog backing) — the
        // eyes-open binding will never match an app binding against it — and fail-closes to bump+pulse
        // on every unclassified case (unknown window id / unknown package / unknown active window).
        // A null event carries no classifiable identity, but the framework still signalled us; fail
        // closed to bump+pulse (the reducer's unknown-classification rule) rather than silently
        // dropping a possible state change.
        val kind = when (event?.eventType) {
            null -> null
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> FreshnessEventKind.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> FreshnessEventKind.WINDOW_CONTENT_CHANGED
            // serviceInfo.eventTypes subscribes to exactly the two types above; any other delivery
            // carries no state-settling information, so it is deliberately ignored.
            else -> return
        }
        val decision = if (kind == null) {
            FreshnessDecision(bumpEpoch = true, pulseSettle = true)
        } else {
            // Classify the event: getWindowId() returns the event's source window id (the framework
            // uses -1 when unknown); packageName identifies the owner. The system-window flag MUST come
            // from the VERIFIED predicate (window TYPE_SYSTEM or a FLAG_SYSTEM-verified system package),
            // NOT a package-name heuristic — a non-system / spoofed package must never be muted as
            // "system". We resolve it by matching the event's window id against the live `windows` list
            // and reading that window's type; if the window cannot be resolved (or the id/package is
            // unknown), the flag is left null so the reducer fails closed to bump+pulse. Reading
            // `.id`/`.type` off the already-held `windows` objects allocates no new handles.
            val eventWindowId: Int? = runCatching { event!!.windowId }.getOrNull()?.takeIf { it >= 0 }
            val eventPackage: String? = event!!.packageName?.toString()
            val eventSystemWindow: Boolean? = if (eventPackage != null && eventWindowId != null) {
                // getWindows() hands back AccessibilityWindowInfo the caller must recycle; read the
                // matched window's type, classify via verified isSystemWindow(pkg, type), then recycle.
                val wins = windows.orEmpty()
                try {
                    wins.firstOrNull { runCatching { it.id }.getOrNull() == eventWindowId }
                        ?.let { isSystemWindow(eventPackage, it.type) }
                } finally {
                    recycleAll(emptyList(), wins)
                }
            } else {
                null
            }
            val (activeWindowId, activePackage) = activeWindowIdentity()
            FreshnessReducer.decide(
                FreshnessEventImpact(
                    kind = kind,
                    eventWindowId = eventWindowId,
                    eventPackage = eventPackage,
                    eventSystemWindow = eventSystemWindow,
                    activeWindowId = activeWindowId,
                    activePackage = activePackage,
                ),
            )
        }
        if (decision.bumpEpoch) stateSeq.incrementAndGet()
        if (decision.pulseSettle) {
            // Pulse the settle signal: install a fresh deferred and complete the old one, so an
            // awaitSettle parked on the previous deferred wakes and a new arrival resets its quiet
            // window. getAndSet is atomic against the act coroutine's waiter (design D3/P13).
            settleSignal.getAndSet(CompletableDeferred()).complete(Unit)
        }
    }

    /**
     * The active window's (id, package) at the current instant, or (null, null) when unavailable.
     * One cheap rootInActiveWindow lookup; the root is recycled on every path. Used by the freshness
     * reducer to classify whether a WINDOW_CONTENT_CHANGED event came from the active window.
     */
    private fun activeWindowIdentity(): Pair<Int?, String?> {
        val root = rootInActiveWindow ?: return null to null
        return try {
            val pkg = root.packageName?.toString()
            // AccessibilityNodeInfo.getWindowId() is the node's owning window id; non-negative. Wrapped
            // in runCatching so a framework failure fails closed to null (bump+pulse in the reducer).
            val wid = runCatching { root.windowId }.getOrNull()?.takeIf { it >= 0 }
            wid to pkg
        } finally {
            @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
            root.recycle()
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
     * Out-of-band confirmation for a DANGEROUS (submit-class) act (#198 slice 11) — the live, overlay-
     * backed [me.rerere.automation.act.ConfirmChannel]. Suspends on the floating Confirm/Deny affordance
     * ([KillSwitchOverlay.requestConfirm]) until the user decides, returning `true` on Confirm and
     * `false` on Deny OR timeout (fail-closed; the overlay owns the timeout). Fails closed to `false`
     * when no overlay exists (a dangerous act with no reachable confirm surface must never auto-confirm)
     * — the STOP overlay is always up while a lease is live, so this is the safety net, not the path.
     *
     * The overlay's WindowManager add/remove marshals to the service main thread internally (the
     * suspend [KillSwitchOverlay.requestConfirm] hops via Dispatchers.Main), and the call is cancellable:
     * a kill-switch `revoke()` cancels the act's owning Job, which tears down the pending prompt (the
     * overlay removes its view in a NonCancellable `finally`).
     */
    suspend fun confirm(app: String, verb: String, label: String?): Boolean {
        val current = overlay ?: return false // no confirm surface ⇒ fail closed (deny)
        return current.requestConfirm(app, verb, label)
    }

    /** A [me.rerere.automation.act.ConfirmChannel] bound to this live runtime's overlay (#198 slice 11). */
    fun confirmChannel(): ConfirmChannel = ConfirmChannel { app, verb, label -> confirm(app, verb.name, label) }

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
            @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
            root.recycle()
            pkg
        }

    override suspend fun snapshotRawTree(): RawTree = captureMutex.withLock {
        // serviceDispatcher (NOT a detached scope): hops onto the single service thread while keeping
        // the caller's Job, so cancelling the owning generation cancels exactly this capture (I9).
        withContext(serviceDispatcher) { captureRawTreeInternal() }
    }

    /**
     * Single-instant capture of the live window forest as a value [RawTree] (no live handles).
     * INTERNAL: the caller MUST hold [captureMutex] and run on [serviceDispatcher]. [snapshotRawTree]
     * / [resolveBinding] / [perform] all funnel through here so the act-path capture and the
     * binding-mismatch snapshot describe the same locked frame. Recycles every [AccessibilityNodeInfo]
     * and [AccessibilityWindowInfo] on every path.
     */
    private fun captureRawTreeInternal(): RawTree {
        val seq = stateSeq.get()
        // Capture the active window's package AND the legacy content hash from ONE read of
        // rootInActiveWindow, inside this same locked frame as the window walk — so the grounding's
        // nodes and its token describe one instant (the token must not be built by a SECOND live read).
        // LEGACY: the eyes-open redesign removed the act's whole-snapshot seq/hash freshness gate (a
        // targeted act now re-resolves a strict TargetBinding atomically); the hash is retained as the
        // capture-instant token a backend surfaces and the adversarial input the "a hash change no
        // longer stales an act" regression asserts. Definition mirrors windowContentHash (active window).
        val (foreground, contentHash) = rootInActiveWindow?.let { root ->
            try {
                val acc = StringBuilder()
                foldStructure(root, acc)
                (root.packageName?.toString() ?: packageName) to acc.toString().hashCode().toString(16)
            } finally {
                @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
                root.recycle()
            }
        } ?: (packageName to "empty:$seq")
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
        return RawTree(stateSeq = seq, foregroundPkg = foreground, windows = rawWindows, contentHash = contentHash)
    }

    /**
     * Project the current live tree into a [UiSnapshot] under the given disclosure policy. INTERNAL:
     * the caller MUST hold [captureMutex] and run on [serviceDispatcher]. Used by [resolveBinding] /
     * [perform] to build the fresh snapshot they hand back on a binding resolution / mismatch.
     */
    private fun projectInternal(allowedPackages: Set<String>, includeHost: Boolean): UiSnapshot {
        val raw = captureRawTreeInternal()
        return projector.project(raw, allowedPackages, includeHost)
            .copy(windowContentHash = raw.contentHash)
    }

    override fun windowContentHash(stateSeq: Long): String {
        // Structural/content hash of the active window. LEGACY: this fed the old act-path TOCTOU gate
        // (#198 slice 8) that compared a stored hash + expected seq before dispatch; the eyes-open
        // redesign REMOVED that gate (a targeted act re-resolves a strict TargetBinding atomically), so
        // no act path reads this anymore — it is retained as the capture-instant token a backend may
        // surface. Pure metadata read of the live tree, no capture; safe outside the guard (S2 protects
        // snapshotRawTree/perform, not this).
        val root = rootInActiveWindow ?: return "empty:$stateSeq"
        val acc = StringBuilder()
        try {
            foldStructure(root, acc)
        } finally {
            @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
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
     * Re-resolve a decision-time [BindingRequest] against a FRESH live capture (spec §7). Projects ONE
     * fresh snapshot (under [captureMutex] + [serviceDispatcher]) and filters its targets by the
     * request's strict [TargetBinding] — exactly as [me.rerere.automation.backend.FakeBackend] does —
     * so the returned target is drawn from the SAME captured tree as the returned snapshot (a single
     * instant; the P9 no-op compares an editable value that the returned snapshot actually reflects).
     * Returns [BindingResolution.Unique] on EXACTLY one match, else [BindingResolution.Mismatch] (zero
     * / multiple) carrying the fresh snapshot so the tool layer can re-ground the model. No dispatch
     * happens here; [perform] is the path that re-resolves AND dispatches atomically on a live handle.
     *
     * Authorize-before-backend (S2): the core ADMITs the capability BEFORE calling this, and routes
     * it through [me.rerere.automation.cap.CapabilityGuard.guardInFlight] so a revoke cancels it.
     */
    override suspend fun resolveBinding(request: BindingRequest): BindingResolution =
        captureMutex.withLock {
            withContext(serviceDispatcher) {
                val freshSnapshot = projectInternal(request.allowedPackages, request.includeHost)
                val matches = freshSnapshot.targets.filter { request.binding.matches(it) }
                when (matches.size) {
                    1 -> BindingResolution.Unique(freshSnapshot, matches.single())
                    else -> BindingResolution.Mismatch(freshSnapshot)
                }
            }
        }

    /**
     * Dispatch a write action against the live UI (spec §7, design §1 step 5 / D1). Runs on the single
     * service thread (like [snapshotRawTree]) via [serviceDispatcher] — which preserves the caller's
     * Job, so a revoke/Stop cancelling the owning generation tears this down in flight (I9 / I-act-10).
     * [captureMutex] serializes it against concurrent captures (one node-tree walk at a time). The
     * [PerformResult] is a best-effort dispatch ack only; act success is the core's post-act re-snapshot
     * (design D4), never this return.
     *
     *  - [PerformAction.Global] → [performGlobalAction] (BACK/HOME/RECENTS), no node target.
     *  - [PerformAction.Node] / [PerformAction.SetText] → re-walk the live windows in the EXACT
     *    [SnapshotProjector] projection order, re-computing the SAME [UiTarget] fields on every
     *    projected target, and dispatch the verb on the UNIQUE node whose [TargetBinding] strictly
     *    matches — ACTION_SCROLL_* / ACTION_CLICK for a Node, ACTION_SET_TEXT (with the text [Bundle])
     *    for a SetText (coordinate-free — a resolved node, never a screen point or dispatchGesture,
     *    design D1). Zero / multiple matches ⇒ [PerformResult.BindingMismatch] with the fresh snapshot,
     *    NO mutation. The headline tap / set_text guards (system-UI / password DENY) already ran in the
     *    core before this is ever dispatched.
     */
    override suspend fun perform(action: PerformAction): PerformResult = captureMutex.withLock {
        withContext(serviceDispatcher) {
            when (action) {
                is PerformAction.Global -> {
                    // Close the revoke→dispatch race: the synchronous performGlobalAction below cannot be
                    // interrupted once started, so check the (preserved) Job's cancellation immediately
                    // before it — a kill-switch revoke that fired after authorize throws here instead of
                    // landing the nav (I-act-10 / P20).
                    coroutineContext.ensureActive()
                    val ok = performGlobalAction(
                        when (action.nav) {
                            GlobalNav.BACK -> GLOBAL_ACTION_BACK
                            GlobalNav.HOME -> GLOBAL_ACTION_HOME
                            GlobalNav.RECENTS -> GLOBAL_ACTION_RECENTS
                        },
                    )
                    if (ok) PerformResult.Dispatched else PerformResult.DispatchFailed
                }

                is PerformAction.Node -> dispatchBound(
                    binding = action.binding,
                    allowedPackages = action.allowedPackages,
                    includeHost = action.includeHost,
                ) { node ->
                    // Slice 10 (the general tap): ACTION_CLICK / ACTION_SCROLL_* on the resolved node,
                    // never a dispatchGesture screen point (design D1). The headline tap guard
                    // (system-UI/password DENY) already ran in the core before this dispatches.
                    when (action.kind) {
                        NodeActionKind.CLICK ->
                            performOrActionableAncestor(node, AccessibilityNodeInfo.ACTION_CLICK) { it.isClickable }
                        NodeActionKind.SCROLL_FORWARD ->
                            performOrActionableAncestor(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { it.isScrollable }
                        NodeActionKind.SCROLL_BACKWARD ->
                            performOrActionableAncestor(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { it.isScrollable }
                    }
                }

                // Slice 9 (the input sink): prefer the real-input InputConnection path on API 33+,
                // falling back to the node-level ACTION_SET_TEXT walk. See [dispatchSetText].
                is PerformAction.SetText -> dispatchSetText(action)
            }
        }
    }

    /**
     * Set the text of the resolved editable. PREFERS the real-input path on API 33+: commit through the
     * focused editor's [InputMethod.AccessibilityInputConnection], which produces InputConnection-level
     * input that live-search controllers honor — `ACTION_SET_TEXT` only mutates the node's text and some
     * apps (Instagram explore search) ignore it. Falls back to the node-level `ACTION_SET_TEXT` walk when
     * the IME path is unavailable (API < 33, the focused editor reports no input connection, or it could
     * not be resolved/focused). The optional submit fires the field's IME editor action either way.
     */
    private suspend fun dispatchSetText(action: PerformAction.SetText): PerformResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            trySetTextViaIme(action)?.let { return it }
        }
        // Fallback (API < 33 or no input connection): node-level ACTION_SET_TEXT, same strict-binding
        // re-resolve + projection-order walk as Node; only the leaf operation differs. The optional
        // submit fires ACTION_IME_ENTER (API 30+; a no-op below it, and on a field with no IME action).
        return dispatchBound(
            binding = action.binding,
            allowedPackages = action.allowedPackages,
            includeHost = action.includeHost,
        ) { node ->
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    action.text,
                )
            }
            val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (setOk && action.submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            setOk
        }
    }

    /**
     * The API 33+ real-input type path. Resolves + focuses the target so it becomes the active editor,
     * waits (event-driven, no fixed sleep) for the editor session to start, then replaces the field via
     * `setSelection`+`commitText` on the [InputMethod.AccessibilityInputConnection] and optionally fires
     * the IME editor action. Returns:
     *  - [PerformResult.Dispatched] when the text was committed through the input connection;
     *  - [PerformResult.BindingMismatch] when the binding no longer resolves to one live node (a genuine
     *    stale — propagated so the core re-grounds, NOT swallowed into the fallback);
     *  - `null` when this path is not applicable (could not focus, or no input connection arrived) so
     *    [dispatchSetText] falls back to `ACTION_SET_TEXT`.
     *
     * Threading: the InputConnection is main-thread-bound, so every commit call runs via [runOnMainSync];
     * [imeConnection]/[imeEditorInfo] are set on the main thread by onStartInput and read volatile here.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun trySetTextViaIme(action: PerformAction.SetText): PerformResult? {
        // Arm the start signal BEFORE focusing so a session that starts in response to the focus can't be
        // missed (lost-wakeup safe). captureMutex serializes perform(), so no concurrent writer exists.
        val started = CompletableDeferred<Unit>()
        imeStartSignal = started
        try {
            // Resolve the unique node + request input focus; capture its current length for the replace.
            // A non-unique binding is a real stale (propagate); any other non-dispatch ⇒ fall back.
            var currentLen = 0
            val focus = dispatchBound(
                binding = action.binding,
                allowedPackages = action.allowedPackages,
                includeHost = action.includeHost,
            ) { node ->
                currentLen = node.text?.length ?: 0
                // Best-effort: an already-focused field re-focuses harmlessly; the connection may already
                // be live (fast path below). The boolean only reflects focus, not applicability.
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                true
            }
            when (focus) {
                is PerformResult.BindingMismatch -> return focus
                PerformResult.Dispatched -> {}
                PerformResult.DispatchFailed -> return null
            }
            // Fast path: the field was already focused (keyboard up) ⇒ connection already live. Else wait
            // for onStartInput, bounded; a field that never starts an editor session ⇒ null ⇒ fall back.
            val conn = imeConnection ?: withTimeoutOrNull(IME_START_TIMEOUT_MS) {
                started.await()
                imeConnection
            }
            conn ?: return null
            val editorInfo = imeEditorInfo
            // The AccessibilityInputConnection is main-thread-bound. Its mutators return void, so success
            // is the post-act re-snapshot the core performs (design D4), not a boolean here.
            runOnMainSync {
                // select-all then commit: commitText replaces the current selection, replacing the field.
                conn.setSelection(0, currentLen)
                conn.commitText(action.text, 1, null)
                if (action.submit) {
                    val imeAction = editorInfo
                        ?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION }
                        ?.takeIf { it != EditorInfo.IME_ACTION_NONE && it != EditorInfo.IME_ACTION_UNSPECIFIED }
                    if (imeAction != null) {
                        conn.performEditorAction(imeAction)
                    } else {
                        // No declared editor action ⇒ a raw Enter is the closest real-input equivalent.
                        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                }
            }
            return PerformResult.Dispatched
        } finally {
            imeStartSignal = null
        }
    }

    /**
     * Strict fresh re-resolve + dispatch (spec §7): walk the live windows in the SAME projection order
     * [SnapshotProjector] uses, re-compute the SAME [UiTarget] fields on every projected target, and
     * dispatch [perform] on the UNIQUE node whose [TargetBinding] strictly matches. Zero / multiple
     * matches ⇒ [PerformResult.BindingMismatch] with the fresh snapshot and NO mutation — the
     * load-bearing invariant the live dispatch enforces (a binding that re-resolves to one node in the
     * FakeBackend re-resolves to one node here too).
     *
     * Resource discipline: every visited [AccessibilityNodeInfo] and [AccessibilityWindowInfo] is held
     * for the duration of the walk and recycled on EVERY path (the matched node's handle is valid
     * until [perform] returns, since it is recycled in the same `finally`). When the walk finds no
     * unique match the held handles are recycled BEFORE the fresh-snapshot capture (so the capture's
     * own window iteration gets fresh framework handles, not the held ones).
     *
     * Revocation: `suspend` so it can check the (preserved) Job's cancellation immediately before the
     * synchronous [perform] — that dispatch cannot be interrupted once started, so a kill-switch revoke
     * racing in after authorize must be caught at this last seam (I-act-10 / P20), never landed.
     */
    private suspend fun dispatchBound(
        binding: TargetBinding,
        allowedPackages: Set<String>,
        includeHost: Boolean,
        perform: (AccessibilityNodeInfo) -> Boolean,
    ): PerformResult {
        val heldNodes = ArrayList<AccessibilityNodeInfo>()
        val heldWindows = ArrayList<AccessibilityWindowInfo>()
        val matches = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in windows.orEmpty()) {
                heldWindows.add(window)
                val root = window.root ?: continue
                heldNodes.add(root)
                val pkg = root.packageName?.toString() ?: continue
                val systemWindow = isSystemWindow(pkg, window.type)
                // Normalize an unavailable window id to UNKNOWN_WINDOW_ID so buildTarget's node.windowId
                // fallback fires identically to the projection path. The framework returns -1 (and a
                // throw is possible) for an unknown id; any negative is unknown, never a real matchable
                // id (review round 7 #1).
                val windowId = runCatching { window.id }.getOrNull()?.takeIf { it >= 0 } ?: UNKNOWN_WINDOW_ID
                if (!SnapshotProjector.isWindowEligible(
                        pkg, systemWindow, allowedPackages, includeHost, packageName,
                    )
                ) continue
                walkLiveAndMatch(
                    node = root,
                    binding = binding,
                    windowId = windowId,
                    pkg = pkg,
                    systemWindow = systemWindow,
                    path = emptyList(),
                    matches = matches,
                    heldNodes = heldNodes,
                )
            }
            if (matches.size == 1) {
                // Last-seam revoke check (see kdoc): the dispatch below is uninterruptible once it runs.
                coroutineContext.ensureActive()
                val ok = perform(matches.single())
                return if (ok) PerformResult.Dispatched else PerformResult.DispatchFailed
            }
            // Zero or multiple matches: recycle the held handles BEFORE the fresh-snapshot capture so
            // projectInternal's own window iteration gets fresh framework handles (no aliasing).
            recycleAll(heldNodes, heldWindows)
            heldNodes.clear()
            heldWindows.clear()
            val fresh = projectInternal(allowedPackages, includeHost)
            return PerformResult.BindingMismatch(fresh)
        } finally {
            recycleAll(heldNodes, heldWindows)
        }
    }

    /**
     * Pre-order DFS mirroring [SnapshotProjector.collect] over live nodes: for every projected target
     * node, re-compute the SAME [UiTarget] fields via [SnapshotProjector.buildTarget] (the helper the
     * projector itself uses, on the node's [toRawNode] subtree) and add the live node to [matches]
     * when [binding] strictly matches. Always descends into every child (incl. non-projected containers
     * so a projected descendant is not skipped — projector P4). The live child handles are held in
     * [heldNodes] and recycled by the caller; the per-node RawNode subtree is built via [toRawNode],
     * whose OWN child handles are independent of this outer walk's and recycled inside it.
     */
    private fun walkLiveAndMatch(
        node: AccessibilityNodeInfo,
        binding: TargetBinding,
        windowId: Int,
        pkg: String,
        systemWindow: Boolean,
        path: List<Int>,
        matches: MutableList<AccessibilityNodeInfo>,
        heldNodes: MutableList<AccessibilityNodeInfo>,
    ) {
        if (isProjectedTargetLive(node)) {
            val rawSubtree = node.toRawNode()
            val target = SnapshotProjector.buildTarget(
                tid = -1,
                node = rawSubtree,
                systemWindow = systemWindow,
                sourcePackage = pkg,
                windowId = windowId,
                structuralPath = path,
            )
            if (binding.matches(target)) matches.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            heldNodes.add(child)
            walkLiveAndMatch(
                node = child,
                binding = binding,
                windowId = windowId,
                pkg = pkg,
                systemWindow = systemWindow,
                path = path + i,
                matches = matches,
                heldNodes = heldNodes,
            )
        }
    }

    /** Recycle [nodes] and [windows], swallowing framework errors so a bad handle never escapes. */
    private fun recycleAll(
        nodes: List<AccessibilityNodeInfo>,
        windows: List<AccessibilityWindowInfo>,
    ) {
        for (node in nodes) runCatching { @Suppress("DEPRECATION") node.recycle() }
        for (window in windows) runCatching { @Suppress("DEPRECATION") window.recycle() }
    }

    /**
     * Dispatch [actionId] on the resolved [node], falling back to the NEAREST ancestor that [capable]
     * accepts when the node itself cannot perform it. Work-first: an agent that selects a bare TextView
     * label (no CLICK flag) should still hit the label's clickable ROW rather than fail with a
     * misleading "screen changed" — this is the standard accessibility-automation behavior and removes
     * the single biggest source of agent looping (tapping non-actionable labels). The resolved [node]
     * is still the strict TargetBinding match (the agent's intended element); the climb only redirects
     * the ACTION to the actionable container that visually owns it, never to a sibling or a different
     * window. Ancestor handles are recycled here; [node] itself is recycled by dispatchBound's finally.
     */
    private fun performOrActionableAncestor(
        node: AccessibilityNodeInfo,
        actionId: Int,
        capable: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        if (node.performAction(actionId)) return true
        val held = ArrayList<AccessibilityNodeInfo>()
        try {
            var ancestor = runCatching { node.parent }.getOrNull()
            var depth = 0
            while (ancestor != null && depth < MAX_ANCESTOR_CLIMB) {
                held.add(ancestor)
                if (capable(ancestor) && ancestor.performAction(actionId)) return true
                ancestor = runCatching { ancestor.parent }.getOrNull()
                depth++
            }
            return false
        } finally {
            recycleAll(held, emptyList())
        }
    }

    /**
     * The projection predicate, mirroring [SnapshotProjector.isTarget] over the live-node fields
     * [toRawNode] reads: a node is projected iff `(visible && hasArea) || hasId || hasText`. Renamed
     * from `isProjectedTarget` so it does not collide with the new dispatch path.
     */
    private fun isProjectedTargetLive(node: AccessibilityNodeInfo): Boolean {
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
                    @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
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
                systemWindow = isSystemWindow(pkg, type),
                root = root.toRawNode(),
                // The window's backend id (spec §3 step 8): carries into UiTarget.windowId so a strict
                // TargetBinding can name the SAME window across a fresh re-resolve. AccessibilityWindowInfo.getId()
                // returns -1 for an unknown id; any negative (and a throw) is normalized to
                // UNKNOWN_WINDOW_ID so it is never matched as a real distinguishing id (review round 7 #1).
                windowId = runCatching { this.id }.getOrNull()?.takeIf { it >= 0 } ?: UNKNOWN_WINDOW_ID,
            )
        } finally {
            @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
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
                    @Suppress("DEPRECATION") // recycle() required below API 33 (no-op above); minSdk 26
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
            // getChecked()'s tri-state replacement (API 36) remaps the partial state; the boolean
            // accessor keeps RawNode's shape identical and works on minSdk 26.
            checked = @Suppress("DEPRECATION") isChecked,
            password = isPassword,
            children = children,
            // The node's owning window id (spec §3 step 8): the projector prefers the window-level id
            // and uses this only as a fallback when the window id was unavailable. getWindowId() returns
            // -1 for an unknown id; any negative (and a throw) is normalized to UNKNOWN_WINDOW_ID so it
            // never serves as a real matchable id (review round 7 #1).
            windowId = runCatching { windowId }.getOrNull()?.takeIf { it >= 0 } ?: UNKNOWN_WINDOW_ID,
        )
    }

    private fun String.isSystemUiPackage(): Boolean =
        this == "com.android.systemui" || this.endsWith("packageinstaller")

    private fun isSystemWindow(pkg: String, type: Int): Boolean =
        type == AccessibilityWindowInfo.TYPE_SYSTEM ||
            (pkg.isSystemUiPackage() && isVerifiedSystemPackage(pkg))

    private fun isVerifiedSystemPackage(pkg: String): Boolean = runCatching {
        (packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    companion object {
        // Cap on the clickable/scrollable-ancestor climb (performOrActionableAncestor): a real view
        // hierarchy is shallow; this only backstops a pathological/looping parent chain.
        private const val MAX_ANCESTOR_CLIMB = 16

        // Online settle bounds for the act path (design D3 / P13), matching the pure SettlePolicy
        // defaults (quiet 250ms, hard cap 1500ms). awaitSettle returns once no window event has
        // arrived for the quiet window, or the hard cap elapses — never a fixed sleep.
        private const val SETTLE_QUIET_WINDOW_MS = 250L
        private const val SETTLE_HARD_CAP_MS = 1500L

        // Bound for waiting on the editor session (onStartInput) after focusing a field in the API 33+
        // real-input type path. Only hit when the field was not already focused; an editor that never
        // starts a session within this window falls back to ACTION_SET_TEXT. Not a fixed sleep — the
        // wait completes the instant onStartInput fires.
        private const val IME_START_TIMEOUT_MS = 700L

        /**
         * The live, connected service or null. Set on [onServiceConnected], cleared on teardown.
         * `@Volatile` because the kill-switch / tool factory may read it off another thread.
         */
        @Volatile
        var instance: AccessibilityRuntime? = null
            private set
    }
}
