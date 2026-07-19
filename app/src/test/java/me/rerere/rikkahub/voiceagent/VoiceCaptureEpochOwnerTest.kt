package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureEpochOwnerTest {
    @Test
    fun `deferred cleanup failure does not strand later cleanup or retirement`() = runBlocking {
        val owner = newOwner()
        val token = checkNotNull(owner.open(SESSION_ID))
        val admission = owner.completeStart(token).acceptedAdmission()
        val cleanupFailure = IllegalStateException("first cleanup failed")
        val cleanupCalls = mutableListOf<String>()
        owner.closeCurrent().finish {
            cleanupCalls += "first"
            throw cleanupFailure
        }
        owner.closeCurrent().finish {
            cleanupCalls += "second"
        }

        val thrown = runCatching(admission::close).exceptionOrNull()

        assertSame(cleanupFailure, thrown)
        assertEquals(listOf("first", "second"), cleanupCalls)
        assertEquals(0, token.epoch.pendingCleanups)
        assertTrue(token.epoch.retired.isCompleted)
    }

    @Test
    fun `deferred cleanup preserves first failure and suppresses later failures in order`() = runBlocking {
        val owner = newOwner()
        val token = checkNotNull(owner.open(SESSION_ID))
        val admission = owner.completeStart(token).acceptedAdmission()
        val firstFailure = IllegalStateException("first cleanup failed")
        val secondFailure = IllegalArgumentException("second cleanup failed")
        val thirdFailure = UnsupportedOperationException("third cleanup failed")
        val cleanupCalls = mutableListOf<String>()
        listOf(
            "first" to firstFailure,
            "second" to secondFailure,
            "third" to thirdFailure,
        ).forEach { (name, failure) ->
            owner.closeCurrent().finish {
                cleanupCalls += name
                throw failure
            }
        }

        val thrown = runCatching(admission::close).exceptionOrNull()

        assertSame(firstFailure, thrown)
        assertEquals(listOf(secondFailure, thirdFailure), thrown?.suppressed?.toList())
        assertEquals(listOf("first", "second", "third"), cleanupCalls)
        assertEquals(0, token.epoch.pendingCleanups)
        assertTrue(token.epoch.retired.isCompleted)
    }

    @Test
    fun `open proceeds after deferred cleanup failure retires prior epoch`() = runBlocking {
        val owner = newOwner()
        val firstToken = checkNotNull(owner.open(SESSION_ID))
        val admission = owner.completeStart(firstToken).acceptedAdmission()
        val cleanupFailure = IllegalStateException("first cleanup failed")
        var secondCleanupCalls = 0
        owner.closeCurrent().finish { throw cleanupFailure }
        owner.closeCurrent().finish { secondCleanupCalls += 1 }

        val thrown = runCatching(admission::close).exceptionOrNull()
        val secondToken = withTimeout(TEST_TIMEOUT_MS) {
            checkNotNull(owner.open(SESSION_ID))
        }

        assertSame(cleanupFailure, thrown)
        assertEquals(1, secondCleanupCalls)
        assertTrue(firstToken.epoch.retired.isCompleted)
        assertEquals(0, firstToken.epoch.pendingCleanups)
        assertNotSame(firstToken.epoch, secondToken.epoch)
    }

    private fun newOwner() = VoiceCaptureEpochOwner(
        lock = Any(),
        canUseEpochLocked = { true },
    )

    private fun VoiceCaptureStartCompletion.acceptedAdmission(): VoiceCaptureEffectAdmission =
        (this as VoiceCaptureStartCompletion.Accepted).admission

    private companion object {
        const val SESSION_ID = 42L
        const val TEST_TIMEOUT_MS = 1_000L
    }
}
