package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernLorebookParseTest {

    private fun parseLorebooks(jsonString: String, assistantName: String = "Test"): List<me.rerere.rikkahub.data.model.Lorebook> {
        val json = Json.parseToJsonElement(jsonString).jsonObject
        return parseLorebooksFromTavernCard(json = json, assistantName = assistantName)
    }

    @Test
    fun `character_book entries as object map should be imported`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "character_book": {
                  "name": "Alice WB",
                  "entries": {
                    "1": {
                      "key": ["foo", "bar"],
                      "content": "hello",
                      "comment": "c1",
                      "constant": true,
                      "position": 1,
                      "order": 42,
                      "disable": false,
                      "depth": 2,
                      "scanDepth": 3,
                      "caseSensitive": true
                    }
                  }
                }
              }
            }
            """.trimIndent(),
            assistantName = "Alice"
        )

        assertEquals(1, lorebooks.size)
        val book = lorebooks[0]
        assertEquals("Alice WB", book.name)
        assertEquals(1, book.entries.size)

        val entry = book.entries[0]
        assertEquals("c1", entry.name)
        assertEquals(listOf("foo", "bar"), entry.keywords)
        assertEquals("hello", entry.content)
        assertTrue(entry.enabled)
        assertTrue(entry.constantActive)
        assertEquals(42, entry.priority)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, entry.position)
        assertEquals(2, entry.injectDepth)
        assertEquals(3, entry.scanDepth)
        assertTrue(entry.caseSensitive)
        assertFalse(entry.useRegex)
    }

    @Test
    fun `character_book entries as array should be imported`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "character_book": {
                  "entries": [
                    {
                      "keys": "trigger",
                      "content": "x",
                      "position": 0,
                      "order": 1
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
            assistantName = "Alice"
        )

        assertEquals(1, lorebooks.size)
        val book = lorebooks[0]
        assertEquals("Alice Lorebook", book.name)
        assertEquals(1, book.entries.size)

        val entry = book.entries[0]
        assertEquals("trigger", entry.name)
        assertEquals(listOf("trigger"), entry.keywords)
        assertEquals("x", entry.content)
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, entry.position)
        assertEquals(1, entry.priority)
    }

    @Test
    fun `compat fields should be accepted`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "character_book": {
                  "entries": [
                    {
                      "key": "abc",
                      "scan_depth": 5,
                      "case_sensitive": true,
                      "enabled": false
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, lorebooks.size)
        val entry = lorebooks[0].entries.single()
        assertEquals(listOf("abc"), entry.keywords)
        assertEquals(5, entry.scanDepth)
        assertTrue(entry.caseSensitive)
        assertFalse(entry.enabled)
    }

    @Test
    fun `missing worldbook config should return empty list`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "name": "Alice"
              }
            }
            """.trimIndent()
        )

        assertTrue(lorebooks.isEmpty())
    }

    @Test
    fun `disable should take precedence over enabled`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "character_book": {
                  "entries": [
                    {
                      "key": "a",
                      "disable": true,
                      "enabled": true
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, lorebooks.size)
        val entry = lorebooks[0].entries.single()
        assertFalse(entry.enabled)
    }

    @Test
    fun `insertion_order should be used as priority fallback`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "character_book": {
                  "entries": [
                    {
                      "keys": ["k"],
                      "content": "x",
                      "insertion_order": 777
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, lorebooks.size)
        val entry = lorebooks[0].entries.single()
        assertEquals(777, entry.priority)
    }

    @Test
    fun `extensions fields should be used as fallback`() {
        val lorebooks = parseLorebooks(
            jsonString = """
            {
              "data": {
                "character_book": {
                  "entries": [
                    {
                      "keys": ["k"],
                      "content": "x",
                      "position": "before_char",
                      "extensions": {
                        "depth": 2,
                        "scan_depth": 9,
                        "case_sensitive": true
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, lorebooks.size)
        val entry = lorebooks[0].entries.single()
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, entry.position)
        assertEquals(2, entry.injectDepth)
        assertEquals(9, entry.scanDepth)
        assertTrue(entry.caseSensitive)
    }
}
