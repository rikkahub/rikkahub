package me.rerere.rikkahub.skills

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager

private const val TAG = "SkillInstallTools"

/**
 * Phase 16 audit fix — outcome of the auto-enable step that rides along with a successful
 * `skill_install_from_*` call. Decoupled from the DataStore write so it can be unit-tested
 * against a plain in-memory `Set<String>` without an Android Context.
 *
 * @property autoEnabled true when the skill is enabled for the calling assistant after the
 * install (either freshly enabled, or already-enabled and left alone).
 * @property updatedEnabledSkills the new enabled-skills set to persist, or null when no
 * persistence is needed (already enabled, or preserved-disabled).
 */
data class AutoEnableOutcome(
    val autoEnabled: Boolean,
    val detail: String,
    val updatedEnabledSkills: Set<String>?,
)

/**
 * Decide what the auto-enable step should do, given the calling assistant's current
 * [enabledSkills] set, the freshly-installed [skillName], and whether that skill
 * [existedBefore] the install wrote to disk.
 *
 *  - Already enabled → no-op, stays enabled (idempotent re-install).
 *  - New skill (did not exist before) → enable it.
 *  - Existed before but not enabled → the user previously disabled it; respect that and
 *    leave it disabled.
 */
fun decideAutoEnable(
    enabledSkills: Set<String>,
    skillName: String,
    existedBefore: Boolean,
): AutoEnableOutcome = when {
    skillName in enabledSkills -> AutoEnableOutcome(
        autoEnabled = true,
        detail = "Skill is already enabled for the active assistant and ready to use.",
        updatedEnabledSkills = null,
    )

    existedBefore -> AutoEnableOutcome(
        autoEnabled = false,
        detail = "Skill content was updated but it remains disabled (you previously disabled it). " +
            "Re-enable in Settings > Assistants > Skills before calling use_skill.",
        updatedEnabledSkills = null,
    )

    else -> AutoEnableOutcome(
        autoEnabled = true,
        detail = "Skill is now enabled for the active assistant and ready to use.",
        updatedEnabledSkills = enabledSkills + skillName,
    )
}

/**
 * Run the auto-enable side effect for a freshly-installed [skillName]: resolve the calling
 * assistant, decide via [decideAutoEnable], and persist the updated enabled-skills set when
 * needed. Returns the outcome so the install tool can surface it in the response envelope.
 *
 * A DataStore write failure (or any other unexpected error) is caught and reported as
 * `autoEnabled = false` with a recovery hint — the install itself already succeeded, so we
 * never fail the whole tool call over the auto-enable step.
 */
private suspend fun applyAutoEnable(
    settingsStore: SettingsStore,
    skillName: String,
    existedBeforeInstall: Boolean,
): AutoEnableOutcome {
    return try {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        val outcome = decideAutoEnable(assistant.enabledSkills, skillName, existedBeforeInstall)
        val newSet = outcome.updatedEnabledSkills
        if (newSet != null) {
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.map { a ->
                        if (a.id == assistant.id) a.copy(enabledSkills = newSet) else a
                    }
                )
            }
        }
        outcome
    } catch (t: Throwable) {
        Log.w(TAG, "applyAutoEnable: failed to auto-enable '$skillName'", t)
        AutoEnableOutcome(
            autoEnabled = false,
            detail = "Skill installed but could not be auto-enabled. Toggle it on in " +
                "Settings > Assistants > Skills before calling use_skill.",
            updatedEnabledSkills = null,
        )
    }
}

/**
 * Snapshot of every skill name on disk. Captured *before* the import writes, so the install
 * tool can tell a brand-new skill apart from a re-install of an existing (possibly disabled)
 * one — the importer itself doesn't surface that distinction.
 */
private fun existingSkillNames(skillManager: SkillManager): Set<String> =
    runCatching { skillManager.listSkills().map { it.name }.toSet() }.getOrDefault(emptySet())

/**
 * Phase 16 — `skill_install_from_url` LLM tool.
 *
 * Always requires per-call approval (NO_ALWAYS_ALLOW). Skills installed this way can ride
 * along with the assistant's full tool surface — the user must consent every single time
 * because the source URL is whatever the LLM said it was.
 *
 * Newly-installed skills are auto-enabled for the calling assistant so `use_skill` works on
 * the next turn without a manual toggle (Phase 16 audit fix). A re-install of a skill the
 * user previously disabled is left disabled — see `auto_enabled` / `auto_enabled_detail` in
 * the response.
 */
fun skillInstallFromUrlTool(
    importer: SkillUrlImporter,
    settingsStore: SettingsStore,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_install_from_url",
    description = """
        Download and install a skill from a URL. Accepts native (RikkaHub markdown +
        frontmatter), openclaw markdown, or Hermes JSON formats. Tool names are best-effort
        transcoded to RikkaHub equivalents. The user reviews and approves the URL + final
        skill name before save. Newly-installed skills are auto-enabled for the calling
        assistant unless they previously existed and were disabled. Returns
        { ok, name, format, source_url, auto_enabled, auto_enabled_detail } on success.
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
    needsApproval = { true },
    execute = { json ->
        val url = json.jsonObject["url"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_url", "url is required")
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val namesBeforeInstall = existingSkillNames(skillManager)
        when (val r = importer.importFromUrl(url, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val existedBefore = r.metadata.name in namesBeforeInstall
                val outcome = applyAutoEnable(settingsStore, r.metadata.name, existedBefore)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_url", url)
                    put("auto_enabled", outcome.autoEnabled)
                    put("auto_enabled_detail", outcome.detail)
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
 * (NO_ALWAYS_ALLOW). Same format detect + tool-name transcode + persistence as the URL path,
 * and the same auto-enable behavior (Phase 16 audit fix).
 */
fun skillInstallFromTextTool(
    importer: SkillUrlImporter,
    settingsStore: SettingsStore,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_install_from_text",
    description = """
        Install a skill from raw text content (markdown or JSON) the LLM already has —
        useful when the source is behind auth and was fetched via ssh_exec / termux_run_command /
        an MCP tool. Accepts the same three formats as skill_install_from_url (native /
        openclaw / Hermes). The user reviews + approves the source label + skill name.
        Newly-installed skills are auto-enabled for the calling assistant unless they
        previously existed and were disabled. Returns
        { ok, name, format, source_label, auto_enabled, auto_enabled_detail } on success.
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
    needsApproval = { true },
    execute = { json ->
        val content = json.jsonObject["content"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_content", "content is required")
        val sourceLabel = json.jsonObject["source_label"]?.jsonPrimitive?.contentOrNull
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val namesBeforeInstall = existingSkillNames(skillManager)
        when (val r = importer.importFromText(content, sourceLabel, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val existedBefore = r.metadata.name in namesBeforeInstall
                val outcome = applyAutoEnable(settingsStore, r.metadata.name, existedBefore)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_label", JsonPrimitive(sourceLabel ?: "imported"))
                    put("auto_enabled", outcome.autoEnabled)
                    put("auto_enabled_detail", outcome.detail)
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
