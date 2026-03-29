package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SillyTavernCompatScriptTransformerTest {
    @Test
    fun `projectCompatMessages should flatten plain text messages`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("user"),
            UIMessage.assistant("assistant"),
        )

        val projected = projectCompatMessages(messages)

        assertEquals(
            listOf(
                StCompatMessage(role = "system", content = "sys"),
                StCompatMessage(role = "user", content = "user"),
                StCompatMessage(role = "assistant", content = "assistant"),
            ),
            projected,
        )
    }

    @Test
    fun `projectCompatMessages should skip non text messages`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image(url = "file:///tmp/demo.png"))
            )
        )

        val projected = projectCompatMessages(messages)

        assertNull(projected)
    }

    @Test
    fun `projectCompatMessages should allow document messages once text has been injected`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("## user sent a file: notes.js\n```js\nconsole.log(1)\n```"),
                    UIMessagePart.Document(
                        url = "file:///tmp/notes.js",
                        fileName = "notes.js",
                        mime = "application/javascript",
                    )
                )
            )
        )

        val projected = projectCompatMessages(messages)

        assertEquals(
            listOf(
                StCompatMessage(
                    role = "user",
                    content = "## user sent a file: notes.js\n```js\nconsole.log(1)\n```",
                )
            ),
            projected,
        )
    }

    @Test
    fun `projectCompatMessages should skip document only messages`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Document(
                        url = "file:///tmp/notes.js",
                        fileName = "notes.js",
                        mime = "application/javascript",
                    )
                )
            )
        )

        val projected = projectCompatMessages(messages)

        assertNull(projected)
    }

    @Test
    fun `projectCompatMessages should ignore reasoning parts when flattening text`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("thinking"),
                    UIMessagePart.Text("final answer"),
                )
            )
        )

        val projected = projectCompatMessages(messages)

        assertEquals(
            listOf(
                StCompatMessage(
                    role = "assistant",
                    content = "final answer",
                )
            ),
            projected,
        )
    }

    @Test
    fun `projectCompatMessages should skip tool messages`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "tool-1",
                        toolName = "demo",
                        input = "{}",
                    )
                )
            )
        )

        val projected = projectCompatMessages(messages)

        assertNull(projected)
    }

    @Test
    fun `applyCompatMessages should rebuild UI messages`() {
        val rebuilt = applyCompatMessages(
            listOf(
                StCompatMessage(role = "system", content = "sys"),
                StCompatMessage(role = "user", content = "user"),
                StCompatMessage(role = "assistant", content = "assistant"),
                StCompatMessage(role = "tool", content = "{\"ok\":true}"),
            )
        )

        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL), rebuilt.map { it.role })
        assertEquals(listOf("sys", "user", "assistant", "{\"ok\":true}"), rebuilt.map { it.toText() })
    }

    @Test
    fun `toCompatApi should expose ST style main api for chat completion providers`() {
        assertEquals(
            StCompatApiContext(
                mainApi = "openai",
                originalMainApi = "openai",
                chatCompletionSource = "openai",
            ),
            ProviderSetting.OpenAI().toCompatApi()
        )
        assertEquals(
            StCompatApiContext(
                mainApi = "openai",
                originalMainApi = "google",
                chatCompletionSource = "makersuite",
            ),
            ProviderSetting.Google(vertexAI = false).toCompatApi()
        )
        assertEquals(
            StCompatApiContext(
                mainApi = "openai",
                originalMainApi = "google",
                chatCompletionSource = "vertexai",
            ),
            ProviderSetting.Google(vertexAI = true).toCompatApi()
        )
        assertEquals(
            StCompatApiContext(
                mainApi = "openai",
                originalMainApi = "claude",
                chatCompletionSource = "claude",
            ),
            ProviderSetting.Claude().toCompatApi()
        )
    }

    @Test
    fun `assistant should serialize compat extension settings`() {
        val assistant = Assistant(
            stCompatScriptEnabled = true,
            stCompatScriptSource = "script.eventSource.on('x', () => {})",
            stCompatExtensionSettings = buildJsonObject {
                put(
                    "SillyTavernExtension-mergeEditor",
                    buildJsonObject {
                        put("capture_enabled", true)
                    }
                )
            }
        )

        val encoded = Json.encodeToString(Assistant.serializer(), assistant)
        val decoded = Json.decodeFromString(Assistant.serializer(), encoded)

        assertEquals(assistant.stCompatScriptEnabled, decoded.stCompatScriptEnabled)
        assertEquals(assistant.stCompatScriptSource, decoded.stCompatScriptSource)
        assertEquals(assistant.stCompatExtensionSettings, decoded.stCompatExtensionSettings)
    }
}
