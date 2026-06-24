package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.hasAntigravity
import me.rerere.rikkahub.data.datastore.hasChatGpt
import me.rerere.rikkahub.data.datastore.resolveSearchOptions
import me.rerere.common.json.JsonInstantPretty
import me.rerere.common.time.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * The active search service options, with the live Gagy refresh token injected for Google Search
 * (the token is @Transient, never persisted — it must be filled from the current provider each time).
 */
private fun activeSearchOptions(settings: Settings): SearchServiceOptions {
    val selected = settings.searchServices.getOrElse(
        index = settings.searchServiceSelected,
        defaultValue = { SearchServiceOptions.DEFAULT })
    // A managed-login engine left selected after its provider was removed would fail every search
    // with "not configured" — fall back to the default engine so the agent's search tool keeps working.
    if (selected is SearchServiceOptions.GoogleSearchOptions && !settings.hasAntigravity()) {
        return SearchServiceOptions.DEFAULT
    }
    if (selected is SearchServiceOptions.CodexSearchOptions && !settings.hasChatGpt()) {
        return SearchServiceOptions.DEFAULT
    }
    return settings.resolveSearchOptions(selected)
}

fun createSearchTools(settings: Settings): Set<Tool> {
    val options = activeSearchOptions(settings)
    val service = SearchService.getService(options)
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web to discover relevant pages or verify up-to-date/current information.
                    Use this for latest news, current facts, broad discovery, or when you do not already
                    know the exact URL. Do not use this to read a known URL in depth; use fetch or
                    scrape_web for a specific page.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    service.parameters(options)
                },
                execute = {
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a specific URL through the configured search/scraping provider and return
                        detailed page content. Use this when search snippets are insufficient or the user
                        asks about a specific page and this provider exposes scraping. If the ChatGPT/Codex
                        fetch tool is also available, prefer fetch for a user-provided URL that needs a
                        reading/extraction answer; use scrape_web when you need the configured provider's
                        raw scraped content.
                        """.trimIndent(),
                    parameters = {
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}
