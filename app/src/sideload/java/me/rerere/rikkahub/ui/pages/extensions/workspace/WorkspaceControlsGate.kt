package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

// Sideload (non-Play) flavor: this seam ships EMPTY in slice 6a so both source sets compile and the
// management UI (info card, read-only file browse, per-tool approval toggles) is byte-identical with
// ZERO behavior change across flavors. Slice 6b fills THIS sideload body with the shell-enable
// toggle + rootfs-install action + terminal entry (HP-1 pattern, mirroring the sideload
// WorkspaceToolsGate). The signature is the stable seam contract shared with the play copy.
@Composable
internal fun SideloadWorkspaceControls(@Suppress("UNUSED_PARAMETER") workspace: WorkspaceEntity?) {
}
