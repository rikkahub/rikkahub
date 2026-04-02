package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
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

    @Test
    fun `global and assistant regexes should be applied together in order`() {
        val settings = Settings(
            globalRegexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            )
        )
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "bar",
                    replaceString = "baz",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            )
        )

        assertEquals(
            "baz",
            "foo".replaceRegexes(
                assistant = assistant,
                settings = settings,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )
    }

    @Test
    fun `group toggles should gate global preset and assistant regexes independently`() {
        val globalRegex = AssistantRegex(
            id = Uuid.random(),
            enabled = true,
            findRegex = "foo",
            replaceString = "bar",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val presetRegex = AssistantRegex(
            id = Uuid.random(),
            enabled = true,
            findRegex = "bar",
            replaceString = "baz",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val assistantRegex = AssistantRegex(
            id = Uuid.random(),
            enabled = true,
            findRegex = "baz",
            replaceString = "qux",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val preset = SillyTavernPreset(
            regexEnabled = false,
            regexes = listOf(presetRegex),
        )
        val assistant = Assistant(
            regexEnabled = false,
            regexes = listOf(assistantRegex),
        )
        val settings = Settings(
            globalRegexEnabled = false,
            globalRegexes = listOf(globalRegex),
            stPresets = listOf(preset),
            selectedStPresetId = preset.id,
        )

        assertEquals(
            "foo",
            "foo".replaceRegexes(
                assistant = assistant,
                settings = settings,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )

        val globalOnly = settings.copy(globalRegexEnabled = true)
        assertEquals(
            "bar",
            "foo".replaceRegexes(
                assistant = assistant,
                settings = globalOnly,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )

        val globalAndPreset = globalOnly.copy(
            stPresets = listOf(preset.copy(regexEnabled = true)),
        )
        assertEquals(
            "baz",
            "foo".replaceRegexes(
                assistant = assistant,
                settings = globalAndPreset,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )

        val allEnabled = globalAndPreset
        val assistantEnabled = assistant.copy(regexEnabled = true)
        assertEquals(
            "qux",
            "foo".replaceRegexes(
                assistant = assistantEnabled,
                settings = allEnabled,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )
    }

    @Test
    fun `effective regexes should resolve active st preset regexes instead of raw compatibility cache`() {
        val presetRegex = AssistantRegex(
            id = Uuid.random(),
            enabled = true,
            findRegex = "foo",
            replaceString = "bar",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val staleCacheRegex = AssistantRegex(
            id = Uuid.random(),
            enabled = true,
            findRegex = "foo",
            replaceString = "stale",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val preset = SillyTavernPreset(regexes = listOf(presetRegex))
        val settings = Settings(
            stPresets = listOf(preset),
            selectedStPresetId = preset.id,
            regexes = listOf(staleCacheRegex),
        )
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "bar",
                    replaceString = "baz",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            )
        )

        assertEquals(
            "baz",
            "foo".replaceRegexes(
                assistant = assistant,
                settings = settings,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )
    }

    @Test
    fun `assistant regex should override same shared regex from earlier scopes`() {
        val globalRegex = AssistantRegex(
            id = Uuid.random(),
            enabled = false,
            findRegex = "foo",
            replaceString = "bar",
            affectingScope = setOf(AssistantAffectScope.USER),
        )
        val assistantRegex = globalRegex.copy(
            id = Uuid.random(),
            enabled = true,
        )
        val settings = Settings(
            globalRegexes = listOf(globalRegex),
        )
        val assistant = Assistant(
            regexEnabled = true,
            regexes = listOf(assistantRegex),
        )

        assertEquals(listOf(assistantRegex), settings.effectiveRegexes(assistant))
        assertEquals(
            "bar",
            "foo".replaceRegexes(
                assistant = assistant,
                settings = settings,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )
    }

    @Test
    fun `st placement should filter imported regex scripts by target`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    stPlacements = setOf(AssistantRegexPlacement.AI_OUTPUT),
                )
            )
        )

        assertEquals(
            "foo",
            "foo".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.USER_INPUT,
            )
        )
        assertEquals(
            "bar",
            "foo".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.ASSISTANT,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.AI_OUTPUT,
            )
        )
    }

    @Test
    fun `trim strings should be removed from captured replacements`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "(Sir )?(Lancelot)",
                    replaceString = "[$1$2]",
                    affectingScope = setOf(AssistantAffectScope.USER),
                    trimStrings = listOf("Sir "),
                )
            )
        )

        assertEquals(
            "[Lancelot]",
            "Sir Lancelot".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )
    }

    @Test
    fun `escaped substitute regex should treat char macro as literal text`() {
        val assistant = Assistant(
            name = "Fallback",
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "{{char}}",
                    replaceString = "matched",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    substituteRegex = AssistantRegexSubstituteStrategy.ESCAPED,
                    stPlacements = setOf(AssistantRegexPlacement.AI_OUTPUT),
                )
            ),
            stCharacterData = SillyTavernCharacterData(
                name = "A+B",
            ),
        )
        val settings = Settings(
            displaySetting = DisplaySetting(userNickname = "User"),
        )

        assertEquals(
            "matched",
            "A+B".replaceRegexes(
                assistant = assistant,
                settings = settings,
                scope = AssistantAffectScope.ASSISTANT,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.AI_OUTPUT,
            )
        )
    }

    @Test
    fun `run on edit false should skip regex during edits`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "draft",
                    replaceString = "final",
                    affectingScope = setOf(AssistantAffectScope.USER),
                    runOnEdit = false,
                    stPlacements = setOf(AssistantRegexPlacement.USER_INPUT),
                )
            )
        )

        assertEquals(
            "draft",
            "draft".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.USER_INPUT,
                isEdit = true,
            )
        )
        assertEquals(
            "final",
            "draft".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.USER_INPUT,
                isEdit = false,
            )
        )
    }

    @Test
    fun `manual regex should keep legacy replace all behavior`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            )
        )

        assertEquals(
            "bar bar",
            "foo foo".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
            )
        )
    }

    @Test
    fun `st regex without global flag should replace first match only`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "foo",
                    rawFindRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    stPlacements = setOf(AssistantRegexPlacement.AI_OUTPUT),
                    sourceKind = AssistantRegexSourceKind.ST_SCRIPT,
                )
            )
        )

        assertEquals(
            "bar foo",
            "foo foo".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.ASSISTANT,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.AI_OUTPUT,
            )
        )
    }

    @Test
    fun `st regex should preserve global and case insensitive flags`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "foo",
                    rawFindRegex = "/foo/gi",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    stPlacements = setOf(AssistantRegexPlacement.AI_OUTPUT),
                    sourceKind = AssistantRegexSourceKind.ST_SCRIPT,
                )
            )
        )

        assertEquals(
            "bar bar",
            "FOO foo".replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.ASSISTANT,
                phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                placement = AssistantRegexPlacement.AI_OUTPUT,
            )
        )
    }
}
