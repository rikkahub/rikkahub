package me.rerere.automation.cap

import me.rerere.common.android.redactAndTruncate
import java.util.concurrent.CopyOnWriteArrayList

/** One immutable admission decision record. Args are stored as length-only metadata, never raw. */
data class AuditEntry(
    val seq: Long,
    val verb: Verb,
    /** Target package, if known. Package names are not secret; node text/args are and are redacted. */
    val targetPkg: String?,
    val decision: Decision,
    val reason: DenyReason?,
    /** Redacted arg metadata (length-only via :common policy) — NEVER the raw JSON (design P25). */
    val redactedArgs: String,
)

enum class Decision { ADMIT, DENY }

/**
 * Why a decision was DENY. ADMIT carries a null reason. Enumerated so the fail-closed branches are
 * explicit and testable rather than a free-text string.
 */
enum class DenyReason {
    LEASE_EXPIRED,
    REVOKED,
    SURFACE_NOT_ALLOWED,
    VERB_NOT_ALLOWED,
    SINK_NOT_IN_BUDGET,
    SENSITIVE_BLOCKED,
    STEP_BUDGET_EXCEEDED,
    MALFORMED,
}

/**
 * Append-only, in-memory, session-scoped audit ledger (design §6, property P25; Open-Q3 lean =
 * in-memory, so v1 has no Room migration — the DB stays at v22).
 *
 * Invariants:
 *  - Exactly ONE entry per authorize decision (the guard appends once per call).
 *  - Append-only: [append] only grows the log; there is no remove/mutate API. [entries] returns an
 *    immutable copy so a caller cannot shrink it.
 *  - Redacted: arg text is reduced to length-only metadata via :common `redactAndTruncate`, so no
 *    secret/password/raw-arg ever reaches the ledger (the gate's "redacted, not merely append-only"
 *    requirement).
 *
 * Thread-safe via CopyOnWriteArrayList (revoke/audit may interleave with backend work).
 */
class Audit {
    private val log = CopyOnWriteArrayList<AuditEntry>()
    private var seq: Long = 0L

    @Synchronized
    fun append(
        verb: Verb,
        targetPkg: String?,
        decision: Decision,
        reason: DenyReason?,
        rawArgs: String?,
    ): AuditEntry {
        val entry = AuditEntry(
            seq = seq++,
            verb = verb,
            targetPkg = targetPkg,
            decision = decision,
            reason = reason,
            redactedArgs = redactAndTruncate(rawArgs),
        )
        log.add(entry)
        return entry
    }

    /** Immutable view; callers cannot shrink or mutate the ledger (append-only, P25). */
    fun entries(): List<AuditEntry> = log.toList()

    val size: Int get() = log.size
}
