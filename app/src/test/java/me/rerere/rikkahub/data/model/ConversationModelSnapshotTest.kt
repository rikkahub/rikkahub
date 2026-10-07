package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationModelSnapshotTest {
    private val model = Model(modelId = "model-a", displayName = "Model A")
    private val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))

    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    private fun assistantMessage(modelId: Uuid?, snapshot: ModelSnapshot? = null) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        modelId = modelId,
        modelSnapshot = snapshot,
    )

    @Test
    fun `messages of an existing model get a snapshot`() {
        val filled = conversation(UIMessage.user("hi"), assistantMessage(model.id)).fillModelSnapshots(settings)

        assertNull(filled.currentMessages[0].modelSnapshot)
        assertEquals(ModelSnapshot("model-a", "Model A"), filled.currentMessages[1].modelSnapshot)
    }

    @Test
    fun `snapshot keeps the message readable after the model is deleted`() {
        val filled = conversation(assistantMessage(model.id)).fillModelSnapshots(settings)
        val afterDeletion = filled.fillModelSnapshots(Settings(providers = emptyList()))

        assertSame(filled, afterDeletion)
        val shown = afterDeletion.currentMessages.single().snapshotModel()
        assertEquals(model.id, shown?.id)
        assertEquals("model-a", shown?.modelId)
        assertEquals("Model A", shown?.displayName)
    }

    @Test
    fun `existing snapshot is not overwritten by a renamed model`() {
        val snapshot = ModelSnapshot("model-a", "Old Name")
        val conversation = conversation(assistantMessage(model.id, snapshot))

        assertSame(conversation, conversation.fillModelSnapshots(settings))
    }

    @Test
    fun `messages of an already deleted model are left untouched`() {
        val conversation = conversation(assistantMessage(Uuid.random()))

        assertSame(conversation, conversation.fillModelSnapshots(settings))
    }
}
