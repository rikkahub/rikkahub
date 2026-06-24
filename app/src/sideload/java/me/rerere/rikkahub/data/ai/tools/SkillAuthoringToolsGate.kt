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
    description = """
        Author a NEW skill. A skill is a folder containing SKILL.md plus optional helper files
        (e.g. references/foo.md, scripts/bar.sh) that SKILL.md links to with Markdown links. SKILL.md
        must start with YAML frontmatter setting `name` (the unique skill id; letters/digits/dashes, no
        slashes) and `description` (one line shown to the model for discovery), followed by the
        instructions body. Pass every file in `files` (relative path -> content); `files` MUST include
        "SKILL.md". The whole bundle is written atomically. The new skill is NOT auto-enabled; the user
        enables it from the skills screen.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        "The user asked to CREATE a skill. Author it in THIS turn by calling create_skill with a files " +
            "map whose SKILL.md has YAML frontmatter (name + description) and a clear instructions body; " +
            "add references/ or scripts/ files only if the skill needs them and link to them from " +
            "SKILL.md. The write requires the user's approval. The create_skill tool is available only " +
            "for this turn — make reasonable choices for any unspecified detail instead of deferring; " +
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
                        "Map of relative file path to its text content. Must include \"SKILL.md\". Example: {\"SKILL.md\": \"---\\nname: my-skill\\ndescription: ...\\n---\\n...\", \"references/notes.md\": \"...\"}."
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
    description = """
        Modify an EXISTING skill. `changes` writes or overwrites files (relative path -> content);
        `deletes` removes files by relative path. Files you do NOT mention are kept as-is. You cannot
        delete SKILL.md or rename the skill. Read the current content first via the use_skill tool
        (use_skill(name) for SKILL.md, use_skill(name, path) for a helper file) so your changes are
        correct. At least one of `changes`/`deletes` is required.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        buildString {
            append("The user asked to UPDATE a skill")
            spec.targetSkill?.takeIf { it.isNotBlank() }?.let { append(" (target: \"$it\")") }
            append(". In THIS turn, first read its current files with use_skill, then call update_skill ")
            append("with the changed files in `changes` and any removals in `deletes`. The write requires ")
            append("the user's approval. The update_skill tool is available only for this turn — if you ")
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
                    put("description", "Map of relative file path to new content to write/overwrite. Omit a file to keep it unchanged.")
                })
                put("deletes", buildJsonObject {
                    put("type", "array")
                    put("description", "Relative file paths to delete. Cannot include SKILL.md.")
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
