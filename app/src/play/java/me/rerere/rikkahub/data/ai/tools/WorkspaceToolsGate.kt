package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

// Play-distributed flavor: the shell/write capability is PHYSICALLY ABSENT (I-FLAVOR, design note
// security-model-design:197 §3, §4.1 Option A). This seam stays empty permanently for `play`, and the
// dangerous factory bodies (write/edit/delete/move/shell + the shell-tail read) exist ONLY in the
// sideload source set (app/src/sideload/.../WorkspaceToolsGate.kt) — they are never compiled into the
// Play APK. Verify with: javap on the playDebug WorkspaceToolsKt class lists no
// create{Write,Edit,Delete,Move,Shell}Tool.
internal fun sideloadWorkspaceTools(
    workspaceId: String,
    conversationId: Uuid,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
): List<Tool> = emptyList()
