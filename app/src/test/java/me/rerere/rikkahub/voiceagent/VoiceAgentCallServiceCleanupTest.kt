package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentCallServiceCleanupTest {
    @Test
    fun `stale end completion cannot clean up a newer call generation`() = runBlocking {
        var currentGeneration = 1L
        var completions = 0
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            completeVoiceAgentEndForGeneration(
                isCurrent = { currentGeneration == 1L },
                endAndDrain = {
                    drainStarted.complete(Unit)
                    releaseDrain.await()
                },
                onCompleted = { completions += 1 },
            )
        }
        drainStarted.await()

        currentGeneration = 2L
        releaseDrain.complete(Unit)
        cleanup.await()

        assertEquals(0, completions)
    }
}
