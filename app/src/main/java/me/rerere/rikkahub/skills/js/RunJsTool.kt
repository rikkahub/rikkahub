package me.rerere.rikkahub.skills.js

import android.content.Context
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager

/**
 * Phase 18 — `run_js` tool. The LLM uses this to invoke a JS skill that lives in the
 * user's installed-skills directory. Matches the contract Google AI Edge Gallery's skills
 * use, so any skill written for Gallery (with `window['ai_edge_gallery_get_result']`)
 * works here verbatim.
 *
 * Tool args:
 *   - skill_name: required, the skill's frontmatter name
 *   - script: optional, defaults to `index.html` (the convention)
 *   - data: optional, JSON string passed as the first arg to the JS function
 *   - secret_key: optional, name of a stored secret to pass as the second JS arg (skill API keys)
 *
 * Approval: ALWAYS_ASK — JS skill code can issue arbitrary network requests on behalf of
 * the user. The user reviews each invocation. Eligible for "Always allow" once trusted.
 *
 * Returns either a single text part with the JS skill's `result` field, an image part if
 * the skill returned base64 image data, or both. Webview returns are rendered as a Text
 * part with [WEBVIEW_METADATA_KEY] metadata so the chat renderer can show an embed.
 */
fun runJsTool(
    context: Context,
    skillManager: SkillManager,
    runner: JsSkillRunner,
    secretsStore: SkillSecretsStore,
): Tool = Tool(
    name = "run_js",
    description = """
        Run a JavaScript skill installed in the user's skills directory. The skill's
        index.html (or the named script) defines a global async function
        `ai_edge_gallery_get_result(data, secret)` that returns a JSON object. Use this
        for skills that need to compute something with rich logic (calculate-hash,
        query-wikipedia), render rich UI (interactive-map, qr-code), or use a third-party
        API (with secret_key).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("skill_name", buildJsonObject {
                    put("type", "string")
                    put("description", "The name of the installed JS skill (matches the skill's frontmatter `name`).")
                })
                put("script", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional script file path within the skill directory. Defaults to `index.html` if omitted. Only paths the SKILL.md explicitly references should be passed.")
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional JSON string passed as the first argument to the JS skill's `ai_edge_gallery_get_result` function. Cap 64KB.")
                })
                put("secret_key", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional name of a stored secret to pass as the JS function's second argument. Use for skills that need an API key. The user manages secrets via Settings.")
                })
            },
            required = listOf("skill_name"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val skillName = params["skill_name"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_skill_name", "skill_name is required")
        val scriptName = params["script"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "index.html"
        val data = params["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (data.length > JsSkillRunner.MAX_DATA_LENGTH) {
            return@Tool err("data_too_large", "data exceeds 64KB cap")
        }
        val secretKey = params["secret_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val secret = secretKey?.let { secretsStore.get(skillName, it) }.orEmpty()

        val skillDir = skillManager.getSkillDir(skillName)
            ?: return@Tool err("skill_not_found", "no installed skill named '$skillName'")
        val scriptFile = skillManager.resolveSkillFile(skillName, scriptName)
            ?: return@Tool err("invalid_script_path", "script path '$scriptName' is outside the skill directory")
        if (!scriptFile.exists()) {
            return@Tool err("script_not_found", "no file at '${scriptName}' inside skill '$skillName'")
        }

        val outcome = runner.runScript(scriptFile = scriptFile, skillRootDir = skillDir, data = data, secret = secret)
        when (outcome) {
            is JsSkillRunner.Result.Err -> err(outcome.code, outcome.detail)
            is JsSkillRunner.Result.Ok -> {
                val parsed = outcome.parsed
                if (parsed.error != null) {
                    return@Tool err("js_error", parsed.error)
                }
                val parts = mutableListOf<UIMessagePart>()
                // Image first — chat surface renders these prominently.
                parsed.imageBase64?.let { b64 ->
                    decodeBase64ImageToCacheFile(context, b64)?.let { f ->
                        parts.add(UIMessagePart.Image(url = f.toUri().toString()))
                    }
                }
                // Webview as a Text part with metadata; chat renderer (Phase 18B) detects
                // and shows an embed. Relative URLs from the JS skill are resolved against
                // the script file's parent directory and rewritten to `file://` so the
                // embed Composable can load them without knowing the skill layout.
                // Absolute http(s)/file/data URIs pass through unchanged.
                parsed.webviewUrl?.let { url ->
                    val resolvedUrl = resolveSkillWebviewUrl(url, scriptFile, skillDir)
                    if (resolvedUrl != null) {
                        parts.add(UIMessagePart.Text(
                            text = parsed.text ?: "Open in a viewer",
                            metadata = buildJsonObject {
                                put("rikkahub.webview", buildJsonObject {
                                    put("url", resolvedUrl)
                                    put("iframe", parsed.webviewIframe)
                                    put("aspect_ratio", parsed.webviewAspectRatio.toDouble())
                                    put("source", "js_skill:$skillName")
                                })
                            }
                        ))
                    } else {
                        val fallback = parsed.text
                        if (!fallback.isNullOrBlank()) {
                            parts.add(UIMessagePart.Text(fallback))
                        }
                    }
                } ?: parsed.text?.takeIf { it.isNotBlank() }?.let { t ->
                    parts.add(UIMessagePart.Text(t))
                }
                if (parts.isEmpty()) {
                    parts.add(UIMessagePart.Text("(skill returned no result)"))
                }
                parts
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

/**
 * Resolve a webview URL emitted by a JS skill. Absolute URIs (http/https/file/data) pass
 * through unchanged. Relative paths are resolved against the script file's parent
 * directory, rewritten to `file://` URIs, and verified to stay inside [skillDir] (path
 * traversal defence). Returns null if the resolved path escapes the skill dir.
 */
internal fun resolveSkillWebviewUrl(
    url: String,
    scriptFile: java.io.File,
    skillDir: java.io.File,
): String? {
    val raw = url.trim()
    if (raw.isEmpty()) return null
    if (raw.startsWith("http://", ignoreCase = true)
        || raw.startsWith("https://", ignoreCase = true)
        || raw.startsWith("file://", ignoreCase = true)
        || raw.startsWith("data:", ignoreCase = true)
    ) return raw

    val pathOnly = raw.substringBefore('?').substringBefore('#')
    val query = raw.substring(pathOnly.length) // includes leading ? or # if present
    val parent = scriptFile.parentFile ?: skillDir
    val target = java.io.File(parent, pathOnly)
    val targetCanonical = runCatching { target.canonicalPath }.getOrNull() ?: return null
    val skillCanonical = runCatching { skillDir.canonicalPath }.getOrNull() ?: return null
    val expectedPrefix = skillCanonical + java.io.File.separator
    if (targetCanonical != skillCanonical && !targetCanonical.startsWith(expectedPrefix)) {
        return null
    }
    return "file://$targetCanonical$query"
}
