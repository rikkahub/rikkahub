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
        // Phase 16 audit fix — read-only accessor so the LLM can show a skill's content
        // without re-installing it. Sits under the same skills surface as use_skill.
        skillGetContentTool(
            enabledSkills = enabledSkills,
            allSkills = allSkills,
            contentReader = skillManager::getContent,
        ),
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
                        // Both branches go through SkillManager's mtime-aware cache so
                        // the per-turn auto-load reads are O(stat) on cache hit rather
                        // than O(file I/O) — N auto-load skills × every turn used to
                        // re-read SOUL/HEARTBEAT/etc from disk every time.
                        val body = runCatching {
                            if (path.isNullOrBlank()) {
                                skillManager.readSkillBody(skill.name)
                            } else {
                                skillManager.readSkillFileCached(skill.name, path)
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
                // Return structured error envelopes instead of throwing. AICore /
                // small models hit `use_skill` with `{}` (no name) regularly; before
                // this fix the LLM saw a 20-frame Java stack trace and gave up. The
                // recovery hint + available_skills list lets the model self-correct
                // on its next call.
                fun err(code: String, detail: String): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", code)
                            put("detail", detail)
                            put("recovery", "Re-call use_skill with one of the listed skill names in `name`.")
                            put(
                                "available_skills",
                                kotlinx.serialization.json.buildJsonArray {
                                    enabledSkills.forEach {
                                        add(kotlinx.serialization.json.JsonPrimitive(it))
                                    }
                                },
                            )
                        }.toString()
                    )
                )
                // Refuse oversized skill files before reading them whole. SkillManager
                // enforces the same cap on its cached reads (readCached); this is the
                // model-facing envelope so the LLM gets a clean error instead of a
                // failed/empty read.
                fun tooLargeErr(file: java.io.File): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "skill_file_too_large")
                            put("max_bytes", SkillManager.MAX_SKILL_FILE_BYTES)
                            put("size_bytes", file.length())
                        }.toString()
                    )
                )
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: return@Tool err(
                        "missing_required_arg",
                        "use_skill requires a 'name' argument identifying which skill to load.",
                    )
                if (name !in enabledSkills) {
                    return@Tool err(
                        "skill_not_enabled",
                        "Skill '$name' is not in the enabled-skills set for this assistant.",
                    )
                }
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                if (path.isNullOrBlank()) {
                    val skillMd = skillManager.getSkillDir(name)?.resolve("SKILL.md")
                    if (skillMd != null && skillMd.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                        return@Tool tooLargeErr(skillMd)
                    }
                    val content = skillManager.readSkillBody(name)
                        ?: return@Tool err(
                            "skill_body_not_found",
                            "Skill '$name' is enabled but its SKILL.md body could not be read on disk.",
                        )
                    return@Tool listOf(UIMessagePart.Text(content))
                }
                val target = skillManager.resolveSkillFile(name, path)
                    ?: return@Tool err(
                        "path_outside_skill",
                        "Path '$path' resolves outside the '$name' skill directory.",
                    )
                if (!target.exists()) {
                    return@Tool err(
                        "skill_file_not_found",
                        "File '$path' does not exist in skill '$name'. Use only paths from Markdown links inside SKILL.md.",
                    )
                }
                if (target.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                    return@Tool tooLargeErr(target)
                }
                listOf(UIMessagePart.Text(target.readText()))
            }
        )
    )
}
