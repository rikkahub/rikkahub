package me.rerere.automation.observe

import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import java.security.MessageDigest

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
 * The eyes-open hybrid tap design (this change) computes two new non-rendered fields per target:
 *  - [UiTarget.structuralPath]: the node's zero-based raw child-index path from its window root.
 *  - [UiTarget.structuralFingerprint]: a SHA-256 of the node's structural shape (see
 *    [computeStructuralFingerprint]). These two — plus [UiTarget.windowId] — are the axes a strict
 *    [TargetBinding] re-resolves against at dispatch time, replacing the old blind `(stateSeq, tid)`
 *    token. The path is in RAW child order (before the projection rule filters), so a live dispatch
 *    walk that re-creates the same indices lands on the same structural node.
 *
 * Pure and stateless — same [RawTree] in ⇒ identical [UiSnapshot] out (no [System] reads). The
 * shape helpers ([isTarget], [buildTarget], [computeStructuralFingerprint], [NodeFieldSnapshot]) are
 * exposed so a real backend's live dispatch walk re-computes the SAME [UiTarget] fields and matches
 * a [TargetBinding] byte-for-byte (the load-bearing parity for atomic fresh resolve + dispatch).
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
            // Each collected node carries its owning window's systemWindow flag + windowId + raw child
            // path so the act path can build a strict TargetBinding (window identity + structural
            // position) AND enforce "system UI is observable but non-actionable" (I-act-3) without
            // re-deriving provenance from coordinates the model never sees.
            val collected = ArrayList<CollectedNode>()
            for (window in visibleWindows) {
                if (window.secure) continue
                val windowId = window.windowId
                window.root?.let { collect(it, window.systemWindow, window.pkg, windowId, emptyList(), collected) }
            }
            collected.mapIndexed { index, it -> buildTarget(index, it.node, it.systemWindow, it.sourcePackage, it.windowId, it.path) }
        }

        return UiSnapshot(
            stateSeq = tree.stateSeq,
            foregroundPkg = tree.foregroundPkg,
            screenState = screenState,
            targets = targets,
        )
    }

    /** A projected node paired with its owning window's provenance + structural position. */
    private data class CollectedNode(
        val node: RawNode,
        val systemWindow: Boolean,
        val sourcePackage: String,
        val windowId: Int,
        val path: List<Int>,
    )

    /** Pre-order collection: a node is emitted iff it passes the projection rule; recursion always
     * continues into children (incl. invisible containers) so passing descendants survive (P4). The
     * path is the RAW child-index chain (before the projection rule filters), so a non-projected
     * container between the root and a target still contributes a slot — the live dispatch walk
     * re-creates the same indices. */
    private fun collect(
        node: RawNode,
        systemWindow: Boolean,
        sourcePackage: String,
        windowId: Int,
        path: List<Int>,
        out: MutableList<CollectedNode>,
    ) {
        if (isTarget(node)) out.add(CollectedNode(node, systemWindow, sourcePackage, windowId, path))
        node.children.forEachIndexed { i, child ->
            collect(child, systemWindow, sourcePackage, windowId, path + i, out)
        }
    }

    companion object {
        const val HOST_PACKAGE = "me.rerere.rikkahub"
        private const val MAX_MASK_LENGTH = 32

        /** The fingerprint scheme version — prepended to every digest so a future algorithm change is
         *  unambiguous (two targets with different versions never collide). */
        const val FINGERPRINT_VERSION = "target-binding:v1"

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

        /** The projection rule (design §4): a node is a target iff `(visible && hasArea) || hasId || hasText`. */
        fun isTarget(node: RawNode): Boolean =
            (node.visible && node.hasArea) || node.hasId || node.hasText

        /** The same rule over the live-node scalar fields a real backend reads (parity with [isTarget]). */
        fun isTarget(
            visible: Boolean,
            hasArea: Boolean,
            hasId: Boolean,
            hasText: Boolean,
        ): Boolean = (visible && hasArea) || hasId || hasText

        /**
         * A structural snapshot of a node's identity fields used for fingerprinting. Deliberately carries
         * NO raw text BYTES — only [textLength] (or -1 for null) — so a value the model never sees cannot
         * leak through the binding, and a same-shape text edit does not by itself stale a bound dispatch.
         * Shared between the projector (from [RawNode]) and a real backend's live walk (from its native
         * node type) so both compute byte-for-byte equal fingerprints.
         */
        data class NodeFieldSnapshot(
            val className: String?,
            val resourceId: String?,
            val contentDescription: String?,
            val visible: Boolean,
            val hasArea: Boolean,
            val clickable: Boolean,
            val editable: Boolean,
            val scrollable: Boolean,
            val checkable: Boolean,
            val checked: Boolean,
            val password: Boolean,
            /** Text length, or -1 when the node's text is null (no raw bytes are ever digested). */
            val textLength: Int,
            val childCount: Int,
        )

        /**
         * Project a [RawNode]'s identity fields into a [NodeFieldSnapshot]. The user-content `text`
         * field contributes only its LENGTH (never its bytes), so editing a value does not change the
         * digest; the structural identity strings (className / resourceId / contentDescription) ARE
         * carried — they are stable identity axes (contentDescription is also the strict-matched
         * [UiTarget.semanticKey]), not user-entered content.
         */
        fun RawNode.toFieldSnapshot(): NodeFieldSnapshot = NodeFieldSnapshot(
            className = className,
            resourceId = resourceId,
            contentDescription = contentDescription,
            visible = visible,
            hasArea = hasArea,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            checkable = checkable,
            checked = checked,
            password = password,
            textLength = text?.length ?: -1,
            childCount = children.size,
        )

        /**
         * The structural fingerprint (spec §5): SHA-256 over the window id + package + system flag +
         * structural path + the node's FULL subtree shape (the node plus every descendant, pre-order in
         * raw child order). The user-content `text` field contributes only its LENGTH (never its bytes),
         * so a value edit does not stale a binding; the structural identity strings (className /
         * resourceId / contentDescription) ARE digested as identity axes. Coordinates are never carried
         * (only hasArea). Every field is length-prefix framed (see [str]/[num]/[flag]) so distinct field
         * tuples can never collide. Pure & total — same inputs ⇒ identical digest, so the projector and a
         * live dispatch walk agree.
         *
         * The digest is recursive over the whole subtree (not just immediate children): the structural
         * path pins the node's position, but a clickable container can keep an unchanged immediate-child
         * shape while a DEEP descendant is swapped (a same-shell-different-guts replacement). Digesting
         * the full subtree — with each node's childCount and a parenthesized child block making the tree
         * shape unambiguous — refuses a binding whose descendant structure changed since the grounding.
         */
        fun computeStructuralFingerprint(
            windowId: Int,
            sourcePackage: String,
            systemWindow: Boolean,
            structuralPath: List<Int>,
            node: RawNode,
        ): String {
            val sb = StringBuilder()
            sb.str(FINGERPRINT_VERSION)
            sb.num(windowId)
            sb.str(sourcePackage)
            sb.flag(systemWindow)
            sb.num(structuralPath.size)
            for (segment in structuralPath) sb.num(segment)
            appendSubtree(sb, node)
            return sha256(sb.toString())
        }

        /**
         * Pre-order recursion over [n]'s subtree: the node's own shape, then a parenthesized block of
         * each child's subtree in raw child order. The parens + per-node childCount make differing tree
         * shapes serialize to different strings (no two distinct subtrees collide). The user-content
         * `text` field is digested as length only, never its bytes (see [appendShape]).
         */
        private fun appendSubtree(sb: StringBuilder, n: RawNode) {
            appendShape(sb, n.toFieldSnapshot())
            sb.append('(')
            for (child in n.children) {
                sb.append('|')
                appendSubtree(sb, child)
            }
            sb.append(')')
        }

        private fun appendShape(sb: StringBuilder, n: NodeFieldSnapshot) {
            sb.str(n.className.orEmpty())
            sb.str(n.resourceId.orEmpty())
            sb.str(n.contentDescription.orEmpty())
            sb.flag(n.visible)
            sb.flag(n.hasArea)
            sb.flag(n.clickable)
            sb.flag(n.editable)
            sb.flag(n.scrollable)
            sb.flag(n.checkable)
            sb.flag(n.checked)
            sb.flag(n.password)
            sb.num(n.textLength)
            sb.num(n.childCount)
        }

        // Unambiguous field framing for the fingerprint preimage: every variable-length component is
        // length-prefixed and every component ends with the FIELD_SEP control char, so distinct field
        // tuples can NEVER serialize to the same preimage (e.g. className="ab"/resourceId="c" vs
        // className="a"/resourceId="bc"). Without this framing, a same-path in-place replacement could
        // collide on the digest and defeat the strict TargetBinding identity axis.
        private const val FIELD_SEP = '\u0001'

        /** Length-prefixed string: `<len>:<chars>` then [FIELD_SEP] — injective over all strings. */
        private fun StringBuilder.str(s: String) {
            append(s.length).append(':').append(s).append(FIELD_SEP)
        }

        /** Int field followed by [FIELD_SEP]. */
        private fun StringBuilder.num(n: Int) {
            append(n).append(FIELD_SEP)
        }

        /** Boolean field as 1/0 followed by [FIELD_SEP]. */
        private fun StringBuilder.flag(b: Boolean) {
            append(if (b) '1' else '0').append(FIELD_SEP)
        }

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Build a [UiTarget] from a raw node + its window context + structural position. The reusable
         * target-shape helper: the projector uses it during projection, and a real backend's live dispatch
         * walk uses it to re-compute the SAME fields on a candidate node so a [TargetBinding] matches
         * byte-for-byte. Pure & total.
         */
        fun buildTarget(
            tid: Int,
            node: RawNode,
            systemWindow: Boolean,
            sourcePackage: String,
            windowId: Int,
            structuralPath: List<Int>,
        ): UiTarget {
            val flags = buildSet {
                if (node.clickable) add(UiFlag.CLICK)
                if (node.editable) add(UiFlag.EDIT)
                if (node.scrollable) add(UiFlag.SCROLL)
                if (node.checkable && node.checked) add(UiFlag.CHECKED)
                if (node.password) add(UiFlag.PASSWORD)
            }
            // Prefer the window-level id; fall back to the node's own window id when the window-level
            // id is unavailable (spec §3 step 8 / RawNode.windowId KDoc). A binding that collapses to
            // UNKNOWN_WINDOW_ID could match a same-shaped node in a different window it could otherwise
            // have distinguished — so the same effective id MUST flow into BOTH the fingerprint and the
            // UiTarget.windowId axis, and the live dispatch walk computes it identically (both call
            // this helper) so a TargetBinding matches byte-for-byte. "Unavailable" is ANY negative value:
            // the framework returns -1 for an unknown window id and the spec's UNKNOWN_WINDOW_ID is
            // Int.MIN_VALUE — treating both (and any negative) as unknown stops a -1 from being matched
            // as a real distinguishing window id.
            val effectiveWindowId = when {
                windowId >= 0 -> windowId
                node.windowId >= 0 -> node.windowId
                else -> UNKNOWN_WINDOW_ID
            }
            // Model-facing display text. Two values must NEVER reach the model: a password (masked to
            // bullets, design I1/P1) and a non-password EDITABLE field's CURRENT VALUE — an editable
            // field can hold a secret the app never flagged as password (an OTP, a card number, a draft),
            // so its `node.text` stays internal-only (it lives on [UiTarget.editableText] for the P9
            // no-op) and the model sees only the field's label/hint (contentDescription). A non-editable
            // node's text IS a label (a button caption, a status line), so it renders as-is.
            val text = when {
                node.password -> {
                    val rawText = node.text ?: node.contentDescription
                    rawText?.let { "•".repeat(it.length.coerceAtMost(MAX_MASK_LENGTH)) }
                }
                node.editable -> node.contentDescription
                else -> node.text ?: node.contentDescription
            }
            val fingerprint = computeStructuralFingerprint(
                windowId = effectiveWindowId,
                sourcePackage = sourcePackage,
                systemWindow = systemWindow,
                structuralPath = structuralPath,
                node = node,
            )
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
                windowId = effectiveWindowId,
                structuralPath = structuralPath,
                structuralFingerprint = fingerprint,
            )
        }
    }
}
