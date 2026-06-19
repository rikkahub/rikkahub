package me.rerere.rikkahub.data.ai.shellrun

import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceCwdPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Per-conversation, IN-MEMORY tracker for the LLM `workspace_shell`'s drifting working directory (the
 * project-jailed cwd feature). Each blocking `workspace_shell` command starts at the tracked cwd; if it
 * ends INSIDE the project jail the tracked cwd persists there, if it leaves the jail the cwd reverts to
 * the latest in-jail value. State is keyed by (workspaceId, conversationId) and lives only in memory —
 * it resets to the project dir on process death (a deliberate scope choice; no Room/durable state).
 *
 * Stores FILES-relative cwds only (the form the policy/manager speak); "" == the files root.
 */
class ShellCwdTracker {
    private val cwdByKey = ConcurrentHashMap<String, String>()

    fun get(workspaceId: String, conversationId: Uuid): String? =
        cwdByKey[key(workspaceId, conversationId)]

    fun set(workspaceId: String, conversationId: Uuid, cwd: String) {
        cwdByKey[key(workspaceId, conversationId)] = cwd
    }

    private fun key(workspaceId: String, conversationId: Uuid): String = "$workspaceId/$conversationId"
}

/** The fate of the tracked cwd after a command — reported to the model so a silent revert is visible. */
enum class ShellCwdStatus { PERSISTED, UNCHANGED, REVERTED, UNKNOWN }

/** The pure decision: the next FILES-relative tracked cwd + what happened to it. */
data class ShellCwdDecision(val cwd: String, val status: ShellCwdStatus)

/** A blocking `workspace_shell` result plus the post-command jailed cwd (canonical) and its fate. */
data class ShellCwdOutcome(
    val result: WorkspaceCommandResult,
    /** The canonical `/workspace/...` cwd the NEXT command will start in. */
    val cwd: String,
    val status: ShellCwdStatus,
)

/**
 * A fresh per-call capture token. RANDOM so a command's own output cannot spoof the cwd line (the
 * captured cwd is untrusted-advisory; the random token plus jail validation makes a spoof harmless).
 * The token is handed to the shell runner, which emits `<token><pwd>` as a postlude OUTSIDE the eval'd
 * user command (so malformed user syntax can't consume it); [extractFinalCwd] parses it back out.
 */
fun newCwdCaptureToken(): String = "__rikkahub_cwd_${Uuid.random()}__"

/**
 * Split a captured stdout into (the model-facing stdout, the final physical cwd the postlude printed).
 * Finds the LAST [token] occurrence (the postlude is the final output); everything before it is the
 * user's byte-exact stdout and the remainder of that line is the pwd. No token — the postlude never ran
 * (the user command `exec`/`exit`ed), or it was truncated past the 128 KiB stdout cap — yields
 * (stdout unchanged, null).
 */
fun extractFinalCwd(stdout: String, token: String): Pair<String, String?> {
    val idx = stdout.lastIndexOf(token)
    if (idx < 0) return stdout to null
    val afterToken = stdout.substring(idx + token.length)
    val pwd = afterToken.substringBefore('\n').trim()
    // Strip ONLY the marker line (token + pwd + its newline), preserving any bytes a background child
    // that outlived the foreground command wrote AFTER the EXIT-trap marker.
    val afterMarker = afterToken.substringAfter('\n', missingDelimiterValue = "")
    return (stdout.substring(0, idx) + afterMarker) to pwd.ifBlank { null }
}

/**
 * The project-jail state machine (PURE). Decide the next tracked cwd from a command's captured final
 * cwd, given the jail [floor] and the in-jail [base] the command started from (the revert target).
 * [finalCwd] is the parsed FILES-relative physical cwd, or null when the captured cwd was OUTSIDE the
 * `/workspace` mount entirely (e.g. `cd /etc`) — both that and an in-`/workspace`-but-out-of-jail path
 * mean the cd did not stick. The caller maps the SEPARATE "no cwd captured at all" case to UNKNOWN
 * before calling this (so [ShellCwdStatus.UNKNOWN] is never returned here):
 *  - null (captured outside /workspace) -> keep [base], REVERTED
 *  - in /workspace but outside the jail  -> keep [base], REVERTED
 *  - == base                            -> keep [base], UNCHANGED
 *  - inside the jail, moved              -> [finalCwd], PERSISTED
 */
fun decideTrackedCwd(floor: String, base: String, finalCwd: String?): ShellCwdDecision = when {
    finalCwd == null -> ShellCwdDecision(base, ShellCwdStatus.REVERTED)
    !WorkspaceCwdPolicy.isWithin(floor, finalCwd) -> ShellCwdDecision(base, ShellCwdStatus.REVERTED)
    finalCwd == base -> ShellCwdDecision(base, ShellCwdStatus.UNCHANGED)
    else -> ShellCwdDecision(finalCwd, ShellCwdStatus.PERSISTED)
}
