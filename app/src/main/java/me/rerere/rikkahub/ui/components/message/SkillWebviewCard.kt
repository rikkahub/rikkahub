package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserActivity

/**
 * Pass 3 (Phase 18B-card): chat-side renderer for `UIMessagePart.Text` parts whose
 * metadata carries a `rikkahub.webview` block. Emitted by JS skills via
 * [me.rerere.rikkahub.skills.js.RunJsTool] when a skill returns a `webview` payload.
 *
 * The shape we look for, written by Phase 20-audit's `runJsTool` change:
 *
 * ```json
 * {
 *   "rikkahub.webview": {
 *     "url": "https://… or file://…",
 *     "iframe": true|false,
 *     "aspect_ratio": 1.6,
 *     "source": "js_skill:<name>"
 *   }
 * }
 * ```
 *
 * v1 of the renderer (this Pass) does NOT inline an iframe — that's the deferred
 * Phase 18B-inline. Instead we draw a small "Open in browser" card. Tapping fires
 * [BrowserActivity.intent] so the same persistent profile + AI tooling the LLM uses for
 * agentic browsing also serves as the skill output viewer. "Browser as the viewer" — exact
 * route the spec asks for: tap to view, scroll to dismiss.
 *
 * The card also surfaces the iframe-vs-direct intent as an overline ("Direct" when the
 * skill explicitly opted out of iframe embedding) so power-users can tell the two apart
 * before tapping. Failing to detect the metadata block returns null — the calling renderer
 * falls back to the standard markdown text rendering.
 */
@Composable
internal fun SkillWebviewCardOrNull(
    part: UIMessagePart.Text,
    modifier: Modifier = Modifier,
): Boolean {
    val webview = remember(part.metadata) { extractWebviewMeta(part.metadata) } ?: return false
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Overline: tells the user whether the skill expected an iframe (embedded) or
            // a direct full-screen open. v1 always opens direct in BrowserActivity; the
            // label is informational.
            Text(
                text = stringResource(
                    if (webview.iframe) R.string.skill_webview_card_embedded
                    else R.string.skill_webview_card_direct
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            // Title: the skill's "Open in a viewer" text (or whatever they returned). The
            // skill author writes this to be human-readable; we keep it as-is.
            Text(
                text = part.text.ifBlank { stringResource(R.string.skill_webview_card_default_title) },
                style = MaterialTheme.typography.titleMedium,
            )
            // URL preview line — gives the user the destination before the tap.
            Text(
                text = webview.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = {
                    // Routes through the in-app browser so the persistent profile, tool
                    // toggles, and HARDLINE all apply uniformly. Falls back to about:blank
                    // if the URL was somehow lost between metadata write + render.
                    runCatching {
                        context.startActivity(BrowserActivity.intent(context, webview.url))
                    }
                }) {
                    Text(stringResource(R.string.skill_webview_card_open))
                }
            }
        }
    }
    return true
}

/** Compact value type for the webview metadata block. */
private data class WebviewMeta(
    val url: String,
    val iframe: Boolean,
    val source: String?,
)

/**
 * Pull a [WebviewMeta] out of the part's metadata. Returns null if the metadata is null,
 * doesn't have the `rikkahub.webview` key, or has a malformed `url`. Defensive parsing —
 * any unexpected shape falls back to null and the standard markdown renderer takes over.
 */
private fun extractWebviewMeta(metadata: JsonObject?): WebviewMeta? {
    val webview = metadata?.get("rikkahub.webview")?.jsonObject ?: return null
    val url = webview["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val iframe = webview["iframe"]?.jsonPrimitive?.booleanOrNull ?: true
    val source = webview["source"]?.jsonPrimitive?.contentOrNull
    return WebviewMeta(url = url, iframe = iframe, source = source)
}
