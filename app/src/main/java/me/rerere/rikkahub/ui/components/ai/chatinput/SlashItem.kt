package me.rerere.rikkahub.ui.components.ai.chatinput

import me.rerere.rikkahub.data.ai.tools.SKILL_AUTHORING_SUPPORTED
import me.rerere.rikkahub.data.files.SkillMetadata

/**
 * One row in the "/" slash-command picker (#364 slice 4). The picker now lists TWO kinds of rows:
 *  - [Builtin] — a reserved NATIVE command (`/goal`, `/loop`). It is NOT a skill: selecting it only
 *    drops `/<name> ` into the input; the ChatVM reserved-command handler runs it on send. Arming a
 *    `use_skill` (the [Skill] path) would be wrong — these commands have no skill behind them.
 *  - [Skill] — an on-disk skill, as before; selecting it arms the skill on the active assistant.
 *
 * Pure data (no Compose / icon coupling) so [filterSlashItems] is JVM-unit-testable; the popup maps a
 * row to its icon at render time.
 */
internal sealed interface SlashItem {
    val name: String
    val description: String

    /** Stable LazyColumn key — type-prefixed so a built-in and a same-named skill never collide. */
    val key: String

    data class Builtin(
        override val name: String,
        override val description: String,
    ) : SlashItem {
        override val key: String get() = "builtin:$name"
    }

    data class Skill(val metadata: SkillMetadata) : SlashItem {
        override val name: String get() = metadata.name
        override val description: String get() = metadata.description
        override val key: String get() = "skill:${metadata.name}"
    }
}

/**
 * The reserved native commands (#364) surfaced as built-in rows. Listed FIRST in the picker so the
 * in-session commands are discoverable above the (possibly long) skill list. The skill-authoring
 * commands are appended only when the flavor actually has the WRITE surface
 * ([SKILL_AUTHORING_SUPPORTED]) — the Play build must not advertise a command with no tool behind it.
 */
internal val BUILTIN_SLASH_COMMANDS: List<SlashItem.Builtin> = buildList {
    add(
        SlashItem.Builtin(
            name = "goal",
            description = "Work autonomously toward a condition until it's met. /goal clear to stop.",
        )
    )
    add(
        SlashItem.Builtin(
            name = "loop",
            description = "Re-run a prompt on a durable schedule. /loop clear to stop.",
        )
    )
    if (SKILL_AUTHORING_SUPPORTED) {
        add(
            SlashItem.Builtin(
                name = "create_skill",
                description = "Have the assistant author a new skill (asks for approval before writing).",
            )
        )
        add(
            SlashItem.Builtin(
                name = "update_skill",
                description = "Have the assistant modify an existing skill. /update_skill <name>",
            )
        )
    }
}

/**
 * Filter the combined built-in + skill rows for the slash [query] (the text after "/"). Built-ins lead
 * (discoverability), then skills in their given order. An empty/blank query shows everything; otherwise
 * a case-insensitive substring match on name OR description (mirroring the prior skills-only filter, so
 * "/go" surfaces `goal` and "/lo" surfaces `loop`). PURE so it is unit-testable.
 */
internal fun filterSlashItems(query: String?, skills: List<SkillMetadata>): List<SlashItem> {
    val all: List<SlashItem> = BUILTIN_SLASH_COMMANDS + skills.map { SlashItem.Skill(it) }
    val q = query?.trim()?.lowercase().orEmpty()
    if (q.isEmpty()) return all
    return all.filter {
        it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
    }
}
