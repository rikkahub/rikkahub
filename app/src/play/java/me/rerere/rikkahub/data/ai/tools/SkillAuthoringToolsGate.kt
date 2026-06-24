package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.files.SkillManager

// Play-distributed flavor: model-facing skill WRITE (create_skill / update_skill) is PHYSICALLY ABSENT,
// mirroring the workspace write/shell gating (I-FLAVOR, design note security-model-design:197). This
// seam stays empty for `play`; the real bodies exist only in app/src/sideload/.../SkillAuthoringToolsGate.kt.

// Play has no skill WRITE surface, so the slash picker must not advertise — and ChatVM must not arm —
// a /create_skill /update_skill that has no tool behind it. Matches the empty seam below.
internal const val SKILL_AUTHORING_SUPPORTED: Boolean = false

internal fun sideloadSkillAuthoringTools(
    spec: SkillAuthoringSpec,
    skillManager: SkillManager,
): List<Tool> = emptyList()
