package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.StLorebookEntryExtension
import me.rerere.rikkahub.data.model.WorldInfoCharacterStrategy
import me.rerere.rikkahub.data.model.matchesTriggerKeywords
import me.rerere.rikkahub.data.model.withStExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookRegressionTest {

    @Test
    fun `shared lorebook budget should keep scanning after an oversized entry`() {
        val oversizedEntry = regexEntry(
            priority = 200,
            content = tokenContent(120),
        )
        val fittingEntry = regexEntry(
            priority = 100,
            content = tokenContent(50),
        )

        val selected = selectTriggeredLorebookEntries(
            entries = listOf(
                ActivatedLorebookEntry(oversizedEntry, LorebookScope.CHARACTER),
                ActivatedLorebookEntry(fittingEntry, LorebookScope.CHARACTER),
            ),
            budget = 100,
            strategy = WorldInfoCharacterStrategy.EVENLY,
        )

        assertEquals(listOf(fittingEntry.id), selected.map { it.id })
    }

    @Test
    fun `local lorebook budget should keep scanning after an oversized entry`() {
        val oversizedEntry = regexEntry(
            priority = 200,
            content = tokenContent(120),
        )
        val fittingEntry = regexEntry(
            priority = 100,
            content = tokenContent(50),
        )

        val selected = selectBudgetedCandidates(
            candidates = listOf(
                LorebookCandidate(entry = oversizedEntry, isSticky = false, score = 0),
                LorebookCandidate(entry = fittingEntry, isSticky = false, score = 0),
            ),
            budget = 100,
            activatedEntries = emptyList(),
        )

        assertEquals(listOf(fittingEntry.id), selected.map { it.id })
    }

    @Test
    fun `shared lorebook budget should not count ignore budget entries`() {
        val ignoredEntry = regexEntry(
            priority = 200,
            content = tokenContent(120),
            ignoreBudget = true,
        )
        val fittingEntry = regexEntry(
            priority = 100,
            content = tokenContent(100),
        )

        val selected = selectTriggeredLorebookEntries(
            entries = listOf(
                ActivatedLorebookEntry(ignoredEntry, LorebookScope.CHARACTER),
                ActivatedLorebookEntry(fittingEntry, LorebookScope.CHARACTER),
            ),
            budget = 100,
            strategy = WorldInfoCharacterStrategy.EVENLY,
        )

        assertEquals(listOf(ignoredEntry.id, fittingEntry.id), selected.map { it.id })
    }

    @Test
    fun `local lorebook budget should not count ignore budget entries`() {
        val ignoredEntry = regexEntry(
            priority = 200,
            content = tokenContent(120),
            ignoreBudget = true,
        )
        val fittingEntry = regexEntry(
            priority = 100,
            content = tokenContent(100),
        )

        val selected = selectBudgetedCandidates(
            candidates = listOf(
                LorebookCandidate(entry = ignoredEntry, isSticky = false, score = 0),
                LorebookCandidate(entry = fittingEntry, isSticky = false, score = 0),
            ),
            budget = 100,
            activatedEntries = emptyList(),
        )

        assertEquals(listOf(ignoredEntry.id, fittingEntry.id), selected.map { it.id })
    }

    @Test
    fun `local lorebook budget should ignore already activated ignore budget entries`() {
        val ignoredActivatedEntry = regexEntry(
            priority = 200,
            content = tokenContent(120),
            ignoreBudget = true,
        )
        val fittingEntry = regexEntry(
            priority = 100,
            content = tokenContent(100),
        )

        val selected = selectBudgetedCandidates(
            candidates = listOf(
                LorebookCandidate(entry = fittingEntry, isSticky = false, score = 0),
            ),
            budget = 100,
            activatedEntries = listOf(ignoredActivatedEntry),
        )

        assertEquals(listOf(fittingEntry.id), selected.map { it.id })
    }

    @Test
    fun `constant active lorebook entries should still respect generation type triggers`() {
        val continueOnlyEntry = regexEntry(
            content = "Continue only",
            constantActive = true,
        ).withStExtension(
            StLorebookEntryExtension(triggers = listOf("continue"))
        )

        assertFalse(
            continueOnlyEntry.matchesTriggerKeywords(
                context = "",
                triggerContext = LorebookTriggerContext(
                    recentMessagesText = "",
                    generationType = "normal",
                ),
            )
        )
        assertTrue(
            continueOnlyEntry.matchesTriggerKeywords(
                context = "",
                triggerContext = LorebookTriggerContext(
                    recentMessagesText = "",
                    generationType = "continue",
                ),
            )
        )
    }

    private fun regexEntry(
        priority: Int = 0,
        content: String,
        constantActive: Boolean = false,
        ignoreBudget: Boolean = false,
    ) = PromptInjection.RegexInjection(
        id = Uuid.random(),
        priority = priority,
        content = content,
        role = MessageRole.SYSTEM,
        keywords = if (constantActive) emptyList() else listOf("trigger"),
        constantActive = constantActive,
    ).let { entry ->
        if (ignoreBudget) {
            entry.withStExtension(StLorebookEntryExtension(ignoreBudget = true))
        } else {
            entry
        }
    }

    private fun tokenContent(tokenCount: Int): String {
        return (1..tokenCount).joinToString(" ") { index -> "w$index" }
    }
}
