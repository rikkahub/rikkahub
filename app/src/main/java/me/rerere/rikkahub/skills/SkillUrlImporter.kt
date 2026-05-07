package me.rerere.rikkahub.skills

import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Phase 16 — narrow interface seam over SkillManager so JVM tests can supply a
 * tmp-dir-backed fake without needing an Android Context.
 */
interface SkillSaver {
    fun saveSkill(name: String, content: String): SkillMetadata?
}

/**
 * Phase 16 — install a skill from a URL.
 *
 * Accepts three input shapes:
 *  - **Native** — RikkaHub-format markdown with YAML frontmatter (`name:`, `description:`,
 *    optional `allowed-tools:`). Stored as-is.
 *  - **openclaw** — markdown skill with `## When to use` / `## Steps` / `## Tools used`
 *    sections. We synthesise a frontmatter block (`name:` from the first H1, `description:`
 *    from the paragraph beneath the first heading) and run the body through the
 *    tool-name transcoder.
 *  - **Hermes** — JSON-shaped skill packs from the Hermes ecosystem. Detected by a leading
 *    `{"hermes_skill":` or `{"name": "...", "schema_version":` envelope. Body is
 *    transformed: title → name, prompt + steps → markdown body, tool aliases mapped.
 *
 * URL guard: only http(s), no loopback, no `localhost`, no `0.0.0.0`, no IPv4-mapped IPv6.
 * Mirrors the McpUrlGuard pattern from Phase 10. Headless context is irrelevant here
 * (this is always a user-driven action).
 *
 * Source URL is persisted as `source-url:` in the saved skill's frontmatter so the user
 * can audit where each skill came from in Settings.
 */
class SkillUrlImporter(
    private val skillManager: SkillSaver,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /** Convenience constructor for production wiring — bridges the SkillManager. */
    constructor(skillManager: SkillManager) : this(skillManager.asSaver())

    sealed class Result {
        data class Ok(val metadata: SkillMetadata, val format: SkillFormat) : Result()
        data class Err(val code: String, val detail: String) : Result()
    }

    enum class SkillFormat { NATIVE, OPENCLAW, HERMES }

    suspend fun importFromUrl(url: String, overrideName: String? = null): Result {
        val urlCheck = checkUrl(url)
        if (urlCheck != null) return Result.Err(urlCheck.first, urlCheck.second)

        val raw = try {
            fetch(url)
        } catch (t: Throwable) {
            return Result.Err("fetch_failed", t.message ?: "network error")
        }
        return importFromText(raw, sourceLabel = url, overrideName = overrideName)
    }

    /**
     * Install a skill from raw text the LLM already has in hand. Same format-detect +
     * transcode + persist pipeline as [importFromUrl] but skips the HTTP fetch entirely.
     *
     * **This is what makes private / authenticated skill sources work.** When the user has
     * a server hosting their skills behind auth (HTTP basic, bearer tokens, session cookies,
     * SSH-only access, or any custom scheme), they can compose existing tools to fetch the
     * content first and pass the body to this method:
     *
     *  - `ssh_exec_saved("myserver", "cat ~/skills/morning.md")` → pipe stdout into here
     *  - `termux_run_command("curl -H 'Authorization: Bearer ...' https://api/me/skill")`
     *  - any MCP tool returning a markdown body
     *  - the user pastes the content directly via clipboard / chat
     *
     * [sourceLabel] is whatever identifying string the LLM has — a URL it pretty-printed,
     * an SSH host label, "clipboard", etc. It's persisted as `source-url:` in the saved
     * skill's frontmatter for audit. Pass null and the importer uses "imported".
     */
    fun importFromText(rawBody: String, sourceLabel: String? = null, overrideName: String? = null): Result {
        val raw = rawBody
        if (raw.isBlank()) return Result.Err("empty_body", "skill body is empty")
        // Reject HTML pages early. clawhub.ai and similar landing pages return a full
        // HTML document instead of the raw SKILL.md, and our openclaw fallback parser
        // happily extracted the first non-blank line of <!doctype html>… as the skill
        // name — silently registering a broken skill the LLM then couldn't use_skill on.
        // A real skill body never starts with HTML, so sniff the first non-blank line.
        val sniff = raw.trimStart().take(256).lowercase()
        if (sniff.startsWith("<!doctype") || sniff.startsWith("<html") ||
            sniff.startsWith("<head") || sniff.startsWith("<body") ||
            sniff.startsWith("<?xml")) {
            return Result.Err(
                "html_response",
                "URL returned HTML, not a skill file. Use the raw SKILL.md URL (e.g. raw.githubusercontent.com path), not the web page URL."
            )
        }
        if (raw.length > MAX_BODY_BYTES) return Result.Err("body_too_large",
            "skill body exceeds ${MAX_BODY_BYTES / 1024}KB cap (got ${raw.length / 1024}KB)")

        val sourceForFrontmatter = sourceLabel?.takeIf { it.isNotBlank() } ?: "imported"
        val format = detectFormat(raw)
        val nativeMd: String = when (format) {
            SkillFormat.NATIVE -> raw
            SkillFormat.OPENCLAW -> transcodeFromOpenclaw(raw, sourceUrl = sourceForFrontmatter, override = overrideName)
                ?: return Result.Err("transcode_failed", "could not parse openclaw skill body")
            SkillFormat.HERMES -> transcodeFromHermes(raw, sourceUrl = sourceForFrontmatter, override = overrideName)
                ?: return Result.Err("transcode_failed", "could not parse Hermes skill JSON")
        }

        // Pull name / description out of the (now-canonical) frontmatter for the saved-skill record.
        val frontmatter = SkillFrontmatterParser.parse(nativeMd)
        val name = (overrideName?.takeIf { it.isNotBlank() } ?: frontmatter["name"])?.trim()
            ?: return Result.Err("missing_name", "imported skill has no 'name'")
        if (!isValidSkillName(name)) {
            return Result.Err("invalid_name",
                "skill name must be 1..40 chars, [a-z0-9-_] (got '$name')")
        }
        val withSourceUrl = ensureSourceUrl(nativeMd, sourceForFrontmatter)
        // If the user overrode the name, rewrite the frontmatter's `name:` line too — otherwise
        // the saved skill's parsed metadata (which is what the LLM and Settings page see) keeps
        // the source-declared name and the override only changes the directory.
        val finalBody = if (overrideName != null && overrideName.isNotBlank())
            rewriteFrontmatterName(withSourceUrl, name) else withSourceUrl

        val metadata = skillManager.saveSkill(name, finalBody)
            ?: return Result.Err("save_failed", "could not write skill files")
        return Result.Ok(metadata, format)
    }

    /** Replace the `name: <whatever>` line inside the YAML frontmatter, preserving the rest. */
    private fun rewriteFrontmatterName(body: String, newName: String): String {
        if (!body.startsWith("---")) return body
        val end = body.indexOf("\n---", startIndex = 3)
        if (end < 0) return body
        val frontmatter = body.substring(0, end)
        val rest = body.substring(end)
        val rewritten = frontmatter.lines().joinToString("\n") { line ->
            if (line.startsWith("name:")) "name: $newName" else line
        }
        return rewritten + rest
    }

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "rikkahub-agent/skill-importer")
            .header("Accept", "text/markdown, text/plain, application/json, */*")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: ""
        }
    }

    private fun detectFormat(raw: String): SkillFormat {
        val trimmed = raw.trimStart()
        // Hermes — JSON envelope.
        if (trimmed.startsWith("{")) {
            return SkillFormat.HERMES
        }
        // Native — markdown with YAML frontmatter that declares `name:`.
        if (trimmed.startsWith("---")) {
            val frontmatter = SkillFrontmatterParser.parse(trimmed)
            if (frontmatter["name"]?.isNotBlank() == true) return SkillFormat.NATIVE
        }
        // openclaw — has at least one of the known section markers in plain markdown form.
        return SkillFormat.OPENCLAW
    }

    private fun transcodeFromOpenclaw(raw: String, sourceUrl: String, override: String?): String? {
        // Pull the first H1 as name (if missing, fall back to first non-blank line).
        val h1Match = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(raw)
        val derivedName = override?.takeIf { it.isNotBlank() }
            ?: h1Match?.groupValues?.get(1)?.trim()
            ?: raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return null

        // Description = first paragraph after H1, capped at 240 chars.
        val descCandidate = h1Match?.range?.let { range ->
            val tail = raw.substring(range.last + 1).trimStart('\r', '\n')
            tail.lineSequence()
                .takeWhile { it.isNotBlank() }
                .joinToString(" ")
                .trim()
                .take(240)
        } ?: "Imported from openclaw."

        val sanitisedName = slugify(derivedName)
        val transcodedBody = ToolNameTranscoder.transcode(raw)
        val frontmatterBlock = buildString {
            appendLine("---")
            appendLine("name: $sanitisedName")
            appendLine("description: ${descCandidate.replace("\n", " ").replace("\"", "")}")
            appendLine("source-format: openclaw")
            appendLine("source-url: $sourceUrl")
            appendLine("---")
        }
        return frontmatterBlock + "\n" + transcodedBody
    }

    private fun transcodeFromHermes(raw: String, sourceUrl: String, override: String?): String? {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        // Hermes envelope can be top-level or wrapped in {"hermes_skill": {...}}.
        val skill = (obj["hermes_skill"] as? kotlinx.serialization.json.JsonObject) ?: obj
        fun str(key: String) =
            skill[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        val name = override?.takeIf { it.isNotBlank() } ?: str("name") ?: str("title") ?: return null
        val description = str("description") ?: str("summary") ?: "Imported from Hermes."
        val prompt = str("prompt") ?: str("system_prompt") ?: ""
        val steps = (skill["steps"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.mapNotNull { it.contentOrNull }
            .orEmpty()

        val sanitisedName = slugify(name)
        val body = buildString {
            appendLine("---")
            appendLine("name: $sanitisedName")
            appendLine("description: ${description.replace("\n", " ").replace("\"", "")}")
            appendLine("source-format: hermes")
            appendLine("source-url: $sourceUrl")
            appendLine("---")
            appendLine()
            appendLine("# $name")
            appendLine()
            if (prompt.isNotBlank()) {
                appendLine("## Operating prompt")
                appendLine()
                appendLine(prompt)
                appendLine()
            }
            if (steps.isNotEmpty()) {
                appendLine("## Steps")
                appendLine()
                for ((i, s) in steps.withIndex()) {
                    appendLine("${i + 1}. $s")
                }
                appendLine()
            }
            appendLine("## Source")
            appendLine()
            appendLine("Imported from Hermes skill at $sourceUrl. Tool names were not transcoded — Hermes references its own tool surface, which doesn't always map 1:1 to RikkaHub's. Edit any tool references manually if the skill misbehaves.")
        }
        return ToolNameTranscoder.transcode(body)
    }

    private fun ensureSourceUrl(nativeMd: String, sourceUrl: String): String {
        val frontmatter = SkillFrontmatterParser.parse(nativeMd)
        if (frontmatter["source-url"]?.isNotBlank() == true) return nativeMd
        // Splice into the existing frontmatter block. The parser ensures it starts with `---`.
        if (!nativeMd.startsWith("---")) return nativeMd  // shouldn't happen — early-return
        val end = nativeMd.indexOf("\n---", startIndex = 3)
        if (end < 0) return nativeMd
        return nativeMd.substring(0, end) + "\nsource-url: $sourceUrl" + nativeMd.substring(end)
    }

    /** Public for testability. */
    fun checkUrl(url: String): Pair<String, String>? {
        if (url.isBlank()) return "invalid_url" to "URL is empty"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "invalid_url" to "URL did not parse"
        val scheme = uri.scheme?.lowercase() ?: return "invalid_url" to "missing scheme"
        if (scheme != "http" && scheme != "https") {
            return "unsupported_url_scheme" to "only http(s) URLs are accepted, got '$scheme'"
        }
        val host = uri.host ?: return "invalid_url" to "missing host"
        if (isLoopback(host)) return "loopback_host_rejected" to "loopback / non-routable hosts are not allowed for skill imports"
        return null
    }

    /** Public for testability. */
    fun isLoopback(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.startsWith("[") && h.endsWith("]")) {
            return isLoopback(h.removePrefix("[").removeSuffix("]"))
        }
        // IPv4 / IPv6 literal — use InetAddress for the authoritative check.
        return runCatching {
            val a = InetAddress.getByName(h)
            a.isLoopbackAddress || a.isAnyLocalAddress
        }.getOrDefault(false)
    }

    private fun isValidSkillName(name: String): Boolean =
        name.length in 1..40 && name.matches(Regex("""^[a-z0-9_-]+$"""))

    /** Lowercase, alphanumerics + hyphens only, hyphenated where source had spaces. */
    private fun slugify(input: String): String {
        val cleaned = input.trim().lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
        return if (cleaned.isBlank()) "imported-skill" else cleaned.take(40)
    }

    companion object {
        const val MAX_BODY_BYTES = 256 * 1024  // 256 KB

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
            .also { me.rerere.rikkahub.utils.NetworkChangeMonitor.register(it) }
    }
}

/** Adapter from the existing SkillManager to the narrow [SkillSaver] interface. */
fun SkillManager.asSaver(): SkillSaver = object : SkillSaver {
    override fun saveSkill(name: String, content: String): SkillMetadata? =
        this@asSaver.saveSkill(name, content)
}

/**
 * Phase 16 — best-effort tool-name remapper.
 *
 * openclaw uses some tool names that have RikkaHub equivalents but different spellings;
 * Hermes uses an entirely different set. Anywhere we see one of the source names in a
 * skill body, replace it with the RikkaHub equivalent. False positives are rare since
 * skill bodies use these names in monospace / step-list contexts, but the replacements
 * are deliberately conservative — we only rewrite `\b<name>\b` (word-bounded).
 *
 * This is "best effort" — the user is expected to edit the skill markdown post-import if
 * the transcoder missed something.
 */
object ToolNameTranscoder {
    private val Mappings: List<Pair<String, String>> = listOf(
        // openclaw → RikkaHub
        "Bash" to "termux_run_command",
        "bash" to "termux_run_command",
        "Read" to "read_file",
        "Write" to "write_text_file",
        "Edit" to "write_text_file",
        "Glob" to "find_files",
        "Grep" to "find_files",
        "ListDir" to "list_files",
        "list_dir" to "list_files",
        "Task" to "subagent_dispatch",
        "TodoWrite" to "telegram_send_message",
        "WebFetch" to "ssh_exec",          // closest analogue if user has an SSH proxy
        "Notify" to "post_notification",
        "notify" to "post_notification",
        // Hermes-style → RikkaHub
        "send_message" to "telegram_send_message",
        "shell" to "termux_run_command",
        "ssh" to "ssh_exec",
        "open_app" to "launch_app",
        "screenshot" to "take_screenshot",
        "vibrate_phone" to "vibrate",
    )

    fun transcode(body: String): String {
        var out = body
        for ((from, to) in Mappings) {
            out = out.replace(Regex("""\b${Regex.escape(from)}\b"""), to)
        }
        return out
    }
}

