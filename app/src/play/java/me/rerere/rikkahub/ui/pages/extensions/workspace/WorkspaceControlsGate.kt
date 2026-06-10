package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

// Play-distributed flavor: the shell-enable toggle / rootfs-install action / terminal entry are
// PHYSICALLY ABSENT (I-FLAVOR, design note security-model-design:197 §4.1 Option A), mirroring the
// empty play WorkspaceToolsGate seam. WorkspaceDetailPage renders ONLY the management UI
// (info card, read-only file browse, per-tool approval toggles) on Play; this seam stays empty
// permanently. Slice 6b fills the SIDELOAD copy with the shell/install controls.
@Composable
internal fun SideloadWorkspaceControls(@Suppress("UNUSED_PARAMETER") workspace: WorkspaceEntity?) {
}
