package me.rerere.rikkahub.data.ai.goal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model

/**
 * The `/goal` (#364) judge's verdict, kept as three distinct cases so a JUDGE FAILURE never silently
 * abandons the goal:
 *  - [Met]          the model judged the goal achieved -> clear the goal and stop.
 *  - [Continue]     not yet met -> inject [directive] as the next user turn and run one more turn.
 *  - [Inconclusive] the judge produced no usable verdict — a provider failure, a timeout, or an
 *                   unparseable answer. PAUSE: keep the goal armed so a later manual send can retry;
 *                   a transient judge failure must never clear it.
 *
 * Lives with the [GoalEvaluator] port (its sole producer) rather than in the ChatService god-object,
 * so the evaluator is the typed contract the goal loop consumes (DIP).
 */
sealed interface GoalVerdict {
    data object Met : GoalVerdict
    data class Continue(val directive: String) : GoalVerdict
    data object Inconclusive : GoalVerdict
}

/**
 * Judges whether a `/goal` condition is achieved after a turn. A port (not the ChatService method it
 * replaces) so the goal loop depends on an abstraction and tests can supply a fake; the production
 * [LlmGoalEvaluator] runs the judge on the assistant's CHAT model — the model the turn just ran on,
 * so it is guaranteed available — rather than the settings fast model the old Stop-hook judge used,
 * which could be unset and silently degrade every goal to [GoalVerdict.Inconclusive] (#364 root-cause
 * fix: a successful chat turn must not require a separate fast model for the goal to ever clear).
 */
interface GoalEvaluator {
    /**
     * @param condition the goal text the user armed.
     * @param lastAssistantText the most recent assistant message (the turn's progress), or null.
     * @param model the chat model to judge with (the one the just-finished turn used).
     */
    suspend fun judge(condition: String, lastAssistantText: String?, model: Model): GoalVerdict
}

/**
 * The judge prompt: a goal-specific evaluator over the latest progress, asking for a DOMAIN verdict
 * ({"achieved": …}) rather than reusing the generic Stop-hook control flags. The latest assistant
 * message is embedded directly so the judge actually sees the progress it is rating (the old hook
 * path claimed "the conversation so far" but only forwarded that one message into a generic wrapper).
 */
internal fun goalJudgePrompt(condition: String, lastAssistantText: String?): String = buildString {
    appendLine("The user set this GOAL for you to accomplish autonomously:")
    appendLine("\"$condition\"")
    appendLine()
    appendLine("Your latest progress (the most recent assistant message):")
    appendLine(lastAssistantText?.takeIf { it.isNotBlank() } ?: "(no progress yet)")
    appendLine()
    appendLine("Decide, from the progress above, whether the goal is now FULLY achieved.")
    appendLine("Respond with ONLY a JSON object, no prose and no markdown fences:")
    appendLine("- If it IS fully achieved: {\"achieved\": true}")
    appendLine("- If it is NOT yet achieved: {\"achieved\": false, \"nextStep\": \"<one short concrete next step>\"}")
    append("The nextStep is injected as your next user turn to keep you working toward the goal.")
}

/**
 * Maps a judge response into a [GoalVerdict]. Pure + top-level so the verdict mapping is unit-testable
 * without the provider round-trip. The response is untrusted model text, so any deviation (prose,
 * markdown fences, malformed JSON, a missing `achieved`, or "not achieved" with no next step) degrades
 * to [GoalVerdict.Inconclusive] — never a false [Met] that would wrongly clear the goal, and never a
 * crash. Tolerates a JSON object embedded in surrounding text by slicing the outermost braces.
 */
internal fun parseGoalVerdict(raw: String): GoalVerdict {
    val json = extractJsonObject(raw) ?: return GoalVerdict.Inconclusive
    val wire = try {
        GoalVerdictJson.decodeFromString<GoalJudgeWire>(json)
    } catch (e: IllegalArgumentException) {
        // kotlinx throws SerializationException (an IllegalArgumentException) on bad JSON — the model
        // wrote something that is not our verdict shape, which is a pause, never a crash.
        return GoalVerdict.Inconclusive
    }
    return when {
        wire.achieved == true -> GoalVerdict.Met
        wire.achieved == false && !wire.nextStep.isNullOrBlank() -> GoalVerdict.Continue(wire.nextStep.trim())
        else -> GoalVerdict.Inconclusive
    }
}

// Slice the outermost {...} so a verdict wrapped in markdown fences or prose still parses; the first
// '{' to the last '}' captures a single top-level object including any nested braces. Null when there
// is no object at all.
private fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    return if (start in 0 until end) raw.substring(start, end + 1) else null
}

// STRICT on purpose (no isLenient): Met clears a live goal, so it must require a well-formed
// {"achieved": true}. A sloppy/lenient object (e.g. unquoted keys) is a deviation -> Inconclusive
// (pause, keep the goal armed) — the safe failure, never a false Met. Unknown extra keys are still
// tolerated because the producer is a model and over-production is benign.
private val GoalVerdictJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class GoalJudgeWire(
    val achieved: Boolean? = null,
    val nextStep: String? = null,
)
