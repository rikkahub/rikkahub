package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

// Play-distributed flavor: the PTY terminal route is PHYSICALLY ABSENT (I-FLAVOR, design note
// security-model-design:197 §4.1 Option A). This seam registers NOTHING, so the Play build neither
// references nor ships WorkspaceTerminalPage / the termux terminal lib. The signature mirrors the
// sideload copy (the stable seam contract RouteActivity calls) and the empty play WorkspaceToolsGate /
// WorkspaceControlsGate seams.
@Suppress("UnusedReceiverParameter")
internal fun EntryProviderScope<NavKey>.workspaceTerminalDestination() {
}
