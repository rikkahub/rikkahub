package me.rerere.rikkahub.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping coverage for the workspace working-directory field (issue #282, M2/T6).
 *
 * `working_dir` is the persisted FILES-area relative seed for the resolved cwd. T6 only adds the
 * column to [WorkspaceEntity], the field to the [me.rerere.workspace.Workspace] domain model, and
 * makes [WorkspaceEntity.toWorkspace] carry the value across. The default is the UNSET sentinel
 * `""` (column `DEFAULT ''`), so a row constructed without an explicit `workingDir` — i.e. a
 * legacy row decoded after the future v26->v27 migration — must surface as unset, never null and
 * never some other path. The mapper is the entity<->domain seam these tests pin.
 */
class WorkspaceEntityWorkingDirTest {

    private fun entity(workingDir: String? = null): WorkspaceEntity =
        if (workingDir == null) {
            WorkspaceEntity(
                id = "ws-1",
                name = "Workspace",
                root = "ws-1",
                createdAt = 1L,
                updatedAt = 2L,
            )
        } else {
            WorkspaceEntity(
                id = "ws-1",
                name = "Workspace",
                root = "ws-1",
                createdAt = 1L,
                updatedAt = 2L,
                workingDir = workingDir,
            )
        }

    // Default is the UNSET sentinel "" — matches the column `DEFAULT ''` and the domain default.
    @Test
    fun `entity workingDir defaults to empty unset sentinel`() {
        assertEquals("", entity().workingDir)
    }

    // The mapper carries the field into the domain model unchanged.
    @Test
    fun `toWorkspace carries the working dir into the domain model`() {
        assertEquals(".xcloudz/scratch", entity(workingDir = ".xcloudz/scratch").toWorkspace().workingDir)
    }

    // An unset entity maps to an unset domain field (not null, not a substituted default).
    @Test
    fun `unset entity maps to unset domain working dir`() {
        assertEquals("", entity().toWorkspace().workingDir)
    }
}
