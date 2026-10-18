package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    private fun editing(parts: List<UIMessagePart>) = ChatInputState().apply {
        editingMessage = Uuid.random()
        setContents(parts)
    }

    @Test
    fun `reasoning and text message allows editing reasoning in place`() {
        val reasoning = UIMessagePart.Reasoning("old thought")
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNotNull(state.editingReasoning)
        assertEquals(emptyList<UIMessagePart>(), state.messageContent)

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("new thought")
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(
            listOf(reasoning.copy(reasoning = "new thought"), UIMessagePart.Text("new answer")),
            state.getContents(),
        )
    }

    @Test
    fun `blank reasoning is dropped`() {
        val state = editing(listOf(UIMessagePart.Reasoning("old thought"), UIMessagePart.Text("answer")))

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("  ")

        assertEquals(listOf(UIMessagePart.Text("answer")), state.getContents())
    }

    @Test
    fun `signed reasoning is not editable and kept as is`() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thought",
            metadata = buildJsonObject { put("signature", JsonPrimitive("sig")) },
        )
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNull(state.editingReasoning)
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(listOf(reasoning, UIMessagePart.Text("new answer")), state.getContents())
    }

    @Test
    fun `message with other parts is not editable`() {
        val state = editing(
            listOf(
                UIMessagePart.Reasoning("thought"),
                UIMessagePart.Text("answer"),
                UIMessagePart.Image("file:///image.png"),
            )
        )

        assertNull(state.editingReasoning)
    }

    @Test
    fun `clear input resets reasoning editing`() {
        val state = editing(listOf(UIMessagePart.Reasoning("thought"), UIMessagePart.Text("answer")))

        state.clearInput()

        assertNull(state.editingReasoning)
    }
}
