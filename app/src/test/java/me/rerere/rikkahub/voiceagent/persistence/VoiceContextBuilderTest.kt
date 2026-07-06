package me.rerere.rikkahub.voiceagent.persistence

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_RESULT_ANNOUNCED_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_SOURCE_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_STATUS_KEY
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.LocalDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceContextBuilderTest {
    @Test
    fun `build keeps the last twenty text turns in order`() {
        val conversation = conversationWith(
            (1..25).map { index -> UIMessage.user("message $index") }
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Answer briefly.",
            conversation = conversation,
        )

        assertEquals(
            expectedVoiceSystemInstruction("Answer briefly."),
            context.systemInstruction,
        )
        assertEquals(20, context.turns.size)
        assertEquals("message 6", context.turns.first().text)
        assertEquals("message 25", context.turns.last().text)
    }

    @Test
    fun `build preserves compressed summary plus recent turns`() {
        val summary = UIMessage.user("[Summary of previous conversation] The user prefers concise Hermes updates.")
        val conversation = conversationWith(
            listOf(summary) + (1..25).map { index -> UIMessage.user("message $index") }
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Answer briefly.",
            conversation = conversation,
        )

        assertEquals(20, context.turns.size)
        assertEquals("[Summary of previous conversation] The user prefers concise Hermes updates.", context.turns.first().text)
        assertEquals("message 7", context.turns[1].text)
        assertEquals("message 25", context.turns.last().text)
    }

    @Test
    fun `build maps assistant role to model and other roles to user`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("hello"),
                UIMessage.assistant("hi"),
                UIMessage.system("system reminder"),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals(listOf("user", "model", "user"), context.turns.map { it.role })
        assertEquals(listOf("hello", "hi", "system reminder"), context.turns.map { it.text })
    }

    @Test
    fun `build filters blank and pending tool messages`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("included"),
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   "))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-1",
                            toolName = "ask_hermes",
                            input = "{}",
                            output = emptyList(),
                        )
                    ),
                ),
                UIMessage.assistant("also included"),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals(listOf("included", "also included"), context.turns.map { it.text })
    }

    @Test
    fun `build includes executed visible tool records folded with adjacent assistant context`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("check this"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-1",
                            toolName = "ask_hermes",
                            input = """{"prompt":"look up account status"}""",
                            output = listOf(UIMessagePart.Text("Hermes says the account is ready.")),
                        )
                    ),
                ),
                UIMessage.assistant("done"),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals(listOf("user", "model"), context.turns.map { it.role })
        assertEquals("check this", context.turns[0].text)
        assertEquals(
            "Tool ask_hermes (call-1)\nInput: {\"prompt\":\"look up account status\"}\nOutput: Hermes says the account is ready.\n\ndone",
            context.turns[1].text,
        )
    }

    @Test
    fun `build folds adjacent model turns from multiple persisted tool records`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("question"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-failed",
                            toolName = "ask_hermes",
                            input = """{"prompt":"old"}""",
                            output = listOf(UIMessagePart.Text("Tool call canceled by Gemini")),
                        )
                    ),
                ),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-complete",
                            toolName = "ask_hermes",
                            input = """{"prompt":"What is 2 + 2?"}""",
                            output = listOf(UIMessagePart.Text("4")),
                        )
                    ),
                ),
                UIMessage.assistant("The answer is 4."),
                UIMessage.user("next question"),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals(listOf("user", "model", "user"), context.turns.map { it.role })
        assertEquals(
            """
                Tool ask_hermes (call-failed)
                Input: {"prompt":"old"}
                Output: Tool call canceled by Gemini

                Tool ask_hermes (call-complete)
                Input: {"prompt":"What is 2 + 2?"}
                Output: 4

                The answer is 4.
            """.trimIndent(),
            context.turns[1].text,
        )
    }

    @Test
    fun `build applies max turns after filtering blank and non text messages`() {
        val skippedToolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "ask_hermes",
                    input = "{}",
                    output = emptyList(),
                )
            ),
        )
        val blankMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   ")))
        val conversation = conversationWith(
            listOf(
                UIMessage.user("text 1"),
                skippedToolMessage,
                UIMessage.assistant("text 2"),
                blankMessage,
                UIMessage.user("text 3"),
                skippedToolMessage,
                blankMessage,
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
            maxTurns = 3,
        )

        assertEquals(listOf("text 1", "text 2", "text 3"), context.turns.map { it.text })
    }

    @Test
    fun `build trims joined text before storing turns`() {
        val conversation = conversationWith(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("  first line"),
                        UIMessagePart.Text("second line  "),
                    ),
                )
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals("first line\nsecond line", context.turns.single().text)
    }

    @Test
    fun `build includes active durable Hermes queue in system instruction`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("queue this"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-active",
                            prompt = "check project status",
                            status = "running",
                        )
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertTrue(context.systemInstruction.contains("Durable Hermes queue status:"))
        assertTrue(context.systemInstruction.contains("- Still running: check project status"))
        assertTrue(context.systemInstruction.contains("answer only from this durable queue status"))
    }

    @Test
    fun `build excludes unannounced durable Hermes result from system instruction`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("start a long request"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-complete",
                            prompt = "summarize the latest deployment",
                            status = "complete",
                            outputText = "Deployment completed successfully.",
                            resultAnnounced = false,
                        )
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertFalse(context.systemInstruction.contains("- Completed: summarize the latest deployment"))
        assertFalse(context.systemInstruction.contains("Hermes answer: Deployment completed successfully."))
    }

    @Test
    fun `build excludes failed durable Hermes result from system instruction`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("start a risky request"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-failed",
                            prompt = "debug the latest run",
                            status = "failed",
                            outputText = "Hermes request failed.",
                            resultAnnounced = false,
                        )
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertFalse(context.systemInstruction.contains("- Failed: debug the latest run"))
        assertFalse(context.systemInstruction.contains("Reason: Hermes request failed."))
        assertFalse(context.systemInstruction.contains("completed, failed, expired, or canceled Hermes queue items"))
    }

    @Test
    fun `build includes terminal queue counts without terminal prompt answer or reason`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("start background work"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-complete",
                            prompt = "private complete request",
                            status = "complete",
                            outputText = "private complete answer",
                            resultAnnounced = false,
                        ),
                        hermesTool(
                            callId = "call-failed",
                            prompt = "private failed request",
                            status = "failed",
                            outputText = "private failed reason",
                            resultAnnounced = false,
                        ),
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertTrue(context.systemInstruction.contains("Durable Hermes queue status:"))
        assertTrue(
            context.systemInstruction.contains(
                "- Unannounced terminal results: completed=1, failed=1, expired=0, canceled=0"
            )
        )
        assertTrue(context.systemInstruction.contains("answer only from this durable queue status"))
        assertFalse(context.systemInstruction.contains("private complete request"))
        assertFalse(context.systemInstruction.contains("private complete answer"))
        assertFalse(context.systemInstruction.contains("private failed request"))
        assertFalse(context.systemInstruction.contains("private failed reason"))

        val turnText = context.turns.joinToString("\n") { it.text }
        assertFalse(turnText.contains("private complete request"))
        assertFalse(turnText.contains("private complete answer"))
        assertFalse(turnText.contains("private failed request"))
        assertFalse(turnText.contains("private failed reason"))
    }

    @Test
    fun `build omits durable queue status when records are announced terminal only`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("check already announced work"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-announced",
                            prompt = "announced private request",
                            status = "complete",
                            outputText = "announced private answer",
                            resultAnnounced = true,
                        ),
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertFalse(context.systemInstruction.contains("Durable Hermes queue status:"))
        assertFalse(context.systemInstruction.contains("Unannounced terminal results"))
        assertFalse(context.systemInstruction.contains("announced private request"))
        assertFalse(context.systemInstruction.contains("announced private answer"))
        val turnText = context.turns.joinToString("\n") { it.text }
        assertFalse(turnText.contains("announced private request"))
        assertFalse(turnText.contains("announced private answer"))
    }

    @Test
    fun `build excludes legacy terminal Hermes tool without announcement metadata from turns`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("check legacy terminal work"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-legacy-terminal",
                            prompt = "legacy private request",
                            status = "failed",
                            outputText = "legacy private reason",
                            includeResultAnnounced = false,
                        ),
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        val turnText = context.turns.joinToString("\n") { it.text }
        assertFalse(turnText.contains("legacy private request"))
        assertFalse(turnText.contains("legacy private reason"))
    }

    @Test
    fun `build excludes malformed unannounced Hermes tool from turns`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("start malformed background work"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-malformed",
                            prompt = "private malformed request",
                            status = "not-a-status",
                            outputText = "private malformed answer",
                            resultAnnounced = false,
                        ),
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        val turnText = context.turns.joinToString("\n") { it.text }
        assertFalse(turnText.contains("private malformed request"))
        assertFalse(turnText.contains("private malformed answer"))
    }

    @Test
    fun `build excludes terminal Hermes tool with malformed announcement metadata from turns`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("start malformed announcement background work"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        hermesTool(
                            callId = "call-malformed-announcement",
                            prompt = "private malformed announcement request",
                            status = "complete",
                            outputText = "private malformed announcement answer",
                            resultAnnouncedText = "not-a-boolean",
                        ),
                    ),
                ),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        val turnText = context.turns.joinToString("\n") { it.text }
        assertFalse(turnText.contains("private malformed announcement request"))
        assertFalse(turnText.contains("private malformed announcement answer"))
    }

    @Test
    fun `build renders voice system prompt placeholders before Gemini setup`() {
        val context = VoiceContextBuilder(
            placeholderValues = VoicePromptPlaceholderValues(
                now = { LocalDateTime.of(2026, 6, 5, 14, 30, 0) },
                locale = { Locale.US },
                timeZone = { TimeZone.getTimeZone("UTC") },
                systemVersion = { "Android SDK v36 (16)" },
                deviceInfo = { "Samsung SM-X730" },
                user = { "Muly" },
            ),
        ).build(
            assistantName = "Hermes",
            assistantPrompt = """
                You are {{char}} on {{model_name}}.
                Time: {{cur_datetime}}
                Locale: {{locale}}
                Timezone: {{timezone}}
                Device: {{device_info}}
                System: {{system_version}}
                User: {{user}}
            """.trimIndent(),
            conversation = conversationWith(emptyList()),
            voiceModelName = "gemini-flash",
        )

        assertEquals(
            expectedVoiceSystemInstruction(
                """
                    You are Hermes on gemini-flash.
                    Time: Jun 5, 2026, 2:30:00 PM
                    Locale: English (United States)
                    Timezone: Coordinated Universal Time
                    Device: Samsung SM-X730
                    System: Android SDK v36 (16)
                    User: Muly
                """.trimIndent()
            ),
            context.systemInstruction.replace('\u202f', ' '),
        )
    }

    @Test
    fun `build renders launch user nickname placeholders`() {
        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "User: {{user}}\nNickname: {{nickname}}",
            conversation = conversationWith(emptyList()),
            userNickname = "Muly",
        )

        assertEquals(
            expectedVoiceSystemInstruction("User: Muly\nNickname: Muly"),
            context.systemInstruction,
        )
    }

    @Test
    fun `build renders voice safe battery placeholder`() {
        val context = VoiceContextBuilder(
            placeholderValues = VoicePromptPlaceholderValues(
                batteryLevel = { "72" },
            ),
        ).build(
            assistantName = "Hermes",
            assistantPrompt = "Battery: {{battery_level}}%",
            conversation = conversationWith(emptyList()),
        )

        assertEquals(
            expectedVoiceSystemInstruction("Battery: 72%"),
            context.systemInstruction,
        )
    }

    private fun expectedVoiceSystemInstruction(assistantPrompt: String): String =
        "$EXPECTED_VOICE_SYSTEM_PREFIX\n\n$assistantPrompt"

    private fun conversationWith(messages: List<UIMessage>): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = messages.map(MessageNode::of),
    )

    private fun hermesTool(
        callId: String,
        prompt: String,
        status: String,
        outputText: String? = null,
        resultAnnounced: Boolean = false,
        resultAnnouncedText: String? = null,
        includeResultAnnounced: Boolean = true,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = VoiceAgentToolNames.ASK_HERMES,
        input = """{"prompt":"$prompt"}""",
        output = outputText?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(),
        metadata = buildJsonObject {
            put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
            put(HERMES_TOOL_STATUS_KEY, status)
            if (resultAnnouncedText != null) {
                put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, resultAnnouncedText)
            } else if (includeResultAnnounced) {
                put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, resultAnnounced)
            }
        },
    )

    private companion object {
        const val EXPECTED_VOICE_SYSTEM_PREFIX =
            "You are Hermes in RikkaHub voice mode.\n" +
                "Hermes is the source of truth for substantive answers in RikkaHub voice mode.\n" +
                "You are the voice interface to Hermes, not a replacement for Hermes.\n" +
                "\n" +
                "Treat every user request as substantive unless it is clearly one of the direct-answer exceptions below.\n" +
                "Default rule: if the user asks for any substantive answer that was not already " +
                "provided by Hermes in this session, you MUST call ask_hermes before answering.\n" +
                "When in doubt, call ask_hermes.\n" +
                "\n" +
                "A request is substantive if answering it would require facts, explanation, advice, status, state, memory, code or project context, access or authorization details, debugging, plans, decisions, or any knowledge outside this small interaction.\n" +
                "Substantive answers include facts, state, context, memory, code, projects, " +
                "decisions, status, access, debugging, plans, and questions such as \"do we\", " +
                "\"did we\", or \"are we\". These examples clarify the rule; they are not the full boundary.\n" +
                "\n" +
                "Answer directly only for greetings, brief acknowledgements, voice controls, " +
                "clarification questions, or restating, interpreting, and summarizing information " +
                "Hermes already provided in the current session.\n" +
                "\n" +
                "Do not use your general knowledge to answer ordinary factual, explanatory, or advice questions. Route them to Hermes with ask_hermes.\n" +
                "Do not answer substantive questions from your own general knowledge, assumptions, " +
                "generic advice, or troubleshooting steps. If speech transcription is imperfect but " +
                "the user's intent appears substantive or Hermes-related, call ask_hermes with the " +
                "best-effort question.\n" +
                "\n" +
                "If ask_hermes returns that Hermes has not answered yet or that the request is pending, " +
                "do not answer the user's substantive question yet. Say only a brief pending " +
                "acknowledgement, such as \"I'm checking Hermes,\" then wait for the Hermes completion " +
                "follow-up.\n" +
                "\n" +
                "When the Hermes completion follow-up arrives, summarize the Hermes answer naturally and briefly."
    }
}
