package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantRegexTest {

    @Test
    fun `prompt only regex should only apply in prompt phase`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.USER),
                    promptOnly = true,
                )
            )
        )

        val source = "foo"
        assertEquals(
            "foo",
            source.replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE
            )
        )
        assertEquals(
            "foo",
            source.replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.VISUAL_ONLY
            )
        )
        assertEquals(
            "bar",
            source.replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.PROMPT_ONLY
            )
        )
    }

    @Test
    fun `depth range should filter regex by message depth`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "secret",
                    replaceString = "[masked]",
                    affectingScope = setOf(AssistantAffectScope.USER),
                    promptOnly = true,
                    minDepth = 3,
                    maxDepth = 5,
                )
            )
        )

        fun apply(depth: Int): String = "secret".replaceRegexes(
            assistant = assistant,
            scope = AssistantAffectScope.USER,
            phase = AssistantRegexApplyPhase.PROMPT_ONLY,
            messageDepthFromEnd = depth
        )

        assertEquals("secret", apply(2))
        assertEquals("[masked]", apply(3))
        assertEquals("[masked]", apply(5))
        assertEquals("secret", apply(6))
    }

    @Test
    fun `chat depth map should ignore non chat roles`() {
        val messages = listOf(
            UIMessage.system("system"),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
        )

        val depthMap = messages.chatMessageDepthFromEndMap()

        assertEquals(null, depthMap[0])
        assertEquals(3, depthMap[1])
        assertEquals(2, depthMap[2])
        assertEquals(1, depthMap[3])
    }
}
