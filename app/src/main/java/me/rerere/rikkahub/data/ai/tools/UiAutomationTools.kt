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
import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.cap.AuthRequest
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Decision
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Verb
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.UiSnapshot
import me.rerere.rikkahub.data.model.Assistant

/**
 * The per-generation UI-automation [Tool] factory — the `:app` surface of #187/#198, built on the
 * already-merged `:automation` capability + observation + act kernel (#205, #211). Exposes
 * `ui_observe` (read-only, #187 v1) plus the lowest-risk nav act verbs `ui_scroll` and `ui_global`
 * (#198 slice 8) over the SAME [AutomationCore]. No write SINK beyond `GLOBAL_NAV` is reachable here:
 * `ui_set_text`/`ui_tap` and the dangerous-sink confirm channel are later slices (9–11).
 *
 * Shape mirrors [me.rerere.rikkahub.data.ai.subagent.buildSpawnTool]: a top-level factory closing
 * over per-conversation context, built ONLY at `ChatService`'s per-generation tool buildList. It is
 * deliberately Android-free (design I10): it takes an [AutomationCore] (over the real
 * `AccessibilityRuntime` backend in production, or a `FakeBackend` in tests) plus a
 * foreground-package supplier — no `android.accessibility` import lives here, only in
 * `service/automation/AccessibilityRuntime`.
 *
 * Safety wiring (the design's hard prerequisites, all enforced here):
 *  - **Default-OFF / empty surface (S1):** returns `emptyList()` unless the assistant explicitly
 *    enabled automation AND a non-null [CapabilityGuard] is supplied. A null guard = no authority.
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
 *    package is re-asserted after capture; for the act path `core.act` re-asserts BOTH the grounded
 *    `stateSeq` and `windowContentHash` before dispatch, so a screen change since the grounding
 *    ui_observe yields a stale-state stop, never an act on a moved target.
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
 */
fun getUiAutomationTools(
    assistant: Assistant,
    guard: CapabilityGuard?,
    core: AutomationCore,
    foregroundPkg: () -> String?,
): List<Tool> {
    // Default-OFF, empty surface (design §2/§5/S1): no activation OR no authority ⇒ no tool at all.
    if (!assistant.uiAutomationEnabled || guard == null) return emptyList()

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
    // so the returned text is always the vague ACT_DENIED_MESSAGE. `grounded` is non-null at every call
    // site (it follows the `grounded ?: return` guard), so its package is the truthful act target.
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
                // S2: authorize BEFORE the backend. Read the foreground package first so the guard
                // decides on the real target (not the post-observe one). Authority is the closed-over
                // guard, never anything in `args`.
                val authorizedPkg = foregroundPkg()
                val request = AuthRequest(
                    verb = Verb.OBSERVE,
                    targetPkg = authorizedPkg,
                    // ui_observe is a read: no sink, no sensitive/system write target.
                    malformed = args !is JsonObject,
                    rawArgs = args.toString(),
                )
                if (guard.authorize(request) == Decision.DENY) {
                    listOf(UIMessagePart.Text(OBSERVE_DENIED_MESSAGE))
                } else {
                    // I9 (revoke cancels in-flight) + close the authorize→observe window: route the
                    // backend capture through the guard's shared RevocationToken. A concurrent
                    // revoke()/kill-switch cancels the parked capture via the owning Job, and a
                    // revoke that fires between authorize() and here lands in onAlreadyRevoked so the
                    // backend is never hit. Mirrors the kernel's proven P20.
                    val job = currentCoroutineContext()[Job]
                    guard.guardInFlight(
                        cancel = { job?.cancel(CancellationException("automation revoked")) },
                        onAlreadyRevoked = { listOf(UIMessagePart.Text(OBSERVE_DENIED_MESSAGE)) },
                        block = {
                            val snapshot = core.observe()
                            // TOCTOU on the authorization target (gate finding): the foreground app
                            // may have switched between the authorize-read above and this capture.
                            // Bind the captured snapshot to the authorized package — if they differ,
                            // we captured an app the guard never admitted, so deny rather than
                            // disclose it (the projector still stripped host/password content, but an
                            // unauthorized foreign app must not be surfaced at all).
                            if (snapshot.foregroundPkg != authorizedPkg) {
                                listOf(UIMessagePart.Text(OBSERVE_DENIED_MESSAGE))
                            } else {
                                // Ground the act tools on this authorized, freshly-captured snapshot:
                                // ui_scroll/ui_global resolve their selector against it and re-assert
                                // its (stateSeq, windowContentHash) before any dispatch (#198 slice 8).
                                grounded = snapshot
                                listOf(UIMessagePart.Text(renderCompactSnapshot(snapshot)))
                            }
                        },
                    )
                }
            },
        ),
        Tool(
            name = UI_SCROLL_TOOL_NAME,
            description = "Scroll an actionable element of the foreground app (other apps), forward " +
                "or backward. You MUST call ui_observe first this turn — a target id is only valid " +
                "for the snapshot it appears in. Select the element by its tid (from the latest " +
                "ui_observe table), by its visible text, or by its semantic key. Returns a fresh " +
                "ui_observe-style snapshot after the scroll; re-read it before the next step.",
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
                // tids are turn-scoped: refuse to act until ui_observe has grounded this turn.
                val snapshot = grounded ?: return@execute listOf(UIMessagePart.Text(ACT_REOBSERVE_MESSAGE))
                // Fail closed on anything we cannot parse into an Act (P24): never build an Act, never
                // touch the backend. Authority is the closed-over guard, never anything in `args` (I2).
                // The refusal is AUDITED (P25) — auditMalformedAct authorizes a malformed SCROLL request
                // so the fail-closed DENY writes a ledger entry, exactly as ui_observe does.
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
                // core.act authorizes internally (S2) and runs guardInFlight (P20) — the tool layer
                // must NOT call authorize/guardInFlight here (would double-audit / break P25).
                when (val outcome = core.act(guard, snapshot, Act.Targeted(selector, kind))) {
                    is ActOutcome.Acted -> {
                        grounded = outcome.snapshot // re-ground for the next act
                        listOf(UIMessagePart.Text(renderCompactSnapshot(outcome.snapshot)))
                    }
                    // Both stops are vague and re-observe-oriented; the deny reason is NEVER leaked.
                    is ActOutcome.Denied -> listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
                    ActOutcome.StaleState -> listOf(UIMessagePart.Text(ACT_STALE_MESSAGE))
                }
            },
        ),
        Tool(
            name = UI_GLOBAL_TOOL_NAME,
            description = "Perform a global navigation on the device: go back, go to the home " +
                "screen, or open recent apps. You MUST call ui_observe first this turn. Returns a " +
                "fresh ui_observe-style snapshot after the navigation; re-read it before the next step.",
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
                val snapshot = grounded ?: return@execute listOf(UIMessagePart.Text(ACT_REOBSERVE_MESSAGE))
                // Fail closed + AUDITED (P24/P25): a malformed ui_global is a GLOBAL act over the
                // GLOBAL_NAV sink, so the ledger entry records that verb/sink as ui_observe does for OBSERVE.
                if (args !is JsonObject) {
                    return@execute auditMalformedAct(Verb.GLOBAL, Sink.GLOBAL_NAV, rawArgs = args.toString())
                }
                val nav = when ((args["direction"] as? JsonPrimitive)?.contentOrNull) {
                    "back" -> GlobalNav.BACK
                    "home" -> GlobalNav.HOME
                    "recents" -> GlobalNav.RECENTS
                    else -> return@execute auditMalformedAct(Verb.GLOBAL, Sink.GLOBAL_NAV, rawArgs = args.toString())
                }
                when (val outcome = core.act(guard, snapshot, Act.Global(nav))) {
                    is ActOutcome.Acted -> {
                        grounded = outcome.snapshot
                        listOf(UIMessagePart.Text(renderCompactSnapshot(outcome.snapshot)))
                    }
                    is ActOutcome.Denied -> listOf(UIMessagePart.Text(ACT_DENIED_MESSAGE))
                    ActOutcome.StaleState -> listOf(UIMessagePart.Text(ACT_STALE_MESSAGE))
                }
            },
        ),
    )
}

/**
 * Parse the model's `selector` arg into a [Selector], or null if it is missing/malformed (fail
 * closed — the caller then denies without building an [Act]). Coordinate-free by construction: only
 * tid / text(+role) / semanticKey are accepted, never a position. `tid` wins if present, then
 * `semanticKey`, then `text`.
 */
private fun parseSelector(element: JsonElement?): Selector? {
    val obj = element as? JsonObject ?: return null
    (obj["tid"] as? JsonPrimitive)?.intOrNull?.let { return Selector.ByTid(it) }
    (obj["semanticKey"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?.let { return Selector.BySemanticKey(it) }
    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
        val role = (obj["role"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        return Selector.ByText(text, role)
    }
    return null
}

const val UI_OBSERVE_TOOL_NAME = "ui_observe"
const val UI_SCROLL_TOOL_NAME = "ui_scroll"
const val UI_GLOBAL_TOOL_NAME = "ui_global"

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
 * The grounding moved under the act (the screen changed since the last ui_observe). The model must
 * re-observe and re-decide — NEVER replay the stale act. Vague (no internal reason leaked).
 */
internal const val ACT_STALE_MESSAGE =
    "The screen changed since your last ui_observe, so that action was not applied. Call ui_observe " +
        "again to get a fresh snapshot, then decide the next step from the current screen."

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
        target.semanticKey?.takeIf { it.isNotBlank() }?.let { append(" · key=").append(it) }
    }
}
