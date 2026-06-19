package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Coverage for [seededRelativeCwd] — the single FILES-relative cwd seed + containment authority both
 * the LLM exec sink and the sideload terminal reduce to (issue #282, W-I6). A blank `working_dir`
 * seeds the files root (the unified project working directory default shared with the file tools); a
 * non-blank seed is resolved through the SAME canonical-containment check exec applies. Touches a real
 * temp filesystem (the containment check canonicalizes), so it is a small test rather than a pure one.
 */
class WorkspaceCwdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- blank working_dir seeds the files root (the unified project working directory default) ----
    @Test
    fun `seededRelativeCwd blank working_dir resolves to the files root`() {
        val filesDir = tmp.newFolder("files")
        assertEquals("", seededRelativeCwd(filesDir, workingDir = ""))
        assertEquals("", seededRelativeCwd(filesDir, workingDir = "   "))
    }

    // ---- a contained working_dir (real in-root dir) is accepted and returns the relative path ----
    @Test
    fun `seededRelativeCwd accepts a contained working_dir`() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "project/sub").mkdirs()

        assertEquals("project/sub", seededRelativeCwd(filesDir, workingDir = "project/sub"))
    }

    // ---- W-I6 (containment parity): seededRelativeCwd rejects a working_dir that escapes via symlink ----
    // exec resolves the seed through WorkspaceFileSystem.resolve (canonical containment) before mapping
    // to `-w`; the terminal calls seededRelativeCwd and maps straight to `-w` with no resolve. So a
    // persisted working_dir reaching through an escaping symlink (`<files>/inside` -> /outside) was
    // REJECTED by exec but ACCEPTED by the terminal — an asymmetry. The shared seededRelativeCwd is the
    // single containment authority, so it must reject the identical set of paths both sinks reduce to.
    @Test
    fun `seededRelativeCwd rejects a working_dir escaping the files root via symlink`() {
        val filesDir = tmp.newFolder("files")
        val outside = tmp.newFolder("outside")
        File(outside, "secret").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(filesDir, "inside").toPath(),
            outside.toPath(),
        )

        try {
            seededRelativeCwd(filesDir, workingDir = "inside/secret")
            throw AssertionError("seededRelativeCwd must reject a working_dir that escapes via symlink")
        } catch (e: IllegalArgumentException) {
            // expected — the canonical-containment resolver rejects the escape, same as exec
        }
    }
}
