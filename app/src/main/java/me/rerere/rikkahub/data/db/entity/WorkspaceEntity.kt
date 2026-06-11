package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.common.json.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_enabled")
    val shellEnabled: Boolean = false,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // Per-tool approval overrides (toolName -> needsApproval); tools not listed fall back to the default
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
) {
    // Returns null when the stored blob cannot be parsed, so callers can distinguish an explicitly
    // empty policy ({}) from a corrupt one. A corrupt approval policy is a security-relevant case:
    // the tool-surface consumer must fail CLOSED (require approval for everything) rather than fall
    // back to the relaxed defaults. Decoding stays resilient (never throws) but does not lie.
    fun toolApprovalOverrides(): Map<String, Boolean>? = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrNull()

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellEnabled = shellEnabled,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
