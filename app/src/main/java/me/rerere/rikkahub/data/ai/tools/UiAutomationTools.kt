package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.automation.act.Act
import me.rerere.automation.act.ActOutcome
import me.rerere.automation.act.AutomationCore
import me.rerere.automation.act.ConfirmChannel
import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.cap.AuthRequest
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Decision
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Verb
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.UiSnapshot

/**
 * The per-generation UI-automation [Tool] factory — the `:app` surface of #187/#198, built on the
 * already-merged `:automation` capability + observation + act kernel (#205, #211). Exposes
 * `ui_observe` (read-only, #187 v1), the lowest-risk nav act verbs `ui_scroll` and `ui_global`
 * (#198 slice 8), the input sink `ui_set_text` (`Verb.SET_TEXT` + `Sink.TYPE_INTO`, #198 slice 9), and
 * the general tap `ui_tap` (`Verb.TAP`, no sink — `Act.Targeted` + `NodeActionKind.CLICK`, #198 slice
 * 10) over the SAME [AutomationCore]. The dangerous-sink (submit-class) confirm channel (#198 slice 11)
 * is now wired: a `ui_tap` whose resolved target is submit-class (send/pay/checkout-class) derives the
 * SUBMIT sink in the core and is gated behind the injected [confirm] channel before it can dispatch.
 * `ui_set_text` inherits the act path's restricted P9 no-op: an unchanged-text set is idempotent (no
 * dispatch), but typing into a password/system-UI field is always DENIED. `ui_tap` is never no-op'd
 * (P9 is set_text-only) and a tap on a system-UI/permission dialog (Allow/Grant) or a password field is
 * DENIED before dispatch — observable, never actionable.
 *
 * Shape mirrors [me.rerere.rikkahub.data.ai.subagent.buildSpawnTool]: a top-level factory closing
 * over per-conversation context, built ONLY at `ChatService`'s per-generation tool buildList. It is
 * deliberately Android-free (design I10): it takes an [AutomationCore] (over the real
 * `AccessibilityRuntime` backend in production, or a `FakeBackend` in tests) plus a
 * foreground-package supplier — no `android.accessibility` import lives here, only in
 * `service/automation/AccessibilityRuntime`.
 *
 * Safety wiring (the design's hard prerequisites, all enforced here):
 *  - **Default-OFF / empty surface (S1):** returns `emptyList()` unless a non-null [CapabilityGuard]
 *    is supplied. The guard is the single source of truth for activation — `ChatService` mints one
 *    only when the master switch is enabled and an active, usable grant exists (a fresh per-run grant
 *    can override the standing grant, but cannot bypass the switch). A null guard = no authority =
 *    no tool.
 *  - **Guard BEFORE backend (S2):** `ui_observe` calls [CapabilityGuard.authorize] before reaching
 *    [AutomationCore.observe]; the act tools delegate to [AutomationCore.act], which authorizes
 *    internally before any [me.rerere.automation.backend.AutomationBackend.perform]. The backend is
 *    never touched on a DENY. (The act tools therefore must NOT re-authorize or re-`guardInFlight` —
 *    that would double-audit and break P25; `core.act` owns both.)
 *  - **Revoke cancels in-flight + closes the authorize→act race (I9):** the observe capture runs
 *    inside [CapabilityGuard.guardInFlight]; the act path runs its perform/settle/re-snapshot inside
 *    the same `guardInFlight` (within `core.act`), so a kill-switch `revoke()` cancels parked work via
 *    the owning [Job] and a revoke between authorize and dispatch denies instead of acting (P20).
 *  - **Captured target bound to the authorized target (TOCTOU):** for `ui_observe` the foreground
 *    package is re-asserted after capture; for the act path `core.act` carries a decision-time
 *    `TargetBinding` (window identity + structural fingerprint) and the backend FRESH-resolves that
 *    binding and dispatches atomically (resolve + perform on the same live node under one capture
 *    lock). A target that moved / was replaced since the grounding ui_observe fails the strict binding
 *    match, yielding a stale-state stop (with the fresh snapshot to re-decide), never an act on a moved
 *    target. (The old whole-snapshot `stateSeq + windowContentHash` freshness gate is gone — benign
 *    background churn no longer stales an act; the binding match is the freshness signal.)
 *  - **Turn-scoped tids:** the act tools resolve against the snapshot the latest `ui_observe`
 *    grounded this turn; an act before any observe refuses (a tid is only valid for its snapshot).
 *  - **Authority is closed over, never model-supplied (I2):** the guard is captured here; the model's
 *    JSON args carry no caller context (a JSON-passed lease would be forgeable — gate finding 6). The
 *    act verb/sink is derived from the [Act] variant, never from args.
 *  - **Fail-closed on malformed args (P24/P25):** a non-object argument (or an unparseable selector /
 *    direction) is refused without touching the backend, but — exactly like `ui_observe` — it is
 *    refused VIA an audited `guard.authorize(AuthRequest(malformed = true, …))` that fails closed and
 *    writes the one redacted ledger entry P25 requires, so a prompt-injection-shaped garbage act still
 *    leaves a trace in the security audit trail. (This is the ONLY authorize the act tools make: the
 *    well-formed path's single decision lives in `core.act`, so there is no double-audit.)
 *  - **needsApproval=false:** the in-chat approval gate is structurally unreachable while another app
 *    is foreground (design constraint 1); OCap is the brake instead.
 *  - **Text-only result (gate A1):** every snapshot is rendered to a single self-sufficient
 *    [UIMessagePart.Text] — never an [UIMessagePart.Image], because most providers drop tool-output
 *    images. Deny/stale stops are vague self-sufficient text; the internal deny reason is never leaked.
 *
 * @param guard the conversation-scoped capability guard, or null when automation is not active for
 *   this generation. Minted per generation in `ChatService` (sessionId = conversationId).
 * @param core the observation core over the live backend; supplied by the caller so this factory
 *   stays free of Android types.
 * @param foregroundPkg the current foreground package, read BEFORE observing so the guard can decide
 *   on the real target (S2). A null value (a11y service not connected / no foreground) fails closed
 *   at the surface check.
 * @param confirm the out-of-band confirmation channel for a dangerous (submit-class) sink (#198 slice
 *   11). Passed through to [AutomationCore.act] for every act; only a submit-class `ui_tap` consults it
 *   (a non-dangerous act never does). Production wires the overlay-backed channel; a fail-closed
 *   [me.rerere.automation.act.AlwaysDeny] is the right default when no real confirm surface is reachable.
 */
fun getUiAutomationTools(
    guard: CapabilityGuard?,
    core: AutomationCore,
    foregroundPkg: () -> String?,
    confirm: ConfirmChannel,
): List<Tool> {
    // The minted [guard] is the single source of truth for activation: ChatService only mints one
    // when `uiAutomationEnabled` is on and an ACTIVE, usable grant exists. A fresh per-run grant can
    // override the standing grant, but cannot bypass that master gate. A null guard ⇒ no authority ⇒
    // no tool at all (default-OFF, empty surface; design §2/§5/S1). Re-checking `uiAutomationEnabled`
    // here in ADDITION to the guard would split the activation source of truth and risk dropping tools
    // even after ChatService minted a valid guard.
    if (guard == null) return emptyList()

    // The last snapshot ui_observe grounded this turn. The act tools (ui_scroll/ui_global) resolve
    // their selector against THIS snapshot and pass it as the act's grounding — tids are turn-scoped,
    // so an act before any observe (grounded == null) must refuse and tell the model to observe first.
    // Closed over by all three tools; only ever written on a successful observe / a successful act's
    // re-snapshot, never from model-supplied args (I2). Tools in a single generation run sequentially
    // (the tool loop awaits each execute), so this needs no extra synchronization.
    var grounded: UiSnapshot? = null

    // P25 for the act path: a malformed act attempt is still an admission DECISION and must leave
    // exactly one redacted ledger entry — mirrors ui_observe's malformed branch. The act tools' ONLY
    // direct authorize: the well-formed path's single decision is inside core.act, so routing the
    // malformed short-circuit through guard.authorize here adds no double-audit (core.act is never
    // reached on a malformed arg). The guard DENYs on `malformed` before it even reads targetPkg/verb,
    // so the returned text is always the vague ACT_DENIED_MESSAGE. The malformed checks run BEFORE the
    // `grounded ?: return` gate (#221): an ungrounded+malformed act is inert (no snapshot to dispatch
    // against) but it is still a malformed admission attempt and must leave its ledger entry — so
    // `grounded` may be null here, and a null targetPkg is the truthful "no grounded target" record.
    fun auditMalformedAct(verb: Verb, sink: Sink?, rawArgs: String): List<UIMessagePart> {
        guard.authorize(
            AuthRequest(
                verb = verb,
                targetPkg = grounded?.foregroundPkg,
                sink = sink,
                malformed = true,
                rawArgs = rawArgs,
            ),
        )
        return listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
    }

    // Authorized capture of the CURRENT foreground screen, re-grounding `grounded` on success. This is
    // the single observe authorize+capture+bind path (S2 + the TOCTOU surface-bind), shared by
    // `ui_observe` AND the act tools' stale re-ground (so a stale act folds the fresh screen into its
    // own result — the model never needs a separate ui_observe round-trip after an act). Returns the
    // bound snapshot, or null when the guard DENYs / a revoke fires / the live foreground is an app the
    // capability never authorized (fail-closed: no unauthorized surface is ever disclosed).
    suspend fun captureAuthorizedScreen(rawArgs: String): UiSnapshot? {
        val authorizedPkg = foregroundPkg()
        val decision = guard.authorize(
            AuthRequest(verb = Verb.OBSERVE, targetPkg = authorizedPkg, rawArgs = rawArgs),
        )
        if (decision == Decision.DENY) {
            return null
        }
        val job = currentCoroutineContext()[Job]
        return guard.guardInFlight(
            cancel = { job?.cancel(CancellationException("automation revoked")) },
            onAlreadyRevoked = { null },
            block = {
                val snapshot = core.observe(setOfNotNull(authorizedPkg), guard.includeHost)
                // TOCTOU surface bind: if the foreground switched since the authorize-read, the capture
                // is an app the guard never admitted — drop it rather than disclose it.
                if (snapshot.foregroundPkg != authorizedPkg) {
                    null
                } else {
                    grounded = snapshot
                    snapshot
                }
            },
        )
    }

    // A stale act response that ALWAYS carries the current screen so the model re-decides in ONE step
    // (no "act -> stale -> ui_observe -> act" round-trip). When the backend already captured the fresh
    // screen (same authorized surface) use it; otherwise (surface switch / framework refusal / missing
    // tid) fold in an authorized re-capture. Only when even that is denied does the bare re-observe text
    // remain — there is genuinely no authorized screen to show.
    suspend fun staleResponse(
        carried: UiSnapshot?,
        redecidePreamble: String = ACT_STALE_REDECIDE_MESSAGE,
    ): List<UIMessagePart> {
        if (carried != null) {
            grounded = carried
            return listOf(UIMessagePart.Text(redecidePreamble + "\n\n" + renderCompactSnapshot(carried)))
        }
        val fresh = captureAuthorizedScreen(rawArgs = "<act-reground>")
        return if (fresh != null) {
            listOf(UIMessagePart.Text(redecidePreamble + "\n\n" + renderCompactSnapshot(fresh)))
        } else {
            listOf(UIMessagePart.Text(ACT_STALE_MESSAGE))
        }
    }

    return listOf(
        Tool(
            name = UI_OBSERVE_TOOL_NAME,
            description = "Capture an authoritative, freshly-grounded snapshot of the device UI that " +
                "is currently in the foreground (other apps), as a compact text table of actionable " +
                "targets. Read-only: it only observes, it cannot tap, type, or scroll. Re-observe " +
                "every step before reasoning about the screen — a target id is only valid for the " +
                "snapshot it appears in.",
            parameters = {
                // No inputs: ui_observe takes an empty object. A non-object arg is malformed (P24).
                InputSchema.Obj(properties = buildJsonObject { })
            },
            needsApproval = false,
            execute = { args ->
                // A non-object arg is malformed (P24/P25): an audited DENY (mirrors auditMalformedAct
                // but for OBSERVE), so the fail-closed refusal still leaves its one ledger entry.
                if (args !is JsonObject) {
                    guard.authorize(
                        AuthRequest(
                            verb = Verb.OBSERVE,
                            targetPkg = foregroundPkg(),
                            malformed = true,
                            rawArgs = args.toString(),
                        ),
                    )
                    listOf(UIMessagePart.Text(OBSERVE_DENIED_MESSAGE))
                } else {
                    // Well-formed: the shared authorize+capture+bind path (S2, revoke-cancellable,
                    // TOCTOU surface-bind) re-grounds the act tools on the fresh snapshot. Null ⇒
                    // denied / revoked / unauthorized foreground ⇒ the vague deny text.
                    captureAuthorizedScreen(rawArgs = args.toString())
                        ?.let { listOf(UIMessagePart.Text(renderCompactSnapshot(it))) }
                        ?: listOf(UIMessagePart.Text(OBSERVE_DENIED_MESSAGE))
                }
            },
        ),
        Tool(
            name = UI_SCROLL_TOOL_NAME,
            description = "Scroll an actionable element of the foreground app (other apps), forward " +
                "or backward. You MUST call ui_observe first this turn — a target id is only valid " +
                "for the snapshot it appears in. Select the element by its tid (from the latest " +
                "ui_observe table), by its visible text, or by its semantic key. Returns a fresh " +
                "ui_observe-style snapshot after the scroll — this IS your fresh observation, so do " +
                "NOT call ui_observe again afterward; read this result and pick the next target from it.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put(
                                    "description",
                                    JsonPrimitive(
                                        "One of: {\"tid\": <int from the latest ui_observe>}, " +
                                            "{\"text\": <visible label>, \"role\"?: <class>}, or " +
                                            "{\"semanticKey\": <key>}.",
                                    ),
                                )
                            },
                        )
                        put(
                            "direction",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put("enum", buildJsonArray { add(JsonPrimitive("forward")); add(JsonPrimitive("backward")) })
                                put("description", JsonPrimitive("Scroll direction."))
                            },
                        )
                    },
                    required = listOf("selector", "direction"),
                )
            },
            needsApproval = false,
            execute = execute@{ args ->
                // Fail closed on anything we cannot parse into an Act (P24): never build an Act, never
                // touch the backend. Authority is the closed-over guard, never anything in `args` (I2).
                // The refusal is AUDITED (P25) — auditMalformedAct authorizes a malformed SCROLL request
                // so the fail-closed DENY writes a ledger entry, exactly as ui_observe does. Audited
                // BEFORE the grounding gate (#221): an ungrounded+malformed act must still leave its
                // one ledger entry, not slip out unaudited via the re-observe steer.
                if (args !is JsonObject) {
                    return@execute auditMalformedAct(Verb.SCROLL, sink = null, rawArgs = args.toString())
                }
                val selector = parseSelector(args["selector"])
                    ?: return@execute auditMalformedAct(Verb.SCROLL, sink = null, rawArgs = args.toString())
                val kind = when ((args["direction"] as? JsonPrimitive)?.contentOrNull) {
                    "forward" -> NodeActionKind.SCROLL_FORWARD
                    "backward" -> NodeActionKind.SCROLL_BACKWARD
                    else -> return@execute auditMalformedAct(Verb.SCROLL, sink = null, rawArgs = args.toString())
                }
                // tids are turn-scoped: refuse to act until ui_observe has grounded this turn.
                val snapshot = grounded ?: return@execute listOf(UIMessagePart.Text(ACT_REOBSERVE_MESSAGE))
                // core.act authorizes internally (S2) and runs guardInFlight (P20) — the tool layer
                // must NOT call authorize/guardInFlight here (would double-audit / break P25).
                when (val outcome = core.act(guard, snapshot, Act.Targeted(selector, kind), confirm)) {
                    is ActOutcome.Acted -> {
                        grounded = outcome.snapshot // re-ground for the next act
                        listOf(UIMessagePart.Text(renderCompactSnapshot(outcome.snapshot)))
                    }
                    // Both stops are vague and re-observe-oriented; the deny reason is NEVER leaked.
                    is ActOutcome.Denied -> listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
                    is ActOutcome.StaleState -> {
                        // Re-ground on the fresh snapshot the backend captured at the binding mismatch,
                        // so the model can re-decide from the live screen (and the next act this turn
                        // resolves against the current screen, not the stale grounding).
                        staleResponse(outcome.snapshot)
                    }
                }
            },
        ),
        Tool(
            name = UI_GLOBAL_TOOL_NAME,
            description = "Perform a global navigation on the device: go back, go to the home " +
                "screen, or open recent apps. You MUST call ui_observe first this turn. Returns a " +
                "fresh ui_observe-style snapshot after the navigation — this IS your fresh observation, " +
                "so do NOT call ui_observe again afterward; read this result and decide from it.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "direction",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("back")); add(JsonPrimitive("home")); add(JsonPrimitive("recents"))
                                    },
                                )
                                put("description", JsonPrimitive("Which global navigation to perform."))
                            },
                        )
                    },
                    required = listOf("direction"),
                )
            },
            needsApproval = false,
            execute = execute@{ args ->
                // Fail closed + AUDITED (P24/P25): a malformed ui_global is a GLOBAL act over the
                // GLOBAL_NAV sink, so the ledger entry records that verb/sink as ui_observe does for
                // OBSERVE. Audited BEFORE the grounding gate (#221), like every act tool.
                if (args !is JsonObject) {
                    return@execute auditMalformedAct(Verb.GLOBAL, Sink.GLOBAL_NAV, rawArgs = args.toString())
                }
                val nav = when ((args["direction"] as? JsonPrimitive)?.contentOrNull) {
                    "back" -> GlobalNav.BACK
                    "home" -> GlobalNav.HOME
                    "recents" -> GlobalNav.RECENTS
                    else -> return@execute auditMalformedAct(Verb.GLOBAL, Sink.GLOBAL_NAV, rawArgs = args.toString())
                }
                // tids are turn-scoped: refuse to act until ui_observe has grounded this turn.
                val snapshot = grounded ?: return@execute listOf(UIMessagePart.Text(ACT_REOBSERVE_MESSAGE))
                when (val outcome = core.act(guard, snapshot, Act.Global(nav), confirm)) {
                    is ActOutcome.Acted -> {
                        grounded = outcome.snapshot
                        listOf(UIMessagePart.Text(renderCompactSnapshot(outcome.snapshot)))
                    }
                    is ActOutcome.Denied -> listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
                    is ActOutcome.StaleState -> {
                        // Global nav has no target element — use the navigation-specific re-decide text.
                        staleResponse(outcome.snapshot, ACT_STALE_GLOBAL_REDECIDE_MESSAGE)
                    }
                }
            },
        ),
        Tool(
            name = UI_SET_TEXT_TOOL_NAME,
            description = "Type text into an editable field of the foreground app (other apps), " +
                "replacing its current contents. You MUST call ui_observe first this turn — a target " +
                "id is only valid for the snapshot it appears in. Select the field by its tid (from " +
                "the latest ui_observe table), by its form key, by its semantic key, or by its " +
                "visible text. Returns a fresh ui_observe-style snapshot after the edit — this IS your " +
                "fresh observation, so do NOT call ui_observe again afterward; read this result. The " +
                "field's new value is shown in that snapshot, so trust it landed rather than re-typing.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put(
                                    "description",
                                    JsonPrimitive(
                                        "One of: {\"tid\": <int from the latest ui_observe>}, " +
                                            "{\"formKey\": <input field key>}, " +
                                            "{\"semanticKey\": <key>}, or " +
                                            "{\"text\": <visible label>, \"role\"?: <class>}.",
                                    ),
                                )
                            },
                        )
                        put(
                            "text",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put("description", JsonPrimitive("The text to set as the field's new contents."))
                            },
                        )
                    },
                    required = listOf("selector", "text"),
                )
            },
            needsApproval = false,
            execute = execute@{ args ->
                // Fail closed + AUDITED (P24/P25): a set_text is a SET_TEXT verb over the TYPE_INTO
                // input sink, so a malformed attempt records that verb/sink in the ledger exactly as
                // ui_observe does for OBSERVE. A non-object arg, an unparseable selector, OR a missing
                // text payload are all malformed — a set_text with no text is meaningless, so it must
                // fail closed rather than dispatch an empty/garbage write. Audited BEFORE the grounding
                // gate (#221), like every act tool.
                if (args !is JsonObject) {
                    return@execute auditMalformedAct(Verb.SET_TEXT, Sink.TYPE_INTO, rawArgs = args.toString())
                }
                val selector = parseSelector(args["selector"])
                    ?: return@execute auditMalformedAct(Verb.SET_TEXT, Sink.TYPE_INTO, rawArgs = args.toString())
                // The text MUST be a JSON string. contentOrNull coerces ANY primitive
                // ({"text":123} -> "123", true -> "true"), which would DISPATCH a non-string as a
                // write instead of failing closed — the garbage-write case P24 rejects for this
                // security-critical input sink. "" stays valid: clearing a field is a legitimate
                // set_text the act path's P9 no-op already handles.
                val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@execute auditMalformedAct(Verb.SET_TEXT, Sink.TYPE_INTO, rawArgs = args.toString())
                // tids are turn-scoped: refuse to act until ui_observe has grounded this turn.
                val snapshot = grounded ?: return@execute listOf(UIMessagePart.Text(ACT_REOBSERVE_MESSAGE))
                // core.act authorizes internally (S2) and runs guardInFlight (P20) — the tool layer
                // must NOT call authorize/guardInFlight here (would double-audit / break P25). The
                // restricted P9 no-op (an unchanged-text set_text returns Acted without dispatching)
                // lives inside core.act; from here it is indistinguishable from a normal Acted.
                when (val outcome = core.act(guard, snapshot, Act.SetText(selector, text), confirm)) {
                    is ActOutcome.Acted -> {
                        grounded = outcome.snapshot // re-ground for the next act
                        listOf(UIMessagePart.Text(renderCompactSnapshot(outcome.snapshot)))
                    }
                    is ActOutcome.Denied -> listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
                    is ActOutcome.StaleState -> {
                        staleResponse(outcome.snapshot)
                    }
                }
            },
        ),
        Tool(
            name = UI_TAP_TOOL_NAME,
            description = "Tap (click) an actionable element of the foreground app (other apps). You " +
                "MUST call ui_observe first this turn — a target id is only valid for the snapshot it " +
                "appears in. Select the element by its tid (from the latest ui_observe table), by its " +
                "visible text, or by its semantic key. Returns a fresh ui_observe-style snapshot after " +
                "the tap — this IS your fresh observation, so do NOT call ui_observe again afterward; " +
                "read this result and pick the next target from it. Tip: select a node that shows the " +
                "CLICK flag (a bare text label often is not tappable — tap its clickable row instead).",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put(
                                    "description",
                                    JsonPrimitive(
                                        "One of: {\"tid\": <int from the latest ui_observe>}, " +
                                            "{\"text\": <visible label>, \"role\"?: <class>}, or " +
                                            "{\"semanticKey\": <key>}.",
                                    ),
                                )
                            },
                        )
                    },
                    required = listOf("selector"),
                )
            },
            needsApproval = false,
            execute = execute@{ args ->
                // Fail closed + AUDITED (P24/P25): a MALFORMED tap is recorded with no sink (the sink is
                // only known once the target resolves, and a malformed tap never resolves one) — exactly
                // as ui_scroll does for SCROLL. The submit-class SUBMIT sink (#198 slice 11) is derived
                // in core.act from the RESOLVED target, so it never applies to a malformed (unresolved)
                // tap. Audited BEFORE the grounding gate (#221), like every act tool.
                if (args !is JsonObject) {
                    return@execute auditMalformedAct(Verb.TAP, sink = null, rawArgs = args.toString())
                }
                val selector = parseSelector(args["selector"])
                    ?: return@execute auditMalformedAct(Verb.TAP, sink = null, rawArgs = args.toString())
                // tids are turn-scoped: refuse to act until ui_observe has grounded this turn.
                val snapshot = grounded ?: return@execute listOf(UIMessagePart.Text(ACT_REOBSERVE_MESSAGE))
                // core.act authorizes internally (S2) and runs guardInFlight (P20) — the tool layer must
                // NOT call authorize/guardInFlight here (would double-audit / break P25). The system-UI/
                // password DENY (a tap on an Allow/Grant button or a credential field) AND the submit-
                // class confirm gate (#198 slice 11 — a send/pay-class tap derives SUBMIT and must be
                // confirmed via `confirm`) both live inside core.act, derived from the resolved target;
                // from here a confirm-declined tap is just an ordinary Denied → vague ACT_DENIED_MESSAGE.
                when (val outcome = core.act(guard, snapshot, Act.Targeted(selector, NodeActionKind.CLICK), confirm)) {
                    is ActOutcome.Acted -> {
                        grounded = outcome.snapshot // re-ground for the next act
                        listOf(UIMessagePart.Text(renderCompactSnapshot(outcome.snapshot)))
                    }
                    is ActOutcome.Denied -> listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
                    is ActOutcome.StaleState -> {
                        staleResponse(outcome.snapshot)
                    }
                }
            },
        ),
    )
}

/**
 * Parse the model's `selector` arg into a [Selector], or null if it is missing/malformed (fail
 * closed — the caller then denies without building an [Act]). Coordinate-free by construction: only
 * tid / formKey / semanticKey / text(+role) are accepted, never a position. Precedence follows the
 * field-addressing order: `tid` wins if present, then `formKey` (the input-field axis, #198 slice 9),
 * then `semanticKey`, then `text`.
 */
private fun parseSelector(element: JsonElement?): Selector? {
    val obj = element as? JsonObject ?: return null
    // tid is type-safe (intOrNull rejects a non-number). The string fields must gate on isString
    // BEFORE reading content: contentOrNull coerces any primitive ({"formKey":123} -> "123"), which
    // would route a non-string into a stable-key/text selector instead of failing closed. A non-string
    // (or empty) field falls through to the next branch, ending in `return null` (the caller then audits
    // a malformed DENY) — exactly as a missing field does.
    (obj["tid"] as? JsonPrimitive)?.intOrNull?.let { return Selector.ByTid(it) }
    (obj["formKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() }
        ?.let { return Selector.ByFormKey(it) }
    (obj["semanticKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() }
        ?.let { return Selector.BySemanticKey(it) }
    (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text ->
        val role = (obj["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        return Selector.ByText(text, role)
    }
    return null
}

const val UI_OBSERVE_TOOL_NAME = "ui_observe"
const val UI_SCROLL_TOOL_NAME = "ui_scroll"
const val UI_GLOBAL_TOOL_NAME = "ui_global"
const val UI_SET_TEXT_TOOL_NAME = "ui_set_text"
const val UI_TAP_TOOL_NAME = "ui_tap"

/**
 * The act tools refuse until [getUiAutomationTools]'s turn-scoped `grounded` snapshot exists: a tid
 * is only valid for the snapshot it appears in, so the model must ui_observe before it acts. Vague by
 * design (never leaks an internal reason) and self-sufficient — it tells the model exactly what to do.
 */
internal const val ACT_REOBSERVE_MESSAGE =
    "Call ui_observe first: there is no current screen grounding this turn, and a target id is only " +
        "valid for the snapshot it appears in. Observe, then act on a tid from that snapshot."

/**
 * An act was refused (capability denied, selector ambiguous/unresolved, or malformed args). Mirrors
 * [OBSERVE_DENIED_MESSAGE]'s style: deliberately vague — the internal
 * [me.rerere.automation.act.ActDenyReason] is NEVER surfaced to the untrusted-content-driven model.
 */
internal const val ACT_DENIED_MESSAGE =
    "Action denied: it is outside the granted automation scope, could not be matched to a single " +
        "element, the lease has expired, or the request was malformed. Re-observe the screen and " +
        "either pick a clearer target or stop the automation."

/**
 * The grounding moved under the act (the screen changed since the last ui_observe, or the bound
 * target did not re-resolve to exactly one live node). The model must re-observe and re-decide —
 * NEVER replay the stale act. Vague (no internal reason leaked). Used by `staleResponse` ONLY as the
 * last-resort text when even the authorized re-capture is denied; on every other stale the fresh
 * current screen is folded into the response instead.
 */
internal const val ACT_STALE_MESSAGE =
    "The screen changed since your last ui_observe, so that action was not applied. Call ui_observe " +
        "again to get a fresh snapshot, then decide the next step from the current screen."

/**
 * A stale-state outcome that CARRIES a fresh snapshot: the bound target did not re-resolve to
 * exactly one live node, but the backend captured the current screen. Vague about the reason (never
 * leaks the binding-mismatch internals), self-sufficient (the rendered fresh table follows), and
 * steers the model to re-decide from the current screen rather than replay the stale act.
 */
internal const val ACT_STALE_REDECIDE_MESSAGE =
    "The element you tried to act on did not match exactly one live element on the current screen, " +
        "so that action was not applied. A fresh snapshot of the current screen follows — re-decide " +
        "the next step from it (do not replay the previous action)."

/**
 * The global-nav (ui_global) analogue of [ACT_STALE_REDECIDE_MESSAGE]. A global nav has NO target
 * element, so the element-mismatch wording is wrong and misleads the model into hunting for a tid.
 * Used when a BACK/HOME/RECENTS left the screen on a different surface than expected (it may have
 * already completed, or moved to another app) — the current screen follows, re-decide from it.
 */
internal const val ACT_STALE_GLOBAL_REDECIDE_MESSAGE =
    "The navigation did not leave the screen in the expected state — it may have already completed, " +
        "or moved to a different surface. A fresh snapshot of the current screen follows — re-decide " +
        "the next step from it (do not blindly repeat the navigation)."

/**
 * Why a `ui_observe` call returned nothing. Self-sufficient text (the model never sees the
 * [me.rerere.automation.cap.DenyReason] enum): observation was denied or the lease is paused, so the
 * agent must stop and re-ground rather than retry blindly. Kept deliberately vague — leaking the
 * exact deny reason to an untrusted-content-driven model is needless attack surface.
 */
internal const val OBSERVE_DENIED_MESSAGE =
    "ui_observe denied: the current screen is outside the granted automation scope, the task lease " +
        "has expired, or the request was malformed. Stop the automation and ask the user to widen " +
        "the scope or restart the task."

/**
 * Render a [UiSnapshot] into the compact, self-sufficient action table the model reads (design §4).
 * Pure and deterministic — JVM-unit-tested directly. The table is the MANDATORY channel: a header
 * line (stateSeq / foregroundPkg / screenState) plus one line per target (tid · role · flags · text).
 * No coordinates, no host package, no password plaintext reach here — the proven
 * [me.rerere.automation.observe.SnapshotProjector] already stripped them upstream.
 */
internal fun renderCompactSnapshot(snapshot: UiSnapshot): String = buildString {
    append("stateSeq=").append(snapshot.stateSeq)
    append(" foregroundPkg=").append(snapshot.foregroundPkg)
    append(" screenState=").append(snapshot.screenState.name)
    append('\n')
    if (snapshot.targets.isEmpty()) {
        append("(no actionable targets)")
        return@buildString
    }
    append("targets:")
    for (target in snapshot.targets) {
        append('\n')
        append('#').append(target.tid)
        append(" · ").append(target.role)
        if (target.flags.isNotEmpty()) {
            append(" · ").append(target.flags.joinToString(",") { it.name })
        }
        target.text?.takeIf { it.isNotBlank() }?.let { append(" · \"").append(it).append('"') }
        // formKey is the input-field stable-key axis (#198 slice 9): emit it so the model can address
        // an editable field via {formKey:...} (the projector sets it ONLY for editable nodes). Without
        // this line the model can never LEARN a field's formKey from observe output, making the
        // advertised by-formKey selector unreachable (a half-wired axis). Rendered before key= so the
        // two stable keys read consistently.
        target.formKey?.takeIf { it.isNotBlank() }?.let { append(" · form=").append(it) }
        target.semanticKey?.takeIf { it.isNotBlank() }?.let { append(" · key=").append(it) }
    }
}
