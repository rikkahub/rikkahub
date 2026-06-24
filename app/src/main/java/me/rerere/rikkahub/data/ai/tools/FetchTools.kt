package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.codexFetch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.chatGptAccessToken

/**
 * The `fetch` agent tool: open and read a single URL via the ChatGPT (Codex) backend's hosted
 * web_search/open_page (a server-side fetch — no local network egress). Deferred: only added to the
 * toolset when a ChatGPT provider is configured (see ChatService baseTools), so it never appears for
 * users without ChatGPT. Returns the model's reading of the page (extract/summarize), not raw HTML.
 */
fun createFetchTools(settings: Settings): List<Tool> {
    val accessToken = settings.chatGptAccessToken() ?: return emptyList()
    return listOf(
        Tool(
            name = "fetch",
            description = """
                Fetch and read one specific absolute URL through the ChatGPT/Codex hosted web reader.
                Use this when the user gives a URL, or after search_web finds a URL whose page content you
                need to answer from. Do not use this for broad discovery; use search_web first when you do
                not know the URL. Optionally describe what to extract.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "The absolute http/https URL to open and read.")
                        })
                        put("instruction", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional: what to extract or do with the page (e.g. 'summarize', 'list the prices')."
                            )
                        })
                    },
                    required = listOf("url"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: error("url is required")
                val instruction = obj["instruction"]?.jsonPrimitive?.content.orEmpty()
                val result = codexFetch(accessToken = accessToken, url = url, instruction = instruction)
                val opened = if (result.opened.isNotEmpty()) {
                    "\n\nOpened: " + result.opened.joinToString(", ")
                } else {
                    ""
                }
                listOf(UIMessagePart.Text(result.answer + opened))
            }
        )
    )
}
