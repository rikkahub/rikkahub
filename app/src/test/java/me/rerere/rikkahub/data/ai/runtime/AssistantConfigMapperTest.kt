package me.rerere.rikkahub.data.ai.runtime

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.runtime.contract.AssistantRegexScope
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the lossy app->neutral boundary [Assistant.toAssistantConfig] (issue #243 slice 3). The
 * mapper's KDoc asserts a "1:1 copy ... round-trips ... not lossy" invariant; nothing else in the
 * slice exercises it (the three policy tests build [me.rerere.ai.runtime.contract.AssistantConfig]
 * by hand). This guards against a dropped field, a swapped field (e.g. subagentMaxSteps reading the
 * wrong source), or a [localToolId] @SerialName drift — any of which would otherwise compile and
 * pass CI silently.
 */
class AssistantConfigMapperTest {

    @Test
    fun toAssistantConfigCopiesEveryFieldNoLoss() {
        val id = Uuid.random()
        val chatModelId = Uuid.random()
        val mcpServer = Uuid.random()
        val modeInjectionId = Uuid.random()
        val lorebookId = Uuid.random()
        val knowledgeBaseId = Uuid.random()
        val regexId = Uuid.random()

        val regex = AssistantRegex(
            id = regexId,
            name = "rename-foo",
            enabled = false,
            findRegex = "foo",
            replaceString = "bar",
            affectingScope = setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT),
            visualOnly = true,
        )

        val assistant = Assistant(
            id = id,
            chatModelId = chatModelId,
            systemPrompt = "you are a test",
            streamOutput = false,
            enableMemory = true,
            useGlobalMemory = true,
            enableRecentChatsReference = true,
            messageTemplate = "{{ message }}!!",
            regexes = listOf(regex),
            reasoningLevel = ReasoningLevel.HIGH,
            maxTokens = 4096,
            customHeaders = listOf(CustomHeader(name = "X-Test", value = "1")),
            customBodies = listOf(CustomBody(key = "k", value = JsonPrimitive("v"))),
            mcpServers = setOf(mcpServer),
            // Every LocalToolOption variant, in a deliberately non-default order.
            localTools = listOf(
                LocalToolOption.AskUser,
                LocalToolOption.JavascriptEngine,
                LocalToolOption.TimeInfo,
                LocalToolOption.Clipboard,
                LocalToolOption.Tts,
            ),
            enabledSkills = setOf("skill-a", "skill-b"),
            modeInjectionIds = setOf(modeInjectionId),
            lorebookIds = setOf(lorebookId),
            knowledgeBaseId = knowledgeBaseId,
            description = "call me when X",
            spawnable = true,
            maxSteps = 17,
        )

        val config = assistant.toAssistantConfig()

        assertEquals(assistant.id, config.id)
        assertEquals(assistant.chatModelId, config.chatModelId)
        assertEquals(assistant.systemPrompt, config.systemPrompt)
        assertEquals(assistant.streamOutput, config.streamOutput)
        assertEquals(assistant.enableMemory, config.enableMemory)
        assertEquals(assistant.useGlobalMemory, config.useGlobalMemory)
        assertEquals(assistant.enableRecentChatsReference, config.enableRecentChatsReference)
        assertEquals(assistant.messageTemplate, config.messageTemplate)
        assertEquals(assistant.reasoningLevel, config.reasoningLevel)
        assertEquals(assistant.maxTokens, config.maxTokens)
        assertEquals(assistant.customHeaders, config.customHeaders)
        assertEquals(assistant.customBodies, config.customBodies)
        assertEquals(assistant.mcpServers, config.mcpServers)
        assertEquals(assistant.enabledSkills, config.enabledSkills)
        assertEquals(assistant.modeInjectionIds, config.modeInjectionIds)
        assertEquals(assistant.lorebookIds, config.lorebookIds)
        assertEquals(assistant.knowledgeBaseId, config.knowledgeBaseId)
        assertEquals(assistant.description, config.description)
        assertEquals(assistant.spawnable, config.spawnable)
        // Field-rename pin: the neutral field subagentMaxSteps is sourced from Assistant.maxSteps.
        assertEquals(assistant.maxSteps, config.subagentMaxSteps)

        // localTools allowlist projected to stable ids, order preserved.
        assertEquals(
            listOf("ask_user", "javascript_engine", "time_info", "clipboard", "tts"),
            config.localToolIds,
        )

        // Regex rule mapped 1:1, with the neutral scope tokens.
        assertEquals(1, config.regexes.size)
        val rule = config.regexes.single()
        assertEquals(regex.id, rule.id)
        assertEquals(regex.name, rule.name)
        assertEquals(regex.enabled, rule.enabled)
        assertEquals(regex.findRegex, rule.findRegex)
        assertEquals(regex.replaceString, rule.replaceString)
        assertEquals(regex.visualOnly, rule.visualOnly)
        assertEquals(
            setOf(AssistantRegexScope.User, AssistantRegexScope.Assistant),
            rule.affectingScope,
        )
    }

    @Test
    fun localToolIdRoundTripsForEveryVariant() {
        val all = listOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Tts,
            LocalToolOption.AskUser,
        )
        for (option in all) {
            assertEquals(option, localToolOptionOf(option.localToolId()))
        }
        assertNull(localToolOptionOf("not_a_real_tool_id"))
    }
}
