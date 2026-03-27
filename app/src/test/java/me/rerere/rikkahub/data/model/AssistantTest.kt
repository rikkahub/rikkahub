package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantTest {
    @Test
    fun `conversation starters should use alternate greetings when available`() {
        val assistant = Assistant(
            presetMessages = listOf(
                UIMessage.system("Prelude"),
                UIMessage.assistant("Original greeting"),
            ),
            stCharacterData = SillyTavernCharacterData(
                alternateGreetings = listOf("Alternate greeting"),
            ),
        )

        val result = assistant.resolveConversationStarterMessages()

        assertEquals(listOf("Prelude", "Alternate greeting"), result.map { it.toText() })
    }
}
