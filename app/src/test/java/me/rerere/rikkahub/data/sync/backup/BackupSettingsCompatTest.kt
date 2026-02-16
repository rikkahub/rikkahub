package me.rerere.rikkahub.data.sync.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsCompatTest {
    @Test
    fun normalizeLegacySerializedTypeNames_shouldMapFullQualifiedDotNotation() {
        val raw = """{"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"hello"}"""
        val normalized = normalizeLegacySerializedTypeNames(raw)
        assertTrue(normalized.contains(""""type":"text""""))
    }

    @Test
    fun normalizeLegacySerializedTypeNames_shouldMapFullQualifiedDollarNotation() {
        val raw = """{"type":"me.rerere.ai.ui.UIMessagePart${'$'}ToolResult"}"""
        val normalized = normalizeLegacySerializedTypeNames(raw)
        assertTrue(normalized.contains(""""type":"tool_result""""))
    }

    @Test
    fun normalizeLegacySerializedTypeNames_shouldMapShortAliasText() {
        val raw = """{"type":"Text","text":"hello"}"""
        val normalized = normalizeLegacySerializedTypeNames(raw)
        assertTrue(normalized.contains(""""type":"text""""))
    }

    @Test
    fun normalizeLegacySerializedTypeNames_shouldMapMiddleAliasToolCall() {
        val raw = """{"type":"UIMessagePart.ToolCall"}"""
        val normalized = normalizeLegacySerializedTypeNames(raw)
        assertTrue(normalized.contains(""""type":"tool_call""""))
    }

    @Test
    fun normalizeLegacySerializedTypeNames_shouldMapLegacyMcpType() {
        val raw = """{"type":"me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer"}"""
        val normalized = normalizeLegacySerializedTypeNames(raw)
        assertTrue(normalized.contains(""""type":"sse""""))
    }

    @Test
    fun normalizeLegacySerializedTypeNames_shouldKeepCurrentTypesUntouched() {
        val raw = """{"type":"text","text":"hello"}"""
        val normalized = normalizeLegacySerializedTypeNames(raw)
        assertEquals(raw, normalized)
    }
}
