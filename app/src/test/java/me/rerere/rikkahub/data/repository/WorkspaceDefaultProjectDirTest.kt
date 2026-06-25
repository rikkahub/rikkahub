package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The project-dir default resolution (issue: ".poci/scratch as default project dir"). An UNSET
 * working dir resolves to [WorkspaceRepository.DEFAULT_PROJECT_DIR]; an explicitly-set one is used
 * verbatim. [resolveWorkingDir] is the pure rule [WorkspaceRepository.effectiveWorkingDir] applies at
 * every working_dir read, so the shell, the file tools, and the cwd jail all share one base — pinning
 * it here is the CI-runnable guarantee (the repository ctor needs an Android SettingsStore, so a full
 * repository test is not in the JVM source set; same constraint as WorkspaceRepositoryShellEnableTest).
 */
class WorkspaceDefaultProjectDirTest {

    private val default = WorkspaceRepository.DEFAULT_PROJECT_DIR

    @Test
    fun `an unset working dir resolves to the default project dir`() {
        assertEquals(default, resolveWorkingDir("", default))
        // ifBlank also covers whitespace-only, which is never a real directory name.
        assertEquals(default, resolveWorkingDir("   ", default))
    }

    @Test
    fun `an explicitly set working dir is used verbatim`() {
        assertEquals("project/sub", resolveWorkingDir("project/sub", default))
    }

    @Test
    fun `the default project dir is poci scratch`() {
        assertEquals(".poci/scratch", WorkspaceRepository.DEFAULT_PROJECT_DIR)
    }
}
