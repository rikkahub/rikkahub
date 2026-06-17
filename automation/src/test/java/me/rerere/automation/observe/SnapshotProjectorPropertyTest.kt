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
        val dialog = snap.targets.firstOrNull { it.text == "ForeignDialog" }
        assertEquals("system-window targets should be projected", foreignPkg, dialog?.sourcePackage)
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
