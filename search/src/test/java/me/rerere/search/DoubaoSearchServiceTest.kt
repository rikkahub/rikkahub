package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubaoSearchServiceTest {
    @Test
    fun parseGlobalResponse() {
        val response = DoubaoSearchService.parseResponse(
            DoubaoSearchMode.GLOBAL,
            """
            {
              "ResponseMetadata": {"RequestId": "request-id"},
              "Result": {
                "TotalDocCount": 1,
                "Documents": [{
                  "Url": "https://example.com/global",
                  "Title": "Global result",
                  "Snippet": [
                    {"Type": "text", "Text": "First paragraph"},
                    {"Type": "image", "Image": {"ImageUrl": "https://example.com/image.jpg"}},
                    {"Type": "text", "Text": "Second paragraph"}
                  ]
                }],
                "ErrorCode": 0,
                "ErrorMsg": ""
              }
            }
            """.trimIndent()
        )

        assertEquals(1, response.items.size)
        assertEquals("Global result", response.items.single().title)
        assertEquals("First paragraph\nSecond paragraph", response.items.single().text)
        assertEquals(listOf("https://example.com/image.jpg"), response.images)
    }

    @Test
    fun parseCustomResponsePrefersSummary() {
        val response = DoubaoSearchService.parseResponse(
            DoubaoSearchMode.CUSTOM,
            """
            {
              "ResponseMetadata": {"RequestId": "request-id"},
              "Result": {
                "ResultCount": 1,
                "WebResults": [{
                  "Title": "Custom result",
                  "Url": "https://example.com/custom",
                  "Snippet": "Snippet",
                  "Summary": "Summary"
                }]
              }
            }
            """.trimIndent()
        )

        assertEquals(1, response.items.size)
        assertEquals("Custom result", response.items.single().title)
        assertEquals("Summary", response.items.single().text)
    }
}
