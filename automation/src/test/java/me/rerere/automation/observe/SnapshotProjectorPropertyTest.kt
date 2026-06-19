package me.rerere.automation.observe

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Projector PBT (#187 design §8, properties P1–P7). Runs over [arbRawTree], which injects password
 * nodes, the host package, duplicate ids, and noise containers.
 */
class SnapshotProjectorPropertyTest {

    private val projector = SnapshotProjector()
    private val grantedPkg = "com.example.app"
    private val foreignPkg = "com.example.foreign"

    private fun project(tree: RawTree): UiSnapshot {
        val allow = tree.windows.firstOrNull { it.pkg != SnapshotProjector.HOST_PACKAGE }?.pkg ?: grantedPkg
        return projector.project(tree, setOf(allow))
    }

    private fun project(tree: RawTree, allowed: String): UiSnapshot =
        projector.project(tree, setOf(allowed))

    private fun addPackageWindowIfMissing(tree: RawTree, pkg: String, root: RawNode = treeSeedNode()): RawTree {
        return if (tree.windows.any { it.pkg == pkg }) {
            tree
        } else {
            tree.copy(
                windows = tree.windows + RawWindow(pkg = pkg, root = root),
            )
        }
    }

    private fun treeSeedNode(): RawNode = RawNode(
        resourceId = "id/grant",
        text = "seed",
        className = "TextView",
        visible = true,
        hasArea = true,
    )

    // ---- P1: a password node's REAL text never appears in the projection ----
    @Test
    fun `P1 password text is never leaked`() {
        runBlocking {
            // Force a password field carrying a known secret somewhere in a non-host window. The
            // sentinel is deliberately NOT in the generator's text pool, so the only way it could
            // appear in the projection is a genuine password leak.
            val secret = "PW_SENTINEL_Zx9Qv"
            checkAll(300, arbRawTree(maxDepth = 3)) { tree ->
                val withSecret = injectPasswordSecret(tree, secret)
                val snap = project(withSecret)
                snap.targets.forEach { t ->
                    assertFalse(
                        "password text leaked in tid=${t.tid}",
                        t.text?.contains(secret) == true,
                    )
                    // editableText carries the ground-truth field VALUE for the act path's P9 no-op (#198 slice 9);
                    // a password value must never escape through it either (the projector nulls it for
                    // password nodes). Pinned so a future change that drops the !node.password guard leaks.
                    assertFalse(
                        "password value leaked via editableText in tid=${t.tid}",
                        t.editableText?.contains(secret) == true,
                    )
                }
            }
        }
    }

    // ---- P2: the host package never appears as a target source ----
    @Test
    fun `P2 host package never contributes targets`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 3)) { tree ->
                val snap = project(tree)
                // Build the set of texts that ONLY exist under the host window; none may appear.
                val hostOnlyTexts = collectTexts(tree.windows.filter { it.pkg == HOST }) -
                    collectTexts(tree.windows.filter { it.pkg != HOST })
                val projectedTexts = snap.targets.mapNotNull { it.text }.toSet()
                hostOnlyTexts.forEach { ht ->
                    assertFalse("host-only text '$ht' leaked", projectedTexts.contains(ht))
                }
            }
        }
    }

    @Test
    fun `allowlist invariant filters foreign windows and hides all foreign projected fields`() {
        val tree = RawTree(
            stateSeq = 171L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    root = RawNode(text = "granted", className = "TextView", visible = true, hasArea = true),
                ),
                RawWindow(
                    pkg = foreignPkg,
                    root = RawNode(
                        resourceId = "id/forbidden",
                        text = "FORBIDDEN_TEXT",
                        contentDescription = "forbidden-key",
                        className = "EditText",
                        visible = true,
                        hasArea = true,
                        editable = true,
                        children = listOf(
                            RawNode(text = "forbidden-editable", className = "EditText", editable = true, hasArea = true),
                        ),
                    ),
                ),
            ),
        )

        val snap = project(tree, grantedPkg)
        assertEquals(
            "non-system foreign windows must be omitted from projection",
            emptyList<UiTarget>(),
            snap.targets.filter { it.sourcePackage == foreignPkg },
        )
    }

    @Test
    fun `forgery-looking packageinstallers are omitted unless marked system`() {
        val tree = RawTree(
            stateSeq = 172L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    root = RawNode(text = "Granted", className = "TextView", visible = true, hasArea = true),
                ),
                RawWindow(
                    pkg = "com.evil.packageinstaller",
                    systemWindow = false,
                    root = RawNode(text = "Forged overlay", className = "TextView", visible = true, hasArea = true),
                ),
            ),
        )

        val snap = project(tree, grantedPkg)
        assertEquals(
            "a packageinstaller-like impostor without system-systemWindow must stay hidden",
            emptyList<UiTarget>(),
            snap.targets.filter { it.sourcePackage == "com.evil.packageinstaller" },
        )
    }

    @Test
    fun `isWindowEligible keeps host excluded and requires either allowlist or system window`() {
        assertFalse(
            "a host window is never eligible even when on the allowlist",
            SnapshotProjector.isWindowEligible(SnapshotProjector.HOST_PACKAGE, true, setOf(SnapshotProjector.HOST_PACKAGE)),
        )
        assertFalse(
            "a packageinstaller-looking impostor is ineligible without the system-window flag",
            SnapshotProjector.isWindowEligible("com.evil.packageinstaller", false, setOf(grantedPkg)),
        )
        assertTrue(
            "a system window can be visible without allowlist",
            SnapshotProjector.isWindowEligible("com.android.systemui", true, emptySet()),
        )
    }

    // ---- includeHost (YOLO): the host self-exclusion is lifted ONLY when includeHost=true ----
    @Test
    fun `isWindowEligible includeHost lifts the host exclusion`() {
        // Default (scoped) keeps the host out even when allow-listed (regression-pinned above).
        assertFalse(
            "host stays excluded with includeHost=false",
            SnapshotProjector.isWindowEligible(SnapshotProjector.HOST_PACKAGE, false, setOf(SnapshotProjector.HOST_PACKAGE)),
        )
        // YOLO: the host becomes eligible when it is in the (singleton) allow-list.
        assertTrue(
            "host is eligible with includeHost=true and host in allow-list",
            SnapshotProjector.isWindowEligible(
                SnapshotProjector.HOST_PACKAGE, false, setOf(SnapshotProjector.HOST_PACKAGE), includeHost = true,
            ),
        )
        // includeHost does NOT widen the package set: a foreign, non-system pkg not in the allow-list
        // is still ineligible even under includeHost (only the host clause is relaxed).
        assertFalse(
            "includeHost must not admit a non-allow-listed foreign window",
            SnapshotProjector.isWindowEligible(foreignPkg, false, setOf(grantedPkg), includeHost = true),
        )
    }

    @Test
    fun `includeHost projects the host foreground instead of FOREGROUND_IS_HOST`() {
        val tree = RawTree(
            stateSeq = 9L,
            foregroundPkg = SnapshotProjector.HOST_PACKAGE,
            windows = listOf(
                RawWindow(
                    pkg = SnapshotProjector.HOST_PACKAGE,
                    root = RawNode(text = "host-target", className = "TextView", visible = true, hasArea = true),
                ),
            ),
        )
        // Scoped (default): host foreground short-circuits to FOREGROUND_IS_HOST, no targets (P2/P12).
        val scoped = projector.project(tree, setOf(SnapshotProjector.HOST_PACKAGE))
        assertEquals(ScreenState.FOREGROUND_IS_HOST, scoped.screenState)
        assertTrue(scoped.targets.isEmpty())
        // YOLO: the host is projected like any other app.
        val yolo = projector.project(tree, setOf(SnapshotProjector.HOST_PACKAGE), includeHost = true)
        assertFalse("includeHost must not report FOREGROUND_IS_HOST", yolo.screenState == ScreenState.FOREGROUND_IS_HOST)
        assertTrue("the host target must be projected under includeHost", yolo.targets.any { it.text == "host-target" })
    }

    // ---- metamorphic: includeHost only ever WIDENS disclosure (never drops a scoped target) ----
    @Test
    fun `metamorphic includeHost is a superset of the scoped projection`() {
        runBlocking {
            checkAll(250, arbRawTree(maxDepth = 3)) { tree ->
                val allow = tree.windows.firstOrNull { it.pkg != SnapshotProjector.HOST_PACKAGE }?.pkg ?: grantedPkg
                val scopedTexts = projector.project(tree, setOf(allow)).targets.mapNotNull { it.text }
                val yoloTexts = projector.project(tree, setOf(allow), includeHost = true).targets.mapNotNull { it.text }
                assertTrue(
                    "includeHost dropped a target the scoped projection kept",
                    yoloTexts.containsAll(scopedTexts),
                )
            }
        }
    }

    // ---- P3: every tid is unique within one snapshot ----
    @Test
    fun `P3 tids are unique within a snapshot`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 4)) { tree ->
                val snap = project(tree)
                val tids = snap.targets.map { it.tid }
                assertEquals("tids must be unique", tids.toSet().size, tids.size)
            }
        }
    }

    // ---- P4: a failing node is absent, but a passing descendant still appears ----
    @Test
    fun `P4 failing parent does not drop a passing child`() {
        runBlocking {
            checkAll(300, Arb.int(0..1000)) { seq ->
                // A parent that fails the rule (invisible, no area, no id, no text) wrapping a
                // child that passes (visible + area). The child must survive.
                val child = RawNode(
                    resourceId = "id/child",
                    text = "VISIBLE_CHILD",
                    className = "Button",
                    visible = true,
                    hasArea = true,
                    clickable = true,
                )
                val failingParent = RawNode(
                    resourceId = null,
                    text = null,
                    contentDescription = null,
                    className = "FrameLayout",
                    visible = false,
                    hasArea = false,
                    children = listOf(child),
                )
                val tree = RawTree(
                    stateSeq = seq.toLong(),
                    foregroundPkg = grantedPkg,
                    windows = listOf(RawWindow(pkg = grantedPkg, root = failingParent)),
                )
                val snap = project(tree, grantedPkg)
                assertTrue(
                    "passing descendant of a failing parent was dropped",
                    snap.targets.any { it.text == "VISIBLE_CHILD" },
                )
                // And the failing container itself contributes no target text of its own.
                assertFalse(snap.targets.any { it.role == "FrameLayout" && it.text == null && it.flags.isEmpty() && it.semanticKey == null })
            }
        }
    }

    // ---- P5: projection is deterministic / idempotent ----
    @Test
    fun `P5 projection is deterministic`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 4)) { tree ->
                val a = project(tree)
                val b = project(tree)
                assertEquals("same input must give identical snapshot", a, b)
            }
        }
    }

    // ---- eyes-open binding fields (windowId / structuralPath / structuralFingerprint) are populated
    // and deterministic, so a strict TargetBinding re-resolve matches byte-for-byte between the
    // grounding projection and a live dispatch walk. The path is dense (0..n-1 per parent in raw
    // child order) and the fingerprint is a stable hex SHA-256.
    @Test
    fun `binding fields are populated and deterministic`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 4)) { tree ->
                val a = project(tree)
                val b = project(tree)
                a.targets.forEachIndexed { idx, t ->
                    // windowId mirrors the owning window's id (UNKNOWN_WINDOW_ID when absent). When the
                    // target carries a known window id, it must belong to a window with this package.
                    if (t.windowId != UNKNOWN_WINDOW_ID) {
                        assertTrue(
                            "windowId must belong to a window with this package",
                            tree.windows.any { it.windowId == t.windowId && it.pkg == t.sourcePackage },
                        )
                    }
                    // structuralPath is a raw child-index chain (each entry >= 0).
                    assertTrue("path entries must be non-negative", t.structuralPath.all { it >= 0 })
                    // fingerprint is a non-empty hex string of identical length across re-projections.
                    assertTrue("fingerprint must be populated", t.structuralFingerprint.isNotEmpty())
                    assertTrue(
                        "fingerprint must be hex",
                        t.structuralFingerprint.all { it in '0'..'9' || it in 'a'..'f' },
                    )
                    assertEquals(
                        "the same target must re-project a stable fingerprint",
                        b.targets[idx].structuralFingerprint,
                        t.structuralFingerprint,
                    )
                    assertEquals(
                        "the same target must re-project a stable path",
                        b.targets[idx].structuralPath,
                        t.structuralPath,
                    )
                }
            }
        }
    }

    // ---- fingerprint excludes raw text BYTES (only length): editing a value never changes the
    // fingerprint, so a same-shape text edit does not by itself stale a bound dispatch; but a
    // STRUCTURAL edit (class/child-count/flags) DOES change it, so a re-flowed node is refused.
    @Test
    fun `fingerprint ignores text bytes but captures structural shape`() {
        val base = RawTree(
            stateSeq = 1L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    windowId = 7,
                    root = RawNode(
                        text = "hello",
                        className = "EditText",
                        resourceId = "com.example.app:id/field",
                        visible = true, hasArea = true, editable = true,
                    ),
                ),
            ),
        )
        val baseFp = project(base).targets.first().structuralFingerprint

        // Editing ONLY the text value keeps the same length ⇒ same fingerprint (length-only digest).
        val sameLengthValue = base.copy(
            windows = base.windows.map { it.copy(root = it.root!!.copy(text = "world")) },
        )
        assertEquals(
            "a same-length text edit must NOT change the fingerprint (no raw bytes digested)",
            baseFp,
            project(sameLengthValue).targets.first().structuralFingerprint,
        )

        // A DIFFERENT text length changes the fingerprint (length is part of the shape).
        val diffLengthValue = base.copy(
            windows = base.windows.map { it.copy(root = it.root!!.copy(text = "hi")) },
        )
        assertTrue(
            "a different text length must change the fingerprint",
            baseFp != project(diffLengthValue).targets.first().structuralFingerprint,
        )

        // A STRUCTURAL change (adding a child) changes the fingerprint even with identical text. The
        // root (tid 0) gains a child ⇒ its childCount + childrenShapeDigest change.
        val addedChild = base.copy(
            windows = base.windows.map {
                it.copy(root = it.root!!.copy(children = listOf(RawNode(text = "child", className = "TextView", visible = true, hasArea = true))))
            },
        )
        assertTrue(
            "a structural change (added child) must change the fingerprint",
            baseFp != project(addedChild).targets.first().structuralFingerprint,
        )
    }

    // ---- the fingerprint is RECURSIVE over the full subtree (review round 3 #1): a DEEP descendant
    // structural mutation must change the ancestor target's fingerprint, so a clickable container whose
    // inner content was swapped (same shell, different guts) is refused by a strict binding. ----
    @Test
    fun `a deep descendant structural change alters the ancestor fingerprint`() {
        // A clickable container (the target) with a child that itself has ONE grandchild.
        fun tree(grandchildren: List<RawNode>) = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg, windowId = 3,
                    root = RawNode(
                        className = "FrameLayout", visible = true, hasArea = true, clickable = true,
                        children = listOf(
                            RawNode(
                                className = "LinearLayout", visible = true, hasArea = true,
                                children = grandchildren,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val base = tree(listOf(RawNode(text = "Cancel", className = "Button", visible = true, hasArea = true, clickable = true)))
        // Same immediate-child shape (one LinearLayout child), but the GRANDCHILD subtree gains a node.
        val deepChange = tree(
            listOf(
                RawNode(text = "Cancel", className = "Button", visible = true, hasArea = true, clickable = true),
                RawNode(text = "Confirm", className = "Button", visible = true, hasArea = true, clickable = true),
            ),
        )
        val baseFp = project(base).targets.first { it.role == "FrameLayout" }.structuralFingerprint
        val changedFp = project(deepChange).targets.first { it.role == "FrameLayout" }.structuralFingerprint
        assertTrue(
            "a deep descendant structural change must change the ancestor container's fingerprint",
            baseFp != changedFp,
        )
    }

    // ---- fingerprint field framing is unambiguous (review round 5 #1): adjacent variable-length
    // fields whose concatenation is equal but whose boundaries differ MUST yield different
    // fingerprints. Without length-prefix framing, className="ab"+resourceId="c" and
    // className="a"+resourceId="bc" would serialize to the same preimage and let an in-place
    // replacement keep the binding. ----
    @Test
    fun `fingerprint framing disambiguates adjacent field boundaries`() {
        fun fp(className: String, resourceId: String): String {
            val tree = RawTree(
                stateSeq = 1L, foregroundPkg = grantedPkg,
                windows = listOf(
                    RawWindow(
                        pkg = grantedPkg, windowId = 1,
                        // resourceId implies hasId ⇒ the node projects regardless of text/visibility.
                        root = RawNode(className = className, resourceId = resourceId, visible = true, hasArea = true),
                    ),
                ),
            )
            return project(tree).targets.single().structuralFingerprint
        }
        assertTrue(
            "className/resourceId boundary must be unambiguous in the fingerprint",
            fp("ab", "c") != fp("a", "bc"),
        )
        // The same boundary ambiguity across the package axis must also be framed.
        fun fpPkgPath(pkg: String, secondSegment: Int): String {
            val tree = RawTree(
                stateSeq = 1L, foregroundPkg = grantedPkg,
                windows = listOf(
                    RawWindow(
                        pkg = pkg, windowId = 1,
                        root = RawNode(
                            className = "Root", visible = true, hasArea = true,
                            children = List(secondSegment + 1) { idx ->
                                RawNode(className = "C$idx", resourceId = "id$idx", visible = true, hasArea = true)
                            },
                        ),
                    ),
                ),
            )
            // Project and read the LAST child's fingerprint (its structuralPath ends in secondSegment).
            return project(tree).targets.last().structuralFingerprint
        }
        assertTrue(
            "package + path framing must be unambiguous",
            fpPkgPath(grantedPkg, 1) != fpPkgPath(grantedPkg, 2),
        )
    }

    // ---- distinct windows with the same shape produce distinct fingerprints (windowId axis), so a
    // same-shape node re-flowed into a different window is refused by a strict binding.
    @Test
    fun `same shape in different windows yields different fingerprints`() {
        val node = RawNode(text = "X", className = "Button", visible = true, hasArea = true, clickable = true)
        val treeA = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = 1, root = node)),
        )
        val treeB = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = 2, root = node)),
        )
        assertTrue(
            "the windowId axis must distinguish same-shape nodes in different windows",
            project(treeA).targets.single().structuralFingerprint !=
                project(treeB).targets.single().structuralFingerprint,
        )
    }

    // ---- windowId fallback (review round 2 #3): when the window-level id is UNKNOWN but the node
    // carries its own window id, the projector must fall back to node.windowId for BOTH the
    // UiTarget.windowId axis and the fingerprint — otherwise the binding collapses to
    // UNKNOWN_WINDOW_ID and a same-shape node in a different window could match it.
    @Test
    fun `windowId falls back to the node id when the window-level id is unknown`() {
        val node = RawNode(
            text = "X", className = "Button", visible = true, hasArea = true, clickable = true,
            windowId = 5,
        )
        // Window-level id UNKNOWN, but the node knows it is in window 5.
        val unknownWindow = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = UNKNOWN_WINDOW_ID, root = node)),
        )
        // The same node shape in a window whose WINDOW-level id is the known 5 (node id irrelevant here).
        val knownWindow = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = 5, root = node.copy(windowId = UNKNOWN_WINDOW_ID))),
        )
        val fallback = project(unknownWindow).targets.single()
        assertEquals("the target must adopt the node's window id when the window id is unknown", 5, fallback.windowId)
        assertEquals(
            "the node-id fallback must flow into the fingerprint identically to a window-level id 5",
            project(knownWindow).targets.single().structuralFingerprint,
            fallback.structuralFingerprint,
        )

        // The window-level id wins when BOTH are known and differ (window id is preferred, node id is fallback-only).
        val bothKnown = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = 9, root = node.copy(windowId = 5))),
        )
        assertEquals("the window-level id must win over a differing node id", 9, project(bothKnown).targets.single().windowId)

        // A NEGATIVE window-level id (the framework's -1 "unknown" sentinel) must be treated as unknown,
        // not as a real matchable id, and trigger the node fallback (review round 7 #1).
        val negativeWindow = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = -1, root = node.copy(windowId = 5))),
        )
        assertEquals("a negative window id must fall back to the node id", 5, project(negativeWindow).targets.single().windowId)

        // Both negative ⇒ canonical UNKNOWN_WINDOW_ID (never a -1 that two unknown windows would share).
        val bothNegative = RawTree(
            stateSeq = 1L, foregroundPkg = grantedPkg,
            windows = listOf(RawWindow(pkg = grantedPkg, windowId = -1, root = node.copy(windowId = -1))),
        )
        assertEquals(
            "both ids negative must canonicalize to UNKNOWN_WINDOW_ID",
            UNKNOWN_WINDOW_ID,
            project(bothNegative).targets.single().windowId,
        )
    }

    // ---- P6: adding empty noise containers does not change the targets ----
    @Test
    fun `P6 noise nodes do not change projection`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 3)) { tree ->
                val before = project(tree)
                val noised = sprinkleNoise(tree)
                val after = project(noised)
                // Same number of meaningful targets; the noise (empty, no-area, no-id, no-text)
                // contributes nothing.
                assertEquals(
                    "noise changed target texts",
                    before.targets.map { it.text },
                    after.targets.map { it.text },
                )
            }
        }
    }

    // ---- P7: a superset of passing nodes ⇒ a superset of targets ----
    @Test
    fun `P7 adding a passing node only grows the target set`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 3)) { tree ->
                val before = project(tree)
                // Add one guaranteed-passing node to the first non-host window's root children.
                val extra = RawNode(
                    resourceId = "id/extra-unique",
                    text = "EXTRA_UNIQUE_NODE",
                    className = "Button",
                    visible = true,
                    hasArea = true,
                    clickable = true,
                )
                val grown = addNodeToFirstAppWindow(tree, extra)
                val after = project(grown)
                val beforeTexts = before.targets.mapNotNull { it.text }
                val afterTexts = after.targets.mapNotNull { it.text }
                // Every text present before is still present, plus the new one.
                assertTrue("a previously-present target disappeared", afterTexts.containsAll(beforeTexts))
                assertTrue("the added node is missing", afterTexts.contains("EXTRA_UNIQUE_NODE"))
            }
        }
    }

    // ---- boundary: host-only -> no targets ----
    @Test
    fun `boundary host-only trees yield no targets`() {
        val tree = RawTree(
            stateSeq = 1L,
            foregroundPkg = SnapshotProjector.HOST_PACKAGE,
            windows = listOf(
                RawWindow(
                    pkg = SnapshotProjector.HOST_PACKAGE,
                    root = RawNode(
                        text = "host-only",
                        className = "TextView",
                        visible = true,
                        hasArea = true,
                    ),
                ),
            ),
        )
        val snap = project(tree, grantedPkg)
        assertTrue("host-only input must not produce targets", snap.targets.isEmpty())
    }

    // ---- boundary: granted + foreign overlay (systemWindow=false) -> overlay omitted ----
    @Test
    fun `boundary granted and foreign overlay keeps only granted targets`() {
        val tree = RawTree(
            stateSeq = 2L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    root = RawNode(text = "Granted", className = "TextView", visible = true, hasArea = true),
                ),
                RawWindow(
                    pkg = foreignPkg,
                    root = RawNode(text = "ForeignOverlay", className = "TextView", visible = true, hasArea = true),
                ),
            ),
        )
        val snap = project(tree, grantedPkg)
        assertTrue(snap.targets.any { it.text == "Granted" })
        assertFalse(snap.targets.any { it.text == "ForeignOverlay" })
    }

    // ---- boundary: granted + foreign system window -> system window retained ----
    @Test
    fun `boundary granted and foreign system window remains projected`() {
        val tree = RawTree(
            stateSeq = 3L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    root = RawNode(text = "Granted", className = "TextView", visible = true, hasArea = true),
                ),
                RawWindow(
                    pkg = foreignPkg,
                    systemWindow = true,
                    root = RawNode(text = "Allow", className = "TextView", visible = true, hasArea = true),
                ),
            ),
        )
        val snap = project(tree, grantedPkg)
        assertTrue(snap.targets.any { it.text == "Granted" })
        val allow = snap.targets.firstOrNull { it.text == "Allow" }
        assertEquals("a system window target must be retained", foreignPkg, allow?.sourcePackage)
    }

    // ---- boundary: split-screen granted + foreign app window -> foreign omitted ----
    @Test
    fun `boundary split-screen keeps granted package only`() {
        val tree = RawTree(
            stateSeq = 4L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    root = RawNode(text = "GrantedLeft", className = "TextView", visible = true, hasArea = true),
                ),
                RawWindow(
                    pkg = foreignPkg,
                    root = RawNode(text = "ForeignRight", className = "TextView", visible = true, hasArea = true),
                ),
            ),
        )
        val snap = project(tree, grantedPkg)
        assertTrue(snap.targets.any { it.text == "GrantedLeft" })
        assertFalse(snap.targets.any { it.text == "ForeignRight" })
    }

    @Test
    fun `boundary granted and foreign system dialog is retained with source provenance`() {
        val tree = RawTree(
            stateSeq = 5L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    root = RawNode(text = "GrantedMain", className = "TextView", visible = true, hasArea = true),
                ),
                RawWindow(
                    pkg = foreignPkg,
                    systemWindow = true,
                    root = RawNode(
                        text = "ForeignDialog",
                        contentDescription = "foreign-key",
                        resourceId = "com.example.foreign:id/dialog",
                        className = "TextView",
                        visible = true,
                        hasArea = true,
                        editable = true,
                    ),
                ),
            ),
        )
        val snap = project(tree, grantedPkg)
        assertTrue(snap.targets.any { it.text == "GrantedMain" })
        // The dialog node is editable, so its display text is the hint (not the value "ForeignDialog");
        // address it by its stable semantic key instead.
        val dialog = snap.targets.firstOrNull { it.semanticKey == "foreign-key" }
        assertEquals("system-window targets should be projected", foreignPkg, dialog?.sourcePackage)
        assertEquals("an editable system node's VALUE must not be projected as display text", "foreign-key", dialog?.text)
        assertEquals("system-window provenance carries semantic key", "foreign-key", dialog?.semanticKey)
        assertEquals(
            "system-window provenance carries view/form id",
            "com.example.foreign:id/dialog",
            dialog?.formKey,
        )
        assertEquals(
            "system-window view id is also preserved",
            "com.example.foreign:id/dialog",
            dialog?.viewId,
        )
    }

    @Test
    fun `boundary granted secure window yields no contents`() {
        val tree = RawTree(
            stateSeq = 6L,
            foregroundPkg = grantedPkg,
            windows = listOf(
                RawWindow(
                    pkg = grantedPkg,
                    secure = true,
                    root = RawNode(text = "Secret", className = "TextView", visible = true, hasArea = true),
                ),
            ),
        )
        val snap = project(tree, grantedPkg)
        assertEquals("secure allowed window must be hidden", me.rerere.automation.observe.ScreenState.SECURE_WINDOW, snap.screenState)
        assertTrue(snap.targets.isEmpty())
    }

    // ---- metamorphic: grant allowlist ignores extra foreign windows ----
    @Test
    fun `metamorphic granted package filter ignores unrelated added foreign windows`() {
        runBlocking {
            checkAll(250, arbRawTree(maxDepth = 3)) { tree ->
                val withGranted = addPackageWindowIfMissing(
                    tree,
                    grantedPkg,
                    RawNode(text = "kept", className = "TextView", visible = true, hasArea = true),
                )
                val baseline = project(withGranted, grantedPkg)
                val extraForeign = RawWindow(
                    pkg = foreignPkg,
                    root = RawNode(text = "UNRELATED", className = "TextView", visible = true, hasArea = true),
                )
                val withForeign = withGranted.copy(windows = withGranted.windows + extraForeign)
                val augmented = project(withForeign, grantedPkg)
                assertEquals("foreign-window additions outside allowlist must not change projection", baseline, augmented)
            }
        }
    }

    // ---- helpers ----

    private fun collectTexts(windows: List<RawWindow>): Set<String> {
        val out = HashSet<String>()
        fun walk(n: RawNode) {
            if (!n.password) {
                n.text?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            n.children.forEach { walk(it) }
        }
        windows.forEach { it.root?.let { r -> walk(r) } }
        return out
    }

    private fun injectPasswordSecret(tree: RawTree, secret: String): RawTree {
        val pwNode = RawNode(
            resourceId = "id/pw",
            text = secret,
            className = "EditText",
            visible = true,
            hasArea = true,
            editable = true,
            password = true,
        )
        val idx = tree.windows.indexOfFirst { it.pkg != HOST && it.root != null }
        if (idx < 0) {
            return tree.copy(
                windows = tree.windows + RawWindow(pkg = grantedPkg, root = pwNode),
                foregroundPkg = grantedPkg,
            )
        }
        val w = tree.windows[idx]
        val root = w.root ?: return tree
        val newRoot = root.copy(children = root.children + pwNode)
        val newWindows = tree.windows.toMutableList().apply { this[idx] = w.copy(root = newRoot) }
        // Make sure the secret window is foregrounded & visible (not host, not secure).
        return tree.copy(windows = newWindows, foregroundPkg = w.pkg)
    }

    private fun sprinkleNoise(tree: RawTree): RawTree {
        fun noisify(n: RawNode): RawNode =
            n.copy(children = listOf(emptyNoiseNode()) + n.children.map { noisify(it) } + emptyNoiseNode())
        return tree.copy(windows = tree.windows.map { w -> w.copy(root = w.root?.let { noisify(it) }) })
    }

    private fun addNodeToFirstAppWindow(tree: RawTree, node: RawNode): RawTree {
        val idx = tree.windows.indexOfFirst { it.pkg != HOST && it.root != null }
        if (idx < 0) {
            return tree.copy(
                windows = tree.windows + RawWindow(pkg = grantedPkg, root = node),
                foregroundPkg = grantedPkg,
            )
        }
        val w = tree.windows[idx]
        val root = w.root ?: return tree
        val newRoot = root.copy(children = root.children + node)
        val newWindows = tree.windows.toMutableList().apply { this[idx] = w.copy(root = newRoot) }
        return tree.copy(windows = newWindows, foregroundPkg = w.pkg)
    }
}
