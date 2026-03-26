package me.rerere.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LorebookSerializerTest {
    @Test
    fun `standalone sillytavern lorebook import should preserve regex and extended metadata`() {
        val lorebook = LorebookSerializer.tryImportSillyTavern(
            json = """
                {
                  "recursive_scanning": true,
                  "token_budget": 128,
                  "entries": {
                    "0": {
                      "uid": 7,
                      "key": ["hero"],
                      "keysecondary": ["/forest/i"],
                      "content": "Standalone content",
                      "comment": "Standalone Entry",
                      "position": 4,
                      "order": 200,
                      "group": "facts",
                      "groupOverride": true,
                      "groupWeight": 75,
                      "useGroupScoring": true,
                      "useRegex": true,
                      "probability": 40,
                      "useProbability": false,
                      "depth": 2,
                      "role": 2,
                      "triggers": ["continue"],
                      "ignoreBudget": true,
                      "outletName": "memory"
                    }
                  }
                }
            """.trimIndent(),
            fileName = "Standalone",
        )

        assertNotNull(lorebook)
        val parsed = lorebook!!
        val entry = parsed.entries.single()
        assertEquals(true, parsed.recursiveScanning)
        assertEquals(128, parsed.tokenBudget)
        assertEquals(true, entry.useRegex)
        assertNull(entry.probability)
        assertEquals("facts", entry.stMetadata["group"])
        assertEquals("true", entry.stMetadata["group_override"])
        assertEquals("75", entry.stMetadata["group_weight"])
        assertEquals("true", entry.stMetadata["use_group_scoring"])
        assertEquals("[continue]", entry.stMetadata["triggers"])
        assertEquals("true", entry.stMetadata["ignore_budget"])
        assertEquals("memory", entry.stMetadata["outlet_name"])
        assertEquals("false", entry.stMetadata["useProbability"])
    }
}
