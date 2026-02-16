package me.rerere.rikkahub.data.sync.backup

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar

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

    @Test
    fun rewriteLegacyUploadUri_shouldRewriteLegacyAbsolutePathToCurrentFilesDir() {
        val filesDir = Files.createTempDirectory("rikkahub-files").toFile()
        val legacy = "file:///data/user/0/me.rerere.rikkahub/files/upload/demo.png"

        val rewritten = rewriteLegacyUploadUri(legacy, filesDir)

        val expected = filesDir.toPath().resolve("upload/demo.png").toUri().toString()
        assertEquals(expected, rewritten)
    }

    @Test
    fun rewriteLegacyUploadUri_shouldIgnoreNonUploadOrNonFileUris() {
        val filesDir = Files.createTempDirectory("rikkahub-files").toFile()
        val httpUrl = "https://example.com/demo.png"
        val otherLocal = "file:///data/user/0/me.rerere.rikkahub/files/images/demo.png"

        assertEquals(httpUrl, rewriteLegacyUploadUri(httpUrl, filesDir))
        assertEquals(otherLocal, rewriteLegacyUploadUri(otherLocal, filesDir))
    }

    @Test
    fun rewriteLegacyUploadUrisInSettings_shouldRewriteAvatarBackgroundAndPresetMessageParts() {
        val filesDir = Files.createTempDirectory("rikkahub-files").toFile()
        val legacy = "file:///data/user/0/me.rerere.rikkahub/files/upload/demo.png"
        val settings = Settings(
            displaySetting = DisplaySetting(userAvatar = Avatar.Image(legacy)),
            assistants = listOf(
                Assistant(
                    name = "A",
                    avatar = Avatar.Image(legacy),
                    background = legacy,
                    presetMessages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Image(legacy))
                        )
                    )
                )
            )
        )

        val rewritten = rewriteLegacyUploadUrisInSettings(settings, filesDir)
        val expected = filesDir.toPath().resolve("upload/demo.png").toUri().toString()

        assertEquals(expected, (rewritten.displaySetting.userAvatar as Avatar.Image).url)
        assertEquals(expected, (rewritten.assistants.first().avatar as Avatar.Image).url)
        assertEquals(expected, rewritten.assistants.first().background)
        val part = rewritten.assistants.first().presetMessages.first().parts.first() as UIMessagePart.Image
        assertEquals(expected, part.url)
    }

    @Test
    fun collectAndClearBrokenSettingsMediaRefs_shouldOnlyAffectSettingsAvatarAndBackground() {
        val filesDir = Files.createTempDirectory("rikkahub-files").toFile()
        val uploadDir = filesDir.toPath().resolve("upload")
        Files.createDirectories(uploadDir)
        val existingPath = uploadDir.resolve("ok.png")
        Files.write(existingPath, byteArrayOf(1, 2, 3))

        val missingUrl = "file:///data/user/0/me.rerere.rikkahub/files/upload/missing.png"
        val existingUrl = "file:///data/user/0/me.rerere.rikkahub/files/upload/ok.png"
        val settings = Settings(
            displaySetting = DisplaySetting(userAvatar = Avatar.Image(missingUrl)),
            assistants = listOf(
                Assistant(
                    name = "A",
                    avatar = Avatar.Image(existingUrl),
                    background = missingUrl,
                )
            )
        )

        val brokenRefs = collectBrokenSettingsMediaRefs(settings, filesDir)
        assertEquals(2, brokenRefs.size)

        val cleaned = clearBrokenSettingsMediaRefs(settings, filesDir)
        assertTrue(cleaned.displaySetting.userAvatar is Avatar.Dummy)
        assertEquals(existingUrl, (cleaned.assistants.first().avatar as Avatar.Image).url)
        assertEquals(null, cleaned.assistants.first().background)
    }
}
