package me.rerere.rikkahub.skills

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Phase 16 — `skill_install_from_url` LLM tool.
 *
 * Always requires per-call approval (NO_ALWAYS_ALLOW). Skills installed this way can ride
 * along with the assistant's full tool surface — the user must consent every single time
 * because the source URL is whatever the LLM said it was.
 *
 * Returns the imported skill's name + format on success so the LLM knows to immediately
 * call `use_skill(name)` if appropriate.
 */
fun skillInstallFromUrlTool(importer: SkillUrlImporter): Tool = Tool(
    name = "skill_install_from_url",
    description = """
        Download and install a skill from a URL. Accepts native (RikkaHub markdown +
        frontmatter), openclaw markdown, or Hermes JSON formats. Tool names are best-effort
        transcoded to RikkaHub equivalents. The user reviews and approves the URL + final
        skill name before save. Returns { ok, name, format, source_url } on success.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "http(s) URL of the skill markdown or JSON. Loopback / private IPs are rejected.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional override for the skill's saved name (1..40 chars, [a-z0-9-_]). Defaults to whatever the source declares.")
                })
            },
            required = listOf("url"),
        )
    },
    needsApproval = true,
    execute = { json ->
        val url = json.jsonObject["url"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_url", "url is required")
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        when (val r = importer.importFromUrl(url, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_url", url)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

/**
 * Phase 16 — `skill_install_from_text`. Companion to `skill_install_from_url` for sources
 * the URL importer can't reach: authenticated servers, private intranets, SSH-only hosts,
 * MCP-relayed skill servers, or content the user just pasted into chat.
 *
 * Compose with any read-capable tool to bridge the gap, e.g.:
 *  - `ssh_exec_saved(host_label="myserver", command="cat ~/skills/morning.md")` → pipe stdout
 *  - `termux_run_command(command="curl -H 'Authorization: Bearer …' https://my.server/skill.md")`
 *  - any MCP tool returning a skill body
 *
 * Same approval gate as `skill_install_from_url` — every install is reviewed individually
 * (NO_ALWAYS_ALLOW). Same format detect + tool-name transcode + persistence as the URL path.
 */
fun skillInstallFromTextTool(importer: SkillUrlImporter): Tool = Tool(
    name = "skill_install_from_text",
    description = """
        Install a skill from raw text content (markdown or JSON) the LLM already has —
        useful when the source is behind auth and was fetched via ssh_exec / termux_run_command /
        an MCP tool. Accepts the same three formats as skill_install_from_url (native /
        openclaw / Hermes). The user reviews + approves the source label + skill name.
        Returns { ok, name, format, source_label } on success.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full skill body — markdown with frontmatter (native / openclaw) or JSON (Hermes). Capped at 256KB.")
                })
                put("source_label", buildJsonObject {
                    put("type", "string")
                    put("description", "Where this came from: URL, ssh host label, MCP tool, 'clipboard', etc. Persisted in the skill's frontmatter for audit.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional override for the skill's saved name (1..40 chars, [a-z0-9-_]). Defaults to whatever the source declares.")
                })
            },
            required = listOf("content"),
        )
    },
    needsApproval = true,
    execute = { json ->
        val content = json.jsonObject["content"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_content", "content is required")
        val sourceLabel = json.jsonObject["source_label"]?.jsonPrimitive?.contentOrNull
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        when (val r = importer.importFromText(content, sourceLabel, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_label", JsonPrimitive(sourceLabel ?: "imported"))
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

private fun err(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }.toString()))
