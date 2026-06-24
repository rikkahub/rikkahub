package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata

/** Which authoring operation a slash-activated authoring session is armed for. */
enum class SkillAuthoringMode { Create, Update }

/** The model-facing names of the two authoring tools. Single source of truth: the sideload tool bodies
 *  name themselves with these, and the approval-resume path maps a resumed tool call back to its mode. */
const val CREATE_SKILL_TOOL_NAME = "create_skill"
const val UPDATE_SKILL_TOOL_NAME = "update_skill"

/**
 * Session-scoped skill-authoring intent (issue: model-facing /create_skill /update_skill). Armed by the
 * ChatVM slash interception (via the send boundary) and CONSUMED at per-turn tool assembly, so the
 * authoring tool is offered to exactly the arming turn and the approval-resume of an authoring call.
 * In-memory only (lives on ConversationSession), never persisted — like the autonomous goal — so a
 * process death cannot resurrect a write-capable mode.
 */
data class SkillAuthoringSpec(val mode: SkillAuthoringMode, val targetSkill: String? = null)

/** Map a resumed tool call's name back to the authoring spec it implies, or null when it is not an
 *  authoring tool. Used by the approval-resume path to re-arm the lease so the continuation that executes
 *  the approved write still finds its tool in the freshly assembled pool. */
fun skillAuthoringSpecForToolName(toolName: String): SkillAuthoringSpec? = when (toolName) {
    CREATE_SKILL_TOOL_NAME -> SkillAuthoringSpec(SkillAuthoringMode.Create)
    UPDATE_SKILL_TOOL_NAME -> SkillAuthoringSpec(SkillAuthoringMode.Update)
    else -> null
}

/**
 * Model-facing skill AUTHORING tools (create_skill / update_skill), built ONLY for a turn whose
 * skill-authoring lease was just consumed. The tool bodies live in the sideload source set (physically
 * absent from the Play APK, like the workspace write/shell tools); each write is approval-required.
 */
fun createSkillAuthoringTools(
    spec: SkillAuthoringSpec,
    skillManager: SkillManager,
): List<Tool> = sideloadSkillAuthoringTools(spec, skillManager)

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        appendLine("    <name>${skill.name}</name>")
                        appendLine("    <description>${skill.description}</description>")
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                if (name !in enabledSkills) {
                    error("Skill '$name' is not available. Available skills: ${enabledSkills.joinToString()}")
                }
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    skillManager.readSkillBody(name)
                        ?: error("Skill '$name' not found")
                } else {
                    val target = skillManager.resolveSkillFile(name, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}
