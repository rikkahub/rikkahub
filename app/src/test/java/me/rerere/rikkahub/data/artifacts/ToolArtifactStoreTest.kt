package me.rerere.rikkahub.data.artifacts

import java.io.File
import java.nio.file.Files
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.context.ArtifactContextGovernor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolArtifactStoreTest {
    @Test
    fun `small output stays inline and threshold output becomes an artifact`() {
        val store = store(inlineBytes = 8, inlineTokens = 100)
        val governor = ArtifactContextGovernor(store)
        val scope = runScope()

        val inline = governor.governToolOutput(scope, "execution", listOf(UIMessagePart.Text("small")))
        val stored = governor.governToolOutput(scope, "execution", listOf(UIMessagePart.Text("012345678")))

        assertFalse(inline.reference.stored)
        assertEquals("small", (inline.modelOutput.single() as UIMessagePart.Text).text)
        assertTrue(stored.reference.stored)
        assertTrue((stored.modelOutput.single() as UIMessagePart.Text).text.contains("artifactId:"))
        assertEquals("execution", stored.reference.toolExecutionId)
        assertTrue((stored.modelOutput.single() as UIMessagePart.Text).text.contains("toolExecutionId: execution"))
    }

    @Test
    fun `artifact hash size and atomic files are recorded`() {
        val root = temporaryRoot()
        val store = FileToolArtifactStore(root)
        val scope = scope()
        val reference = store.create(scope, "abc", "text/plain")

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", reference.sha256)
        assertEquals(3, reference.sizeBytes)
        assertEquals(scope.toolExecutionId, reference.toolExecutionId)
        assertTrue(root.walkTopDown().any { it.name == "${reference.artifactId}.bin" })
        assertFalse(root.walkTopDown().any { it.extension == "tmp" })
    }

    @Test
    fun `scope paths reject traversal and another assistant cannot read`() {
        try {
            ToolArtifactScope("assistant", "../conversation", "run", "execution")
            fail("Traversal must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val store = FileToolArtifactStore(temporaryRoot())
        val reference = store.create(scope(), "private", "text/plain")
        try {
            store.readFragment(scope(assistantId = "other-assistant"), checkNotNull(reference.artifactId), 0, 64)
            fail("Cross-assistant read must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `run and execution scope prevent cross run reads`() {
        val store = FileToolArtifactStore(temporaryRoot())
        val reference = store.create(scope(), "private", "text/plain")
        try {
            store.readFragment(scope(runId = "other-run"), checkNotNull(reference.artifactId), 0, 64)
            fail("Cross-run read must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `authorized scope can read fragments and search without a path`() {
        val store = FileToolArtifactStore(temporaryRoot())
        val reference = store.create(scope(), "first line\nneedle line\nlast line", "text/plain")

        val fragment = store.readFragment(scope(), checkNotNull(reference.artifactId), 0, 5)
        val matches = store.search(scope(), checkNotNull(reference.artifactId), "needle")

        assertEquals("first", fragment.content)
        assertEquals(listOf("needle line"), matches)
    }

    @Test
    fun `conversation quota is enforced`() {
        val store = FileToolArtifactStore(
            temporaryRoot(),
            ToolArtifactLimits(maxArtifactBytes = 32, maxConversationBytes = 10),
        )
        store.create(scope(), "123456", "text/plain")
        try {
            store.create(scope(toolExecutionId = "next"), "78901", "text/plain")
            fail("Conversation quota must be enforced")
        } catch (_: ToolArtifactLimitExceeded) {
        }
    }

    @Test
    fun `single artifact size limit is enforced`() {
        val store = FileToolArtifactStore(
            temporaryRoot(),
            ToolArtifactLimits(maxArtifactBytes = 3, maxConversationBytes = 10),
        )
        try {
            store.create(scope(), "four", "text/plain")
            fail("Single artifact limit must be enforced")
        } catch (_: ToolArtifactLimitExceeded) {
        }
    }

    @Test
    fun `conversation deletion removes every run artifact`() {
        val root = temporaryRoot()
        val store = FileToolArtifactStore(root)
        store.create(scope(), "one", "text/plain")
        store.create(scope(toolExecutionId = "next"), "two", "text/plain")

        store.deleteConversation("assistant", "conversation")

        assertFalse(root.walkTopDown().any { it.extension == "bin" })
    }

    @Test
    fun `retention cleanup removes expired artifacts`() {
        val root = temporaryRoot()
        val store = FileToolArtifactStore(root, ToolArtifactLimits(retentionMillis = 1), nowMillis = { 0 })
        store.create(scope(), "expired", "text/plain")

        store.cleanup(nowMillis = 1)

        assertFalse(root.walkTopDown().any { it.extension == "bin" || it.extension == "properties" })
    }

    @Test
    fun `read and search delete expired artifacts before returning content`() {
        var now = 0L
        val root = temporaryRoot()
        val store = FileToolArtifactStore(root, ToolArtifactLimits(retentionMillis = 1), nowMillis = { now })
        val reference = store.create(scope(), "needle", "text/plain")
        now = 1

        assertFails { store.readFragment(scope(), checkNotNull(reference.artifactId), 0, 64) }
        assertFails { store.search(scope(), checkNotNull(reference.artifactId), "needle") }
        assertFalse(root.walkTopDown().any { it.extension == "bin" || it.extension == "properties" })
    }

    @Test
    fun `metadata schema and bounded artifact access are enforced`() {
        val root = temporaryRoot()
        val store = FileToolArtifactStore(
            root,
            ToolArtifactLimits(
                maxArtifactBytes = 64,
                maxConversationBytes = 64,
                maxFragmentBytes = 4,
                maxSearchBytes = 8,
                maxSearchResultBytes = 8,
                maxSearchQueryBytes = 4,
                maxSearchMatches = 1,
                maxAccessesPerArtifact = 2,
                maxReadBytesPerArtifact = 8,
            ),
        )
        val reference = store.create(scope(), "hit\nhit\n", "text/plain")
        val artifactId = checkNotNull(reference.artifactId)

        assertFails { store.search(scope(), artifactId, "hit", maxMatches = 2) }
        assertFails { store.search(scope(), artifactId, "query") }
        store.readFragment(scope(), artifactId, 0, 4)
        store.readFragment(scope(), artifactId, 4, 4)
        assertFails { store.readFragment(scope(), artifactId, 0, 4) }

        root.walkTopDown().first { it.name == "$artifactId.properties" }.appendText("\nunexpected=value")
        assertFails { store.readFragment(scope(), artifactId, 0, 4) }
        assertFalse(root.walkTopDown().any { it.name == "$artifactId.bin" || it.name == "$artifactId.properties" })
    }

    @Test
    fun `model preview redacts host paths and is fixed size`() {
        val store = store(inlineBytes = 1, inlineTokens = 1, previewBytes = 64)
        val output = ArtifactContextGovernor(store).governToolOutput(
            runScope(),
            "execution",
            listOf(UIMessagePart.Text("/data/user/0/me.rerere.rikkahub/files/secret " + "x".repeat(256))),
        )
        val preview = output.reference.preview

        assertFalse(preview.contains("/data/user/0"))
        assertTrue(preview.contains("[REDACTED_HOST_PATH]"))
        assertTrue(preview.toByteArray().size <= 64)
        assertNotEquals("", checkNotNull(output.reference.artifactId))
    }

    private fun store(inlineBytes: Int = 32 * 1024, inlineTokens: Int = 8 * 1024, previewBytes: Int = 4 * 1024) =
        FileToolArtifactStore(
            temporaryRoot(),
            ToolArtifactLimits(
                inlineMaxBytes = inlineBytes,
                inlineMaxEstimatedTokens = inlineTokens,
                previewMaxBytes = previewBytes,
            ),
        )

    private fun temporaryRoot(): File = Files.createTempDirectory("tool-artifact-test").toFile()

    private fun runScope() = ToolArtifactRunScope("assistant", "conversation", "run")

    private fun scope(
        assistantId: String = "assistant",
        runId: String = "run",
        toolExecutionId: String = "execution",
    ) = ToolArtifactScope(assistantId, "conversation", runId, toolExecutionId)

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
