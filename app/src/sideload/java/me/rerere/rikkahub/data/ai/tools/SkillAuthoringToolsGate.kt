package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager

// Sideload-distributed flavor: the skill WRITE capability (create_skill / update_skill) is present.
// These bodies are physically ABSENT from the Play APK — the play source set keeps an empty seam —
// because model-authored skills are a persistent, write-capable agent capability, mirroring the
// workspace write/shell gating (I-FLAVOR, design note security-model-design:197). Each tool is also
// approval-required, and is only ever assembled while a slash-activated authoring session is armed.

// Whether the skill WRITE surface exists in this flavor. The slash picker and the ChatVM interceptor
// read this so the Play build neither advertises nor arms a /create_skill /update_skill that has no
// tool behind it (mirrors AUTOMATION_YOLO_SUPPORTED). Sideload = true; the play seam sets it false.
internal const val SKILL_AUTHORING_SUPPORTED: Boolean = true

internal val CREATE_SKILL_TOOL_DESCRIPTION = """
    Author a NEW skill by writing a complete text file bundle. A skill is a folder containing SKILL.md
    plus optional helper files (for example references/foo.md or scripts/bar.sh) that SKILL.md links to
    with Markdown links. `files` maps skill-root-relative paths to complete text file contents; it is not
    a diff/patch format and cannot carry binary data. `files` MUST include "SKILL.md". SKILL.md must
    start with YAML frontmatter setting `name` (the unique skill id; no slashes) and `description` (one
    line shown to the model for discovery), followed by the instructions body. The whole bundle is
    written atomically. Creation refuses an existing skill name; use update_skill for existing skills.
    The new skill is NOT auto-enabled; the user enables it from the skills screen.
""".trimIndent().replace("\n", " ")

internal val UPDATE_SKILL_TOOL_DESCRIPTION = """
    Modify an EXISTING skill by applying full-file writes/deletes to its current bundle. `name` is the
    existing skill directory id. `changes` maps skill-root-relative paths to complete replacement text for
    those files; it is not a diff/patch format. `deletes` removes whole files by relative path. Files you
    do NOT mention are preserved as-is. Paths are normalized inside the skill directory; escaping or
    ambiguous paths are rejected. You cannot delete SKILL.md or rename the skill. If `changes` includes
    SKILL.md, it must be the complete SKILL.md with YAML frontmatter whose `name` stays equal to `name`
    and whose `description` is non-blank. At least one of `changes`/`deletes` is required.
""".trimIndent().replace("\n", " ")

internal fun sideloadSkillAuthoringTools(
    spec: SkillAuthoringSpec,
    skillManager: SkillManager,
): List<Tool> = when (spec.mode) {
    SkillAuthoringMode.Create -> listOf(createCreateSkillTool(skillManager))
    SkillAuthoringMode.Update -> listOf(createUpdateSkillTool(spec, skillManager))
}

private fun createCreateSkillTool(
    skillManager: SkillManager,
) = Tool(
    name = CREATE_SKILL_TOOL_NAME,
    description = CREATE_SKILL_TOOL_DESCRIPTION,
    systemPrompt = { _, _ ->
        "The user asked to CREATE a skill. Author it in THIS turn by calling create_skill with a files " +
            "map of skill-root-relative paths to complete text file contents, not diffs or fragments. " +
            "SKILL.md must have YAML frontmatter (name + description) and a clear instructions body; " +
            "add references/ or scripts/ files only if the skill needs them and link to them from " +
            "SKILL.md. The write requires the user's approval. The create_skill tool is available only " +
            "for this turn - make reasonable choices for any unspecified detail instead of deferring; " +
            "only if you truly cannot tell what to build, ask the user to re-run /create_skill <description>."
    },
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("files", buildJsonObject {
                    put("type", "object")
                    put(
                        "description",
                        "Map of skill-root-relative file path to complete text content. Not a diff; " +
                            "include every file the new skill should contain. Must include \"SKILL.md\"."
                    )
                })
            },
            required = listOf("files"),
        )
    },
    execute = {
        val files = it.jsonObject.stringMap("files")
        require(files.isNotEmpty()) { "files is required and must be a non-empty object of path -> content" }
        val skillMd = files["SKILL.md"] ?: error("files must include a \"SKILL.md\" entry")
        val frontmatter = SkillFrontmatterParser.parse(skillMd)
        val name = frontmatter["name"]?.takeIf { n -> n.isNotBlank() }
            ?: error("SKILL.md frontmatter must set a non-blank `name`")
        require(frontmatter["description"]?.isNotBlank() == true) {
            "SKILL.md frontmatter must set a non-blank `description`"
        }
        // create is for NEW skills only — the atomic save replaces the whole directory, so creating over
        // an existing name would silently drop its helper files. Refuse and point at update_skill.
        require(!skillManager.skillExists(name)) {
            "A skill named '$name' already exists. Use /update_skill to modify it instead of create_skill."
        }
        val ok = skillManager.saveSkillFilesAtomically(name, files)
        require(ok) { "Failed to create skill '$name' (invalid name or write error)" }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("created", name)
                    put("files", files.keys.joinToString(", "))
                }.toString()
            )
        )
    },
)

private fun createUpdateSkillTool(
    spec: SkillAuthoringSpec,
    skillManager: SkillManager,
) = Tool(
    name = UPDATE_SKILL_TOOL_NAME,
    description = UPDATE_SKILL_TOOL_DESCRIPTION,
    systemPrompt = { _, _ ->
        buildString {
            append("The user asked to UPDATE a skill")
            spec.targetSkill?.takeIf { it.isNotBlank() }?.let { append(" (target: \"$it\")") }
            append(". In THIS turn, inspect the current skill content when available (for enabled skills, ")
            append("use `use_skill`; helper file paths should come from SKILL.md links), then call ")
            append("update_skill with complete replacement file contents in `changes` and any whole-file ")
            append("removals in `deletes`. Do not send diffs or partial file fragments. If changing ")
            append("SKILL.md, include the full SKILL.md with unchanged `name` and non-blank `description`. ")
            append("The write requires the user's approval. The update_skill tool is available only ")
            append("for this turn - if you ")
            append("need more direction, ask the user to re-run /update_skill <name> rather than deferring.")
        }
    },
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "The existing skill's name (its directory id).")
                })
                put("changes", buildJsonObject {
                    put("type", "object")
                    put(
                        "description",
                        "Map of skill-root-relative path to complete replacement text for that file. " +
                            "Not a diff. Omitted files are preserved."
                    )
                })
                put("deletes", buildJsonObject {
                    put("type", "array")
                    put("description", "Skill-root-relative file paths to delete. Cannot remove SKILL.md (listing it here is only valid if `changes` re-adds a complete SKILL.md).")
                })
            },
            required = listOf("name"),
        )
    },
    execute = {
        val params = it.jsonObject
        val name = params["name"]?.jsonPrimitive?.contentOrNull?.takeIf { n -> n.isNotBlank() }
            ?: error("name is required")
        val changes = params.stringMap("changes")
        val deletes = params.stringArray("deletes")
        require(changes.isNotEmpty() || deletes.isNotEmpty()) {
            "Provide at least one of `changes` or `deletes`"
        }
        val ok = skillManager.updateSkillBundle(name, changes, deletes.toSet())
        require(ok) {
            "Failed to update skill '$name' (missing skill, a path escaped the skill directory, or the " +
                "change would remove or invalidate SKILL.md)"
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("updated", name)
                    put("changed", changes.keys.joinToString(", "))
                    put("deleted", deletes.joinToString(", "))
                }.toString()
            )
        )
    },
)

// A JSON object arg as path -> content. Tolerates a missing key (-> empty) but REJECTS a non-string
// value loudly: silently dropping e.g. an object-valued `changes` entry could, combined with a same-path
// `deletes`, turn an intended replace into a pure delete (data loss). Fail the whole call instead.
private fun JsonObject.stringMap(key: String): Map<String, String> {
    val obj = (this[key] as? JsonObject) ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    obj.forEach { (k, v) ->
        val content = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Value for \"$k\" in `$key` must be a string (the file's text content)")
        out[k] = content
    }
    return out
}

// A JSON array arg of strings; tolerates a missing key (-> empty) and ignores non-string elements.
private fun JsonObject.stringArray(key: String): List<String> {
    val arr = (this[key] as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
}
