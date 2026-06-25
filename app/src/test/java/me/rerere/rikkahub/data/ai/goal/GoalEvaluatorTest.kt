package me.rerere.rikkahub.data.ai.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure verdict-mapping + prompt coverage for the `/goal` (#364) evaluator. These carry over the
 * semantics the old Stop-hook `judgeGoal` tests pinned (Met / Continue / Inconclusive, and "a judge
 * failure must never look like Met"), now expressed over the evaluator's DOMAIN response shape
 * ({"achieved": …}) rather than the generic hook control flags.
 */
class GoalEvaluatorTest {

    @Test
    fun `achieved true is Met`() {
        assertEquals(GoalVerdict.Met, parseGoalVerdict("""{"achieved": true}"""))
    }

    @Test
    fun `not achieved with a next step is Continue carrying that step`() {
        assertEquals(
            GoalVerdict.Continue("run the failing test next"),
            parseGoalVerdict("""{"achieved": false, "nextStep": "run the failing test next"}"""),
        )
    }

    @Test
    fun `the next step is trimmed`() {
        assertEquals(
            GoalVerdict.Continue("do the thing"),
            parseGoalVerdict("""{"achieved": false, "nextStep": "  do the thing  "}"""),
        )
    }

    @Test
    fun `achieved wins over a present next step`() {
        assertEquals(
            GoalVerdict.Met,
            parseGoalVerdict("""{"achieved": true, "nextStep": "ignored"}"""),
        )
    }

    @Test
    fun `not achieved without a next step is Inconclusive (pause, do not clear)`() {
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict("""{"achieved": false}"""))
    }

    @Test
    fun `not achieved with a blank next step is Inconclusive`() {
        assertEquals(
            GoalVerdict.Inconclusive,
            parseGoalVerdict("""{"achieved": false, "nextStep": "   "}"""),
        )
    }

    @Test
    fun `an empty object is Inconclusive`() {
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict("{}"))
    }

    @Test
    fun `unparseable text is Inconclusive, never a crash or a false Met`() {
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict("the goal is done!"))
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict(""))
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict("{not valid json"))
    }

    // Parsing is STRICT (no lenient mode) precisely because Met clears a live goal: a sloppy object
    // that only a lenient parser would accept (unquoted key) is a deviation -> Inconclusive, never Met.
    @Test
    fun `a lenient-only malformed object never clears the goal`() {
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict("{achieved: true}"))
        assertEquals(GoalVerdict.Inconclusive, parseGoalVerdict("{achieved: false, nextStep: do x}"))
    }

    // LLMs commonly wrap JSON in markdown fences or surrounding prose — the outermost-brace slice must
    // still recover the verdict so a correct judgment is not lost to formatting.
    @Test
    fun `a verdict wrapped in a markdown fence still parses`() {
        val raw = "```json\n{\"achieved\": true}\n```"
        assertEquals(GoalVerdict.Met, parseGoalVerdict(raw))
    }

    @Test
    fun `a verdict embedded in prose still parses`() {
        val raw = "Sure — here is my call: {\"achieved\": false, \"nextStep\": \"open the PR\"} thanks!"
        assertEquals(GoalVerdict.Continue("open the PR"), parseGoalVerdict(raw))
    }

    @Test
    fun `the prompt embeds the condition and the latest progress`() {
        val prompt = goalJudgePrompt("ship the feature", lastAssistantText = "wrote the code")
        assertTrue(prompt.contains("ship the feature"))
        assertTrue(prompt.contains("wrote the code"))
        assertTrue(prompt.contains("\"achieved\": true"))
        assertTrue(prompt.contains("nextStep"))
    }

    @Test
    fun `the prompt marks absent progress explicitly`() {
        val prompt = goalJudgePrompt("ship the feature", lastAssistantText = null)
        assertTrue(prompt.contains("(no progress yet)"))
    }
}
