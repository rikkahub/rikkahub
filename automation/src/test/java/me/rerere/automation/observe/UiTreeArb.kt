package me.rerere.automation.observe

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow

/**
 * Generators for the projector PBT (#187 design §8): random UI trees that deliberately include the
 * adversarial cases the projection rule must handle — password nodes, the host package, duplicate
 * resource ids, and empty "noise" containers.
 */

const val HOST = SnapshotProjector.HOST_PACKAGE

private val NON_HOST_PACKAGES = listOf("com.example.app", "com.other.app", "com.third.app")

/** A single node; may be a password field, may carry a duplicate id, may be empty/invisible. */
fun arbRawNode(maxDepth: Int): Arb<RawNode> = arbitrary { rs ->
    val password = Arb.boolean().bind()
    val visible = Arb.boolean().bind()
    val hasArea = Arb.boolean().bind()
    // Duplicate-id pool is deliberately tiny so collisions are common.
    val resourceId = Arb.element(listOf<String?>(null, "id/a", "id/a", "id/b")).bind()
    val text = Arb.element(listOf<String?>(null, "", "hello", "secret123", "OK")).bind()
    val children = if (maxDepth <= 0) emptyList()
    else Arb.list(arbRawNode(maxDepth - 1), 0..3).bind()
    RawNode(
        resourceId = resourceId,
        text = text,
        contentDescription = Arb.element(listOf<String?>(null, "", "desc")).bind(),
        className = Arb.element(listOf("Button", "TextView", "EditText", "View")).bind(),
        visible = visible,
        hasArea = hasArea,
        clickable = Arb.boolean().bind(),
        editable = Arb.boolean().bind(),
        scrollable = Arb.boolean().bind(),
        checkable = Arb.boolean().bind(),
        checked = Arb.boolean().bind(),
        password = password,
        children = children,
    )
}

/** A non-host, non-secure window rooted at a random tree. */
fun arbAppWindow(maxDepth: Int): Arb<RawWindow> = arbitrary {
    RawWindow(
        pkg = Arb.element(NON_HOST_PACKAGES).bind(),
        secure = false,
        systemWindow = Arb.boolean().bind(),
        root = arbRawNode(maxDepth).bind(),
    )
}

/**
 * A full forest. Randomly injects a host-package window (must be excluded) and lets the foreground
 * be host or non-host. Some windows may be secure.
 */
fun arbRawTree(maxDepth: Int = 3): Arb<RawTree> = arbitrary {
    val windows = Arb.list(arbAppWindow(maxDepth), 1..3).bind().toMutableList()
    // Sometimes add a host window that the projector must drop entirely (P2).
    if (Arb.boolean().bind()) {
        windows.add(RawWindow(pkg = HOST, secure = false, root = arbRawNode(maxDepth).bind()))
    }
    val foreground = Arb.element(NON_HOST_PACKAGES + listOf(HOST)).bind()
    RawTree(
        stateSeq = Arb.int(0..1000).bind().toLong(),
        foregroundPkg = foreground,
        windows = windows,
    )
}

/** An empty container node — pure noise, must not change the projection (P6). */
fun emptyNoiseNode(): RawNode = RawNode(
    resourceId = null,
    text = null,
    contentDescription = null,
    className = "FrameLayout",
    visible = true,
    hasArea = false,
    children = emptyList(),
)
