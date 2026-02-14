package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.uuid.Uuid

private const val MAX_TOOL_OUTPUT_CHARS = 140_000

private fun limitToolOutput(text: String): String {
    return if (text.length <= MAX_TOOL_OUTPUT_CHARS) {
        text
    } else {
        text.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n...[truncated]"
    }
}

private fun buildToolFailurePayload(
    error: Throwable,
    unavailableCode: String,
    unavailableMessage: String
): JsonObject {
    val msg = error.message.orEmpty()
    val isNoResults = msg.contains("no results", ignoreCase = true)
    return buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error_code", JsonPrimitive(if (isNoResults) "NO_RESULTS" else unavailableCode))
        put(
            "message",
            JsonPrimitive(if (isNoResults) "no results from current sources" else unavailableMessage)
        )
    }
}

fun createSearchTools(context: Context, settings: Settings): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
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
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        context = context,
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val payload = result.fold(
                        onSuccess = { success ->
                            JsonInstantPretty.encodeToJsonElement(success).jsonObject.let { json ->
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
                        },
                        onFailure = {
                            buildToolFailurePayload(
                                error = it,
                                unavailableCode = "SEARCH_UNAVAILABLE",
                                unavailableMessage = "search temporarily unavailable"
                            )
                        }
                    )
                    listOf(UIMessagePart.Text(limitToolOutput(payload.toString())))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            context = context,
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = result.fold(
                            onSuccess = { JsonInstantPretty.encodeToJsonElement(it).jsonObject },
                            onFailure = {
                                buildToolFailurePayload(
                                    error = it,
                                    unavailableCode = "SCRAPE_UNAVAILABLE",
                                    unavailableMessage = "scrape temporarily unavailable"
                                )
                            }
                        )
                        listOf(UIMessagePart.Text(limitToolOutput(payload.toString())))
                    }
                ))
        }
    }
}
