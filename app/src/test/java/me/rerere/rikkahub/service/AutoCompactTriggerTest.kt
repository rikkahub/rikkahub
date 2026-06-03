package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic test for [shouldAutoCompact], the trigger predicate that decides whether a conversation's
 * history should be auto-compacted before the next request. Keeping the decision pure lets it run on the
 * JVM without any Android / network / model dependency, mirroring [shouldRenewWakeLock].
 *
 * The invariant this guards: auto-compact must only fire when (1) the user opted in, (2) the assistant
 * has a finite message-count limit (contextMessageSize > 0 — "unlimited" never auto-triggers because
 * there is no limit to take a fraction of, and we deliberately do NOT invent a token estimator), (3) the
 * message count has reached the configured fraction of that limit, and (4) there are actually enough
 * messages to compress (mirroring compressConversation's keepRecentMessages guard, so we never kick off a
 * compaction that would immediately throw "not enough messages").
 */
class AutoCompactTriggerTest {

    private val keepRecent = 32

    @Test
    fun `disabled never triggers`() {
        assertFalse(
            shouldAutoCompact(
                enabled = false,
                messageCount = 10_000,
                contextMessageSize = 100,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
    }

    @Test
    fun `unlimited context size never triggers`() {
        // contextMessageSize <= 0 means "no message-count cap". There is no limit to take a fraction of,
        // and inventing a token estimator here would be a second, possibly-wrong compaction engine.
        assertFalse(
            shouldAutoCompact(
                enabled = true,
                messageCount = 10_000,
                contextMessageSize = 0,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
    }

    @Test
    fun `below threshold does not trigger`() {
        // limit = ceil(100 * 0.8) = 80; 79 messages is still under the line.
        assertFalse(
            shouldAutoCompact(
                enabled = true,
                messageCount = 79,
                contextMessageSize = 100,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
    }

    @Test
    fun `at or above threshold triggers`() {
        // limit = ceil(100 * 0.8) = 80.
        assertTrue(
            shouldAutoCompact(
                enabled = true,
                messageCount = 80,
                contextMessageSize = 100,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
        assertTrue(
            shouldAutoCompact(
                enabled = true,
                messageCount = 81,
                contextMessageSize = 100,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
    }

    @Test
    fun `not enough messages to compress does not trigger`() {
        // Even past the threshold, if messageCount <= keepRecentMessages there is nothing to compress:
        // compressConversation would throw "not enough messages". Guard so we never start a doomed pass.
        // limit = ceil(40 * 0.8) = 32; messageCount 32 == keepRecentMessages 32 => nothing compressible.
        assertFalse(
            shouldAutoCompact(
                enabled = true,
                messageCount = 32,
                contextMessageSize = 40,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
        // One above keepRecentMessages and still over the limit => compressible => triggers.
        assertTrue(
            shouldAutoCompact(
                enabled = true,
                messageCount = 33,
                contextMessageSize = 40,
                threshold = 0.8f,
                keepRecentMessages = keepRecent,
            )
        )
    }

    @Test
    fun `fraction of limit and exact boundary`() {
        // 0.8 of 10 = 8.0 -> ceil = 8. Triggers at >= 8 (given keepRecentMessages small enough to allow it).
        assertFalse(
            shouldAutoCompact(
                enabled = true, messageCount = 7, contextMessageSize = 10,
                threshold = 0.8f, keepRecentMessages = 4,
            )
        )
        assertTrue(
            shouldAutoCompact(
                enabled = true, messageCount = 8, contextMessageSize = 10,
                threshold = 0.8f, keepRecentMessages = 4,
            )
        )
    }

    @Test
    fun `threshold is clamped into 0 to 1`() {
        // threshold > 1 clamps to 1: limit = ceil(100 * 1) = 100; 99 below, 100 at boundary.
        assertFalse(
            shouldAutoCompact(
                enabled = true, messageCount = 99, contextMessageSize = 100,
                threshold = 5f, keepRecentMessages = keepRecent,
            )
        )
        assertTrue(
            shouldAutoCompact(
                enabled = true, messageCount = 100, contextMessageSize = 100,
                threshold = 5f, keepRecentMessages = keepRecent,
            )
        )
        // threshold < 0 clamps to 0: limit = ceil(100 * 0) = 0; any positive count past keepRecent triggers.
        assertTrue(
            shouldAutoCompact(
                enabled = true, messageCount = 33, contextMessageSize = 100,
                threshold = -1f, keepRecentMessages = keepRecent,
            )
        )
    }
}
