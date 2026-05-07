package me.rerere.rikkahub.reliability

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the version comparator. We don't fire real HTTP — the [check]
 * method is reach-out + parse, exercised end-to-end by manual invocation via the
 * `check_app_updates` LLM tool. The comparator is the part with non-trivial logic
 * worth pinning.
 */
class GitHubReleaseCheckerTest {

    private val checker = GitHubReleaseChecker(OkHttpClient())

    @Test fun `agent revision bump is newer`() {
        assertTrue(checker.isNewer("2.1.15-agent.1", "2.1.15-agent.0"))
        assertTrue(checker.isNewer("v2.1.15-agent.1", "2.1.15-agent.0"))
    }

    @Test fun `patch bump is newer`() {
        assertTrue(checker.isNewer("2.1.16-agent.0", "2.1.15-agent.0"))
        assertTrue(checker.isNewer("2.1.16-agent.0", "2.1.15-agent.99"))
    }

    @Test fun `minor bump is newer regardless of patch and agent`() {
        assertTrue(checker.isNewer("2.2.0-agent.0", "2.1.99-agent.99"))
    }

    @Test fun `major bump is newer regardless of all else`() {
        assertTrue(checker.isNewer("3.0.0-agent.0", "2.99.99-agent.99"))
    }

    @Test fun `same version is not newer`() {
        assertFalse(checker.isNewer("2.1.15-agent.0", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("v2.1.15-agent.0", "2.1.15-agent.0"))
    }

    @Test fun `older version is not newer`() {
        assertFalse(checker.isNewer("2.1.14-agent.99", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15-agent.0", "2.1.15-agent.1"))
    }

    @Test fun `missing agent suffix treated as agent_0`() {
        assertTrue(checker.isNewer("2.1.16", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15", "2.1.15-agent.0"))
    }

    @Test fun `unparseable strings fail safe to false`() {
        assertFalse(checker.isNewer("totally bogus", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15-agent.0", "totally bogus"))
    }

    @Test fun `partial version comparator works`() {
        assertTrue(checker.isNewer("3", "2.99.99-agent.99"))
        assertFalse(checker.isNewer("2", "2.0.0-agent.0"))
    }
}
