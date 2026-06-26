package me.rerere.rikkahub.data.ai.slash

import me.rerere.rikkahub.data.files.SkillMetadata

/**
 * The unified "/" slash-command registry (Part B slice 1). Modeled on Claude Code's single-Command
 * design — builtin commands and skills are the SAME mechanism, gathered from LAYERED sources, merged
 * into one list, looked up once, and dispatched once by kind. It replaces three scattered pieces:
 *  - the hard-coded `BUILTIN_SLASH_COMMANDS` list,
 *  - the reserved-command `when` in `ChatVM.handleReservedSlashCommand`, and
 *  - the longest-prefix skill matcher in `ChatVM.expandSlashSkillCommand`.
 *
 * Everything here is PURE (sources are passed in) so the registry is JVM-unit-testable; production wires
 * the flavor's reserved set (gated by the skill-authoring write surface) and the live skills.
 */

/** Which native reserved command (#364). A pure id; [ReservedCommand] is the dispatch discriminant. */
internal enum class ReservedCommand(val commandName: String) {
    Goal("goal"),
    Loop("loop"),
    CreateSkill("create_skill"),
    UpdateSkill("update_skill"),
}

/**
 * One entry in the registry — the SINGLE descriptor for every "/" command:
 *  - [Reserved] — a native command (`/goal`, `/loop`, and the sideload-only `/create_skill`,
 *    `/update_skill`). It performs a guaranteed side effect; it is NOT a skill.
 *  - [Skill] — an on-disk skill; selecting it arms the skill so its `use_skill` tool is exposed.
 *
 * Pure data (no Compose/icon coupling) so [filterSlashCommands] is unit-testable; the popup maps a row
 * to its icon at render time off [Reserved.command] / [Skill].
 */
internal sealed interface SlashCommand {
    val name: String
    val description: String

    /** Stable LazyColumn key — type-prefixed so a reserved command and a same-named skill never collide. */
    val key: String

    data class Reserved(
        val command: ReservedCommand,
        override val description: String,
    ) : SlashCommand {
        override val name: String get() = command.commandName
        override val key: String get() = "builtin:$name"
    }

    data class Skill(val metadata: SkillMetadata) : SlashCommand {
        override val name: String get() = metadata.name
        override val description: String get() = metadata.description
        override val key: String get() = "skill:${metadata.name}"
    }
}

/**
 * What a typed "/<name> <arg>" resolves to. Returned by [resolveSlashCommand] and dispatched once at the
 * send seam:
 *  - [Reserved] — run the native command's side effect; nothing is sent as a message for it here.
 *  - [Skill] — rewrite the input into a "Use the <name> skill." directive (see [buildUseSkillDirective]).
 *  - `null` (no invocation) — not a slash command; send the input verbatim.
 */
internal sealed interface SlashInvocation {
    data class Reserved(val command: ReservedCommand, val arg: String) : SlashInvocation
    data class Skill(val name: String, val param: String) : SlashInvocation
}

/**
 * The reserved native commands the active flavor advertises (#364). `/goal` and `/loop` are always
 * present; the skill-authoring commands are included ONLY when [skillAuthoringSupported] (the write
 * surface — sideload yes, play no), so the Play build neither lists nor intercepts a command with no
 * tool behind it. This is the ONE flavor gate; both the picker and the resolver consume this list.
 */
internal fun reservedSlashCommands(skillAuthoringSupported: Boolean): List<SlashCommand.Reserved> = buildList {
    add(
        SlashCommand.Reserved(
            ReservedCommand.Goal,
            "Work autonomously toward a condition until it's met. /goal clear to stop.",
        )
    )
    add(
        SlashCommand.Reserved(
            ReservedCommand.Loop,
            "Re-run a prompt on a durable schedule. /loop clear to stop.",
        )
    )
    if (skillAuthoringSupported) {
        add(
            SlashCommand.Reserved(
                ReservedCommand.CreateSkill,
                "Have the assistant author a new skill (asks for approval before writing).",
            )
        )
        add(
            SlashCommand.Reserved(
                ReservedCommand.UpdateSkill,
                "Have the assistant modify an existing skill. /update_skill <name>",
            )
        )
    }
}

/**
 * Filter the merged [reserved] + [skills] rows for the picker [query] (the text after "/"). Reserved
 * lead (discoverability), then skills in their given order. An empty/blank query shows everything;
 * otherwise a case-insensitive substring match on name OR description.
 */
internal fun filterSlashCommands(
    query: String?,
    reserved: List<SlashCommand.Reserved>,
    skills: List<SkillMetadata>,
): List<SlashCommand> {
    val all: List<SlashCommand> = reserved + skills.map { SlashCommand.Skill(it) }
    val q = query?.trim()?.lowercase().orEmpty()
    if (q.isEmpty()) return all
    return all.filter {
        it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
    }
}

/**
 * Resolve typed input into a [SlashInvocation], or null when it is not a slash command (send verbatim).
 * The ONE resolver that replaces both the reserved `when` and the skill prefix matcher:
 *  - Reserved is matched FIRST on the TRIMMED text (a reserved command performs a guaranteed side effect
 *    and must win over a same-named skill); arg is the remainder after the command token.
 *  - Skill is matched on the RAW text against the [enabledSkills] names by LONGEST prefix (so names with
 *    spaces resolve and the matched skill's `use_skill` tool — exposed only for enabled skills — exists).
 */
internal fun resolveSlashCommand(
    text: String,
    reserved: List<SlashCommand.Reserved>,
    enabledSkills: Collection<String>,
): SlashInvocation? {
    val trimmed = text.trim()
    for (cmd in reserved) {
        val token = "/${cmd.name}"
        if (trimmed == token || trimmed.startsWith("$token ")) {
            return SlashInvocation.Reserved(cmd.command, trimmed.removePrefix(token).trim())
        }
    }
    if (!text.startsWith("/")) return null
    val afterSlash = text.substring(1)
    val name = enabledSkills
        .filter { afterSlash == it || afterSlash.startsWith("$it ") || afterSlash.startsWith("$it\n") }
        .maxByOrNull { it.length } ?: return null
    return SlashInvocation.Skill(name, afterSlash.removePrefix(name).trim())
}

/**
 * The directive a [SlashInvocation.Skill] expands into: an explicit "Use the <name> skill." plus the
 * optional param, so the agent runs the (already-armed) skill via its `use_skill` tool.
 */
internal fun buildUseSkillDirective(name: String, param: String): String = buildString {
    append("Use the \"$name\" skill.")
    if (param.isNotEmpty()) {
        append("\n\n")
        append(param)
    }
}
