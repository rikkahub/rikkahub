package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAnnouncerAdmissionTest {
    @Test
    fun `retirement revokes every proactive send waiting to enter the bridge`() = runTest {
        FocusedProactiveSendKind.entries.forEach { kind ->
            val selected = CompletableDeferred<Unit>()
            val releaseEntry = CompletableDeferred<Unit>()
            val store = focusedStore(focusedConversationFor(kind))
            val bridge = FocusedRecordingBridge()
            val announcer = focusedAnnouncer(
                queueStore = store,
                beforeAnnouncementAdmission = {
                    selected.complete(Unit)
                    releaseEntry.await()
                },
            )

            announcer.prepareFocusedProactiveSend(kind, bridge)
            runCurrent()

            announcer.onGeminiTurnComplete()
            runCurrent()
            selected.await()

            announcer.onGeminiSessionRetired()
            releaseEntry.complete(Unit)
            runCurrent()

            assertTrue("$kind entered the retired bridge", bridge.completions.isEmpty())
            assertTrue("$kind entered the retired bridge", bridge.terminals.isEmpty())
            assertTrue("$kind entered the retired bridge", bridge.stillWorking.isEmpty())
            val record = requireNotNull(store.latestRecord("call-admission", "job-admission"))
            if (kind == FocusedProactiveSendKind.Progress) {
                assertFalse(record.stillWorkingAnnounced)
            } else {
                assertTrue(record.messageWritten)
            }
        }
    }

    @Test
    fun `proactive bridge code runs outside the announcer registry lock`() = runTest {
        FocusedProactiveSendKind.entries.forEach { kind ->
            val store = focusedStore(focusedConversationFor(kind))
            lateinit var subject: HermesAnnouncer
            var registryLockHeld: Boolean? = null
            val bridge = FocusedRecordingBridge(
                beforeSend = {
                    registryLockHeld = Thread.holdsLock(focusedRegistryLock(subject))
                },
            )
            subject = focusedAnnouncer(queueStore = store)

            subject.prepareFocusedProactiveSend(kind, bridge)
            runCurrent()
            subject.onGeminiTurnComplete()
            runCurrent()

            assertEquals("$kind bridge code ran under the registry lock", false, registryLockHeld)
        }
    }

    @Test
    fun `admitted proactive send runs exactly once when retirement follows admission`() = runTest {
        FocusedProactiveSendKind.entries.forEach { kind ->
            val entered = CompletableDeferred<Unit>()
            val releaseSend = CompletableDeferred<Unit>()
            val store = focusedStore(focusedConversationFor(kind))
            val bridge = FocusedRecordingBridge(
                beforeSend = {
                    entered.complete(Unit)
                    releaseSend.await()
                },
            )
            val announcer = focusedAnnouncer(queueStore = store)

            announcer.prepareFocusedProactiveSend(kind, bridge)
            runCurrent()
            announcer.onGeminiTurnComplete()
            runCurrent()
            entered.await()

            announcer.onGeminiSessionRetired()
            releaseSend.complete(Unit)
            runCurrent()

            bridge.assertEnteredExactlyOnce(kind)
        }
    }
}
