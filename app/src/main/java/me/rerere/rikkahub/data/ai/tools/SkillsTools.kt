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
                    // Auto-load skills with `auto_load: true` in their SKILL.md frontmatter:
                    // their body (auto_load_path file if set, else SKILL.md) is inlined into
                    // the system prompt every turn, no `use_skill` call needed. Use for the
                    // "core persona" skills (agent-core/SOUL.md). Models that previously
                    // never bothered to discover the SOUL via use_skill now see it on turn 1.
                    val autoLoaded = available.filter { it.autoLoad }
                    autoLoaded.forEach { skill ->
                        val path = skill.autoLoadPath
                        val body = runCatching {
                            if (path.isNullOrBlank()) {
                                skillManager.readSkillBody(skill.name)
                            } else {
                                skillManager.resolveSkillFile(skill.name, path)
                                    ?.takeIf { it.exists() }
                                    ?.readText()
                            }
                        }.getOrNull()
                        if (!body.isNullOrBlank()) {
                            appendLine(body.trim())
                            appendLine()
                        }
                    }

                    // Lazy skills — listed for discovery; loaded on demand via `use_skill`.
                    val lazy = available.filterNot { it.autoLoad }
                    if (lazy.isNotEmpty()) {
                        appendLine("**Skills**")
                        appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                        appendLine("<available_skills>")
                        lazy.forEach { skill ->
                            appendLine("  <skill>")
                            appendLine("    <name>${skill.name}</name>")
                            appendLine("    <description>${skill.description}</description>")
                            appendLine("  </skill>")
                        }
                        append("</available_skills>")
                        appendLine()
                    }
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
