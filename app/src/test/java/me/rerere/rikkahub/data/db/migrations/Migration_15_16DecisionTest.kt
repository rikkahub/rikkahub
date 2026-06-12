package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration_15_16DecisionTest {

    // The test intentionally builds legacy ToolCall payloads: the 15->16 migration's whole
    // job is to rewrite that deprecated wire format, so its inputs must use it.
    @Suppress("DEPRECATION")
    private fun assistantNodeWithToolCall(): MigrationNodeRow = MigrationNodeRow(
        id = "assistant-node",
        messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ToolCall("call1", "tool1", "{}"))
            )
        ),
        selectIndex = 0
    )

    // Same as above: deprecated ToolResult is the legacy wire format under migration.
    @Suppress("DEPRECATION")
    private fun toolNode(): MigrationNodeRow = MigrationNodeRow(
        id = "tool-node",
        messages = listOf(
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        "call1", "tool1", JsonPrimitive("result1"), JsonPrimitive("{}")
                    )
                )
            )
        ),
        selectIndex = 0
    )

    /**
     * Regression for issue #9: a conversation containing one undecodable node (messages == null)
     * alongside a sibling node that the tool migration WOULD rewrite must be Skipped entirely,
     * so the undecodable node is never destroyed.
     *
     * On the unfixed logic the undecodable row was dropped before computing `changed`; the sibling
     * tool migration made `changed == true`, triggering DELETE-all + reinsert-only-decoded, which
     * permanently deleted the undecodable node. The fixed logic returns Skip.
     */
    @Test
    fun `conversation with an undecodable node and a migratable sibling is skipped`() {
        val undecodable = MigrationNodeRow(id = "broken-node", messages = null, selectIndex = 0)
        val rows = listOf(undecodable, assistantNodeWithToolCall(), toolNode())

        val decision = decideMigration15To16(rows)

        assertEquals(
            "any undecodable node must preserve the whole conversation untouched",
            MigrationDecision.Skip,
            decision
        )
    }

    @Test
    fun `conversation with all nodes parsing and a tool node is rewritten`() {
        val rows = listOf(assistantNodeWithToolCall(), toolNode())

        val decision = decideMigration15To16(rows)

        assertTrue(
            "fully-decodable conversation with a TOOL node should be rewritten",
            decision is MigrationDecision.Rewrite
        )
        decision as MigrationDecision.Rewrite
        // TOOL node merged into the ASSISTANT node => one fewer row.
        assertEquals(1, decision.rows.size)
        assertEquals("assistant-node", decision.rows[0].id)
    }

    @Test
    fun `conversation with all nodes parsing but nothing tool-related is skipped`() {
        val plainNode = MigrationNodeRow(
            id = "plain-node",
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("hello"))
                )
            ),
            selectIndex = 0
        )

        val decision = decideMigration15To16(listOf(plainNode))

        assertEquals(MigrationDecision.Skip, decision)
    }

    @Test
    fun `conversation with a single undecodable node and no other changes is skipped`() {
        val undecodable = MigrationNodeRow(id = "broken-node", messages = null, selectIndex = 0)

        val decision = decideMigration15To16(listOf(undecodable))

        assertEquals(MigrationDecision.Skip, decision)
    }

    @Test
    fun `empty conversation is skipped`() {
        assertEquals(MigrationDecision.Skip, decideMigration15To16(emptyList()))
    }
}
