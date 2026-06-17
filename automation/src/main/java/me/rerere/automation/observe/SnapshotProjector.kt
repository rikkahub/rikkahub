package me.rerere.automation.observe

import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow

/**
 * Projects a backend's raw window forest into the compact, coordinate-free [UiSnapshot] the model
 * is allowed to see (#187 design §4). The output is ~10x smaller than the raw tree (an "action
 * table", not a DOM dump) and is the trust boundary: everything the projector strips (host app,
 * password text, secure windows, raw coordinates) can never reach the model.
 *
 * Rules (design §4 / properties P1–P7):
 *  - Host package [HOST_PACKAGE] is excluded entirely (P2). If it is the foreground, the snapshot
 *    is [ScreenState.FOREGROUND_IS_HOST] with no targets (the agent must pause/re-ground, P12).
 *  - FLAG_SECURE window or an empty forest ⇒ [ScreenState.SECURE_WINDOW], no targets (design I1).
 *  - A node is a target iff `(visible && hasArea) || hasId || hasText`; recursion still descends
 *    into invisible containers so a visible descendant is not lost (P4).
 *  - A password node's real text is replaced with bullets (P1).
 *  - [UiTarget.tid] is a dense index, unique within the snapshot and only valid for this stateSeq
 *    (P3). Projection is deterministic/idempotent: a pre-order walk gives stable ordering (P5).
 *
 * Pure and stateless — same [RawTree] in ⇒ identical [UiSnapshot] out (no [System] reads).
 */
class SnapshotProjector {

    /**
     * @param includeHost when true (YOLO only), the host self-exclusion is lifted: the host may be the
     *   foreground (no [ScreenState.FOREGROUND_IS_HOST] short-circuit) and host windows are eligible.
     *   Default false preserves the P2/P12 host exclusion for every scoped (non-YOLO) caller.
     */
    fun project(tree: RawTree, allowedPackages: Set<String>, includeHost: Boolean = false): UiSnapshot {
        val foregroundIsHost = !includeHost && tree.foregroundPkg == HOST_PACKAGE

        // Windows the model may see: never the host (unless includeHost), never a secure window's contents.
        val visibleWindows = tree.windows.filter { isWindowEligible(it.pkg, it.systemWindow, allowedPackages, includeHost) }

        val screenState = when {
            foregroundIsHost -> ScreenState.FOREGROUND_IS_HOST
            visibleWindows.isEmpty() -> ScreenState.SECURE_WINDOW
            visibleWindows.any { it.secure } && visibleWindows.all { it.secure } ->
                ScreenState.SECURE_WINDOW
            visibleWindows.any { it.systemWindow } -> ScreenState.DIALOG
            else -> ScreenState.READY
        }

        val targets = if (screenState == ScreenState.FOREGROUND_IS_HOST ||
            screenState == ScreenState.SECURE_WINDOW
        ) {
            emptyList()
        } else {
            // Deterministic order: windows as given, each tree pre-order. Dense tid assigned last so
            // it is unique and stable for this snapshot (P3/P5). Secure windows contribute nothing.
            // Each collected node carries its owning window's systemWindow flag so the act path can
            // enforce "system UI is observable but non-actionable" (I-act-3) without re-deriving
            // provenance from coordinates the model never sees.
            val collected = ArrayList<CollectedNode>()
            for (window in visibleWindows) {
                if (window.secure) continue
                window.root?.let { collect(it, window.systemWindow, window.pkg, collected) }
            }
            collected.mapIndexed { index, it -> toTarget(index, it.node, it.systemWindow, it.sourcePackage) }
        }

        return UiSnapshot(
            stateSeq = tree.stateSeq,
            foregroundPkg = tree.foregroundPkg,
            screenState = screenState,
            targets = targets,
        )
    }

    /** A projected node paired with its owning window's system-UI provenance (carried so the act
     * path can enforce I-act-3; window identity is otherwise lost once nodes are flattened). */
    private data class CollectedNode(val node: RawNode, val systemWindow: Boolean, val sourcePackage: String)

    /** Pre-order collection: a node is emitted iff it passes the projection rule; recursion always
     * continues into children (incl. invisible containers) so passing descendants survive (P4). */
    private fun collect(node: RawNode, systemWindow: Boolean, sourcePackage: String, out: MutableList<CollectedNode>) {
        if (isTarget(node)) out.add(CollectedNode(node, systemWindow, sourcePackage))
        for (child in node.children) collect(child, systemWindow, sourcePackage, out)
    }

    private fun isTarget(node: RawNode): Boolean =
        (node.visible && node.hasArea) || node.hasId || node.hasText

    private fun toTarget(tid: Int, node: RawNode, systemWindow: Boolean, sourcePackage: String): UiTarget {
        val flags = buildSet {
            if (node.clickable) add(UiFlag.CLICK)
            if (node.editable) add(UiFlag.EDIT)
            if (node.scrollable) add(UiFlag.SCROLL)
            if (node.checkable && node.checked) add(UiFlag.CHECKED)
            if (node.password) add(UiFlag.PASSWORD)
        }
        // Password text is never surfaced as-is (design I1/P1): mask to bullets sized to the input
        // so the model knows a value is present without learning it.
        val rawText = node.text ?: node.contentDescription
        val text = if (node.password) {
            rawText?.let { "•".repeat(it.length.coerceAtMost(MAX_MASK_LENGTH)) }
        } else {
            rawText
        }
        return UiTarget(
            tid = tid,
            role = node.className ?: "node",
            text = text,
            flags = flags,
            semanticKey = node.contentDescription?.takeIf { it.isNotEmpty() },
            formKey = node.resourceId?.takeIf { it.isNotEmpty() && node.editable },
            // Ground-truth editable VALUE for the act path's P9 no-op: the literal node.text, NEVER the
            // contentDescription fallback [text] uses (a blank field's hint is not its value) and NEVER
            // a password (its value must not leak even to internal plumbing). Null for non-editable
            // nodes — only an editable target can be a set_text postcondition.
            editableText = if (node.editable && !node.password) node.text else null,
            // Raw view id for ALL nodes (internal, never rendered): the submit-class classifier's third
            // input, so an icon-only commit button (no text/contentDescription, id like …:id/pay_button)
            // is still gated behind confirmation (#198 slice 11). formKey above stays editable-only.
            viewId = node.resourceId?.takeIf { it.isNotEmpty() },
            systemWindow = systemWindow,
            sourcePackage = sourcePackage,
        )
    }

    companion object {
        const val HOST_PACKAGE = "me.rerere.rikkahub"
        private const val MAX_MASK_LENGTH = 32

        /**
         * Shared predicate for deciding whether a window is traversable by both projection and act replay.
         * The host window is never visible to the model UNLESS [includeHost] (YOLO). Non-host windows are
         * visible when explicitly allow-listed or explicitly marked system. [includeHost] defaults false so
         * every scoped caller keeps the host exclusion.
         */
        fun isWindowEligible(
            pkg: String,
            systemWindow: Boolean,
            allowedPackages: Set<String>,
            includeHost: Boolean = false,
        ): Boolean =
            (includeHost || pkg != HOST_PACKAGE) && (pkg in allowedPackages || systemWindow)
    }
}
