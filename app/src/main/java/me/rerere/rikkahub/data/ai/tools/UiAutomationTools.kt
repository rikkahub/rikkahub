package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.automation.act.AutomationCore
import me.rerere.automation.cap.AuthRequest
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Decision
import me.rerere.automation.cap.Verb
import me.rerere.automation.observe.UiSnapshot
import me.rerere.rikkahub.data.model.Assistant

/**
 * The per-generation `ui_observe` [Tool] factory — the read-only v1 `:app` surface of #187, built on
 * the already-merged `:automation` capability + observation kernel (#205).
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
 *  - **Guard BEFORE backend (S2):** [Tool.execute] calls [CapabilityGuard.authorize] first and only
 *    reaches [AutomationCore.observe] on ADMIT — the backend is never touched on a DENY.
 *  - **Revoke cancels in-flight + closes the authorize→observe race (I9):** the capture runs inside
 *    [CapabilityGuard.guardInFlight], so a kill-switch `revoke()` cancels the parked observe via the
 *    owning [Job], and a revoke that fires after authorize() but before the backend hit denies
 *    instead of capturing (mirrors the kernel's proven P20).
 *  - **Captured target bound to the authorized target (TOCTOU):** the foreground package is read once
 *    to authorize; after capture the snapshot's `foregroundPkg` is re-asserted to equal it, so a
 *    foreground app switch between the two reads yields a denial, never a snapshot of an unauthorized
 *    app.
 *  - **Authority is closed over, never model-supplied (I2):** the guard is captured here; the model's
 *    JSON args carry no caller context (a JSON-passed lease would be forgeable — gate finding 6).
 *  - **Fail-closed on malformed args (P24):** a non-object argument is reported `malformed=true` so
 *    the guard denies it.
 *  - **needsApproval=false:** the in-chat approval gate is structurally unreachable while another app
 *    is foreground (design constraint 1); OCap is the brake instead.
 *  - **Text-only result (gate A1):** the snapshot is rendered to a single self-sufficient
 *    [UIMessagePart.Text] — never an [UIMessagePart.Image], because most providers drop tool-output
 *    images.
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
                                listOf(UIMessagePart.Text(renderCompactSnapshot(snapshot)))
                            }
                        },
                    )
                }
            },
        ),
    )
}

const val UI_OBSERVE_TOOL_NAME = "ui_observe"

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
