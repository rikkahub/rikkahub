package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.model.AutomationGrant

// Play-distributed flavor: YOLO ("bypass all restriction") is PHYSICALLY ABSENT from the UI. The
// unrestricted automation surface (every app incl. system UI and the host, auto-confirmed submit taps)
// is sideload-only, mirroring the workspace shell security boundary; this seam stays empty permanently.
// An empty UI seam alone is NOT sufficient — backup-restore / settings import can write yolo=true +
// acknowledged from a sideload-origin settings.json. The runtime boundary is enforced at the lease
// derivation: the play AUTOMATION_YOLO_SUPPORTED=false strips YOLO regardless of any persisted/imported
// flag, so a Play build can never mint an Unbounded/host-inclusive capability.
@Composable
internal fun AutomationYoloSection(
    @Suppress("UNUSED_PARAMETER") grant: AutomationGrant,
    @Suppress("UNUSED_PARAMETER") yoloAcknowledged: Boolean,
    @Suppress("UNUSED_PARAMETER") onUpdate: (AutomationGrant) -> Unit,
    @Suppress("UNUSED_PARAMETER") onAcknowledge: () -> Unit,
) {
}
