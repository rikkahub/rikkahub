package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen

// Sideload (non-Play) flavor: this is the ONLY place the entry<Screen.WorkspaceTerminal> ->
// WorkspaceTerminalPage binding exists, so the PTY terminal page compiles into the sideload APK only
// (I-FLAVOR, design note security-model-design:197 §4.1 Option A). RouteActivity calls this seam from
// its entryProvider lambda; the play copy registers nothing, so the Play build never references
// WorkspaceTerminalPage. Mirrors the per-flavor WorkspaceToolsGate / WorkspaceControlsGate seams.
internal fun EntryProviderScope<NavKey>.workspaceTerminalDestination() {
    entry<Screen.WorkspaceTerminal> { key ->
        WorkspaceTerminalPage(id = key.id)
    }
}
