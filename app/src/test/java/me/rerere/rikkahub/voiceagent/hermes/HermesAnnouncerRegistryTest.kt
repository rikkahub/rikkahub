package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAnnouncerRegistryTest {
    @Test
    fun `registry event timestamps are evaluated outside the registry lock`() = runTest {
        val observations = mutableListOf<Pair<String, Boolean>>()
        lateinit var callbackOwner: HermesAnnouncer
        var operation = ""
        val clock = {
            observations += operation to Thread.holdsLock(focusedRegistryLock(callbackOwner))
            testScheduler.currentTime
        }
        val scopedBridge = FocusedRecordingBridge()
        val scopedAnnouncer = focusedAnnouncer(
            queueStore = focusedStore(focusedEmptyConversation()),
            nowMs = clock,
        )
        callbackOwner = scopedAnnouncer

        operation = "attach-scoped"
        scopedAnnouncer.attachScoped(scopedBridge, sessionId = 7L)
        operation = "retire"
        scopedAnnouncer.onGeminiSessionRetired()
        operation = "detach"
        scopedAnnouncer.detachScoped(scopedBridge)

        val defaultAnnouncer = focusedAnnouncer(
            queueStore = focusedStore(focusedEmptyConversation()),
            defaultBridge = { FocusedRecordingBridge() },
            nowMs = clock,
        )
        callbackOwner = defaultAnnouncer
        operation = "attach-default"
        defaultAnnouncer.attachDefaultIfNeeded()

        assertEquals(
            listOf(
                "attach-scoped" to false,
                "retire" to false,
                "detach" to false,
                "attach-default" to false,
            ),
            observations,
        )
    }

    @Test
    fun `scoped attach during default creation wins without a phantom default event`() = runTest {
        val scoped = FocusedRecordingBridge()
        val unusedDefaultCandidate = FocusedRecordingBridge()
        lateinit var subject: HermesAnnouncer
        var providerLockHeld: Boolean? = null
        subject = focusedAnnouncer(
            queueStore = focusedStore(focusedEmptyConversation()),
            defaultBridge = {
                providerLockHeld = Thread.holdsLock(focusedRegistryLock(subject))
                subject.attachScoped(scoped, sessionId = 7L)
                unusedDefaultCandidate
            },
        )

        subject.attachDefaultIfNeeded()
        runCurrent()

        assertEquals(false, providerLockHeld)
        assertSame(scoped, subject.currentAttachment()?.bridge)
        assertEquals(7L, focusedAnnouncerState(subject).bridgeSessionId)
        assertTrue(unusedDefaultCandidate.completions.isEmpty())
        assertTrue(unusedDefaultCandidate.terminals.isEmpty())
        assertTrue(unusedDefaultCandidate.stillWorking.isEmpty())
    }

    @Test
    fun `close during default creation wins and leaves the candidate unused`() = runTest {
        val unusedDefaultCandidate = FocusedRecordingBridge()
        lateinit var subject: HermesAnnouncer
        var providerLockHeld: Boolean? = null
        subject = focusedAnnouncer(
            queueStore = focusedStore(focusedEmptyConversation()),
            defaultBridge = {
                providerLockHeld = Thread.holdsLock(focusedRegistryLock(subject))
                subject.close()
                unusedDefaultCandidate
            },
        )

        subject.attachDefaultIfNeeded()
        runCurrent()

        assertEquals(false, providerLockHeld)
        assertNull(subject.currentAttachment())
        assertTrue(focusedAnnouncerState(subject).closed)
        assertTrue(unusedDefaultCandidate.completions.isEmpty())
        assertTrue(unusedDefaultCandidate.terminals.isEmpty())
        assertTrue(unusedDefaultCandidate.stillWorking.isEmpty())
    }
}
