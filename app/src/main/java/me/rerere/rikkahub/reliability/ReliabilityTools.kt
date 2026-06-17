package me.rerere.rikkahub.reliability

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Phase 14 — Reliability bundle, surfaced as LLM tools so the assistant can drive the
 * checker / bug-reporter from chat without a new Settings page (matches the project's
 * existing chat-driven config pattern).
 *
 * Two tools:
 *   - `check_app_updates` — read-only. Hits GitHub Releases, compares the latest tag
 *     against BuildConfig.VERSION_NAME, returns either "up to date" or details about
 *     the newer release including the html_url for download.
 *   - `generate_bug_report` — side-effecting (writes a file to cache). Builds a redacted
 *     ZIP and returns its path. The user / assistant can then attach it to an email,
 *     telegram_send_document, or share via intent.
 *
 * Both are gated by the per-assistant `Reliability` Local Tools toggle.
 */

fun checkAppUpdatesTool(checker: GitHubReleaseChecker): Tool = Tool(
    name = "check_app_updates",
    description = """
        Check GitHub Releases for a newer version of rikkahub-agent than the one currently
        installed. Read-only. Returns the current version, the latest tag, whether an update
        is available, the release URL, and the release body for context. Use when the user
        asks "any updates?" or to schedule a periodic update reminder via cron.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = {
        val payload = when (val result = checker.check()) {
            is GitHubReleaseChecker.CheckResult.Available -> buildJsonObject {
                put("update_available", true)
                put("current", result.current)
                put("latest", result.latest.tag_name)
                put("name", result.latest.name)
                put("url", result.latest.html_url)
                put("published_at", result.latest.published_at)
                put("body", result.latest.body)
            }
            is GitHubReleaseChecker.CheckResult.UpToDate -> buildJsonObject {
                put("update_available", false)
                put("current", result.current)
                put("latest", result.latest.tag_name)
            }
            is GitHubReleaseChecker.CheckResult.Failed -> buildJsonObject {
                put("error", "check_failed")
                put("detail", result.message)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

fun generateBugReportTool(context: Context, builder: BugReportBuilder): Tool = Tool(
    name = "generate_bug_report",
    description = "Build a bug-report ZIP: last ~5000 lines of secret-redacted logcat + app/device/Android version + a README listing what's excluded (conversations, tokens, hosts, memories all stay private). Returns absolute path + content:// URI for ACTION_SEND share. File lives in app cache.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    needsApproval = { true },
    execute = {
        val zip = builder.build()
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, zip)
        }.getOrNull()
        val payload = buildJsonObject {
            put("ok", true)
            put("path", zip.absolutePath)
            put("size_bytes", zip.length())
            if (uri != null) put("content_uri", uri.toString())
            put("share_intent_action", Intent.ACTION_SEND)
            put("mime_type", "application/zip")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
