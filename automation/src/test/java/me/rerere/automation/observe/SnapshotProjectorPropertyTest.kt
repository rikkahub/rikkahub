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
 * nodes, the host package, duplicate ids, and noise containers. Each property is written so a naive
 * "project everything verbatim" stub FAILS it.
 */
class SnapshotProjectorPropertyTest {

    private val projector = SnapshotProjector()

    // ---- P1: a password node's REAL text never appears in the projection ----
    @Test
    fun `P1 password text is never leaked`() {
        runBlocking {
            // Force a password field carrying a known secret somewhere in a non-host window. The
            // sentinel is deliberately NOT in the generator's text pool, so the only way it could
            // appear in the projection is a genuine password leak.
            val secret = "PW_SENTINEL_Zx9Qv"
            checkAll(300, arbRawTree(maxDepth = 3)) { tree ->
                // Inject a guaranteed password node with the secret into the first non-host window.
                val withSecret = injectPasswordSecret(tree, secret)
                val snap = projector.project(withSecret)
                snap.targets.forEach { t ->
                    assertFalse(
                        "password text leaked in tid=${t.tid}",
                        t.text?.contains(secret) == true,
                    )
                    // editableText carries the ground-truth field VALUE for the P9 no-op (#198 slice 9);
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
                val snap = projector.project(tree)
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

    // ---- P3: every tid is unique within one snapshot ----
    @Test
    fun `P3 tids are unique within a snapshot`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 4)) { tree ->
                val snap = projector.project(tree)
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
                    foregroundPkg = "com.example.app",
                    windows = listOf(RawWindow(pkg = "com.example.app", root = failingParent)),
                )
                val snap = projector.project(tree)
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
                val a = projector.project(tree)
                val b = projector.project(tree)
                assertEquals("same input must give identical snapshot", a, b)
            }
        }
    }

    // ---- P6: adding empty noise containers does not change the targets ----
    @Test
    fun `P6 noise nodes do not change projection`() {
        runBlocking {
            checkAll(300, arbRawTree(maxDepth = 3)) { tree ->
                val before = projector.project(tree)
                val noised = sprinkleNoise(tree)
                val after = projector.project(noised)
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
                val before = projector.project(tree)
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
                val after = projector.project(grown)
                val beforeTexts = before.targets.mapNotNull { it.text }
                val afterTexts = after.targets.mapNotNull { it.text }
                // Every text present before is still present, plus the new one.
                assertTrue("a previously-present target disappeared", afterTexts.containsAll(beforeTexts))
                assertTrue("the added node is missing", afterTexts.contains("EXTRA_UNIQUE_NODE"))
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
                windows = tree.windows + RawWindow(pkg = "com.example.app", root = pwNode),
                foregroundPkg = "com.example.app",
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
                windows = tree.windows + RawWindow(pkg = "com.example.app", root = node),
                foregroundPkg = "com.example.app",
            )
        }
        val w = tree.windows[idx]
        val root = w.root ?: return tree
        val newRoot = root.copy(children = root.children + node)
        val newWindows = tree.windows.toMutableList().apply { this[idx] = w.copy(root = newRoot) }
        return tree.copy(windows = newWindows, foregroundPkg = w.pkg)
    }
}
