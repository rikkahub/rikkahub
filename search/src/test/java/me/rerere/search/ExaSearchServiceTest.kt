package me.rerere.search

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaSearchServiceTest {
    @Test
    fun `search mapping preserves publication date and highlights`() {
        val result = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult(
                        id = "result-1",
                        title = "Official release",
                        url = "https://example.com/release",
                        publishedDate = "2026-08-31T06:26:20Z",
                        highlights = listOf("Current release evidence"),
                        text = "Release text",
                    )
                )
            )
        )

        assertEquals("2026-08-31T06:26:20Z", result.items.single().publishedDate)
        assertEquals(listOf("Current release evidence"), result.items.single().highlights)
        assertEquals("https://example.com/release", result.items.single().url)
    }

    @Test
    fun `missing publication date decodes as null`() {
        val data = SearchService.json.decodeFromString<ExaSearchService.ExaData>(
            """
            {
              "results": [{
                "id": "result-1",
                "title": "Undated result",
                "url": "https://example.com/undated"
              }]
            }
            """.trimIndent()
        )

        assertNull(ExaSearchService.mapSearchResult(data).items.single().publishedDate)
    }

    @Test
    fun `current query adds conservative freshness and official domain defaults`() {
        val params = buildJsonObject {
            put("query", "What is the current stable RikkaHub version?")
        }

        val prepared = ExaSearchService.prepareCurrentInfoParams(params)

        assertEquals("github.com", prepared["includeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(0, prepared["maxAgeHours"]!!.jsonPrimitive.int)
        assertEquals("current query must preserve the user's exact wording", params["query"], prepared["query"])
    }

    @Test
    fun `current query chooses known provider domains without overriding explicit filters`() {
        val scaleway = ExaSearchService.prepareCurrentInfoParams(
            buildJsonObject { put("query", "Is qwen currently available at Scaleway?") }
        )
        val berget = ExaSearchService.prepareCurrentInfoParams(
            buildJsonObject { put("query", "Is Kimi K3 currently available through Berget or Opper?") }
        )
        val explicit = ExaSearchService.prepareCurrentInfoParams(
            buildJsonObject {
                put("query", "What is the current RikkaHub version?")
                put("includeDomains", buildJsonArray { add(JsonPrimitive("example.com")) })
                put("maxAgeHours", 12)
            }
        )

        assertEquals("scaleway.com", scaleway["includeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            listOf("berget.ai", "opper.ai"),
            berget["includeDomains"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("example.com", explicit["includeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(12, explicit["maxAgeHours"]!!.jsonPrimitive.int)
    }

    @Test
    fun `ordinary query keeps legacy parameters unchanged`() {
        val params = buildJsonObject {
            put("query", "How does version control work?")
        }

        assertEquals(params, ExaSearchService.prepareCurrentInfoParams(params))
    }

    @Test
    fun `explicit empty or invalid freshness options are not overwritten`() {
        val params = buildJsonObject {
            put("query", "What is the current RikkaHub version?")
            put("includeDomains", buildJsonArray {})
            put("maxAgeHours", JsonPrimitive("invalid"))
        }

        assertEquals(params, ExaSearchService.prepareCurrentInfoParams(params))
    }

    @Test
    fun `current result ordering prefers newest dated evidence and leaves undated last`() {
        val result = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult("old", "Old", "https://example.com/old", "2026-01-01T00:00:00Z"),
                    ExaSearchService.ExaResult("undated", "Undated", "https://example.com/undated"),
                    ExaSearchService.ExaResult("new", "New", "https://example.com/new", "2026-09-01T00:00:00Z"),
                )
            )
        )

        val prioritized = ExaSearchService.prioritizeCurrentResults(result)

        assertEquals(listOf("new", "old", "undated"), prioritized.items.map { it.url.substringAfterLast('/') })
    }

    @Test
    fun `normal search request keeps legacy contents shape`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
            },
            resultSize = 10,
        )

        assertEquals(true, body["contents"]!!.jsonObject["text"]!!.jsonPrimitive.boolean)
        assertTrue("startPublishedDate should be absent", "startPublishedDate" !in body)
        assertTrue("includeDomains should be absent", "includeDomains" !in body)
    }

    @Test
    fun `evidence request serializes dates domains and content freshness`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "latest release")
                put("startPublishedDate", "2026-08-01T00:00:00Z")
                put("endPublishedDate", "2026-09-03T23:59:59Z")
                put("includeDomains", buildJsonArray { add(JsonPrimitive("example.com")) })
                put("excludeDomains", buildJsonArray { add(JsonPrimitive("old.example.com")) })
                put("maxAgeHours", 1)
            },
            resultSize = 10,
        )

        assertEquals("2026-08-01T00:00:00Z", body["startPublishedDate"]!!.jsonPrimitive.content)
        assertEquals("2026-09-03T23:59:59Z", body["endPublishedDate"]!!.jsonPrimitive.content)
        assertEquals("example.com", body["includeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("old.example.com", body["excludeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, body["contents"]!!.jsonObject["maxAgeHours"]!!.jsonPrimitive.int)
        assertEquals(
            8_000,
            body["contents"]!!.jsonObject["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertEquals(
            1_200,
            body["contents"]!!.jsonObject["highlights"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )

        val schema = ExaSearchService.parameters(SearchServiceOptions.ExaOptions()) as InputSchema.Obj
        assertEquals("integer", schema.properties["maxAgeHours"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            schema.properties["startPublishedDate"]!!.jsonObject["description"]!!.jsonPrimitive.content
                .contains("after this date")
        )
        assertTrue(
            schema.properties["endPublishedDate"]!!.jsonObject["description"]!!.jsonPrimitive.content
                .contains("before this date")
        )
        assertEquals(-1, schema.properties["maxAgeHours"]!!.jsonObject["minimum"]!!.jsonPrimitive.int)
        assertEquals(720, schema.properties["maxAgeHours"]!!.jsonObject["maximum"]!!.jsonPrimitive.int)
    }

    @Test
    fun `empty and null optional values keep legacy request shape`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
                put("startPublishedDate", "")
                put("endPublishedDate", JsonNull)
                put("includeDomains", buildJsonArray { add(JsonPrimitive("")) })
                put("excludeDomains", buildJsonArray {})
                put("maxAgeHours", JsonPrimitive("not-an-integer"))
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        assertEquals(true, contents["text"]!!.jsonPrimitive.boolean)
        assertTrue("empty start date should be absent", "startPublishedDate" !in body)
        assertTrue("null end date should be absent", "endPublishedDate" !in body)
        assertTrue("empty include domains should be absent", "includeDomains" !in body)
        assertTrue("empty exclude domains should be absent", "excludeDomains" !in body)
        assertTrue("invalid max age should be absent", "maxAgeHours" !in contents)
        assertTrue("legacy request should not request highlights", "highlights" !in contents)
    }

    @Test
    fun `out of range max age is omitted`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "latest release")
                put("maxAgeHours", 721)
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        assertTrue("out of range max age should be absent", "maxAgeHours" !in contents)
        assertEquals(true, contents["text"]!!.jsonPrimitive.boolean)
        assertTrue("out of range max age should not enable evidence mode", "highlights" !in contents)
    }

    @Test
    fun `scrape request supports content freshness and bounded text`() {
        val body = ExaSearchService.buildScrapeRequestBody(
            buildJsonObject {
                put("url", "https://example.com/release")
                put("maxAgeHours", 0)
            }
        )

        assertEquals(0, body["maxAgeHours"]!!.jsonPrimitive.int)
        assertEquals(
            8_000,
            body["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
    }
}
