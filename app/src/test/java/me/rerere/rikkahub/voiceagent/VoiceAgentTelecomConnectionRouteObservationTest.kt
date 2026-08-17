package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VoiceAgentTelecomConnectionRouteObservationTest {
    @Test
    fun `recordObservedAutomationRoute records speaker, earpiece, bluetooth, and wired headset through production helper`() {
        val recordedEvents = mutableListOf<VoiceAutomationEventInput>()
        val fakeRuntime = object : VoiceAutomationRuntime {
            override fun status(): VoiceAutomationStatus = VoiceAutomationStatus(
                state = VoiceAutomationRunState.Active,
                runHash = "hash-1",
            )
            override fun record(event: VoiceAutomationEventInput) {
                recordedEvents.add(event)
            }
            override fun recordIfActiveRun(runHash: String, event: VoiceAutomationEventInput): Boolean = false
            override fun markReconnectTransportRestored(runHash: String): Boolean = false
            override fun prepare(binding: me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunBinding) = Unit
            override fun finalizeRun(): File = File("tmp")
            override fun reset() = Unit
        }

        val observedRoutes = listOf(
            VoiceAgentCallEndpointType.Speaker,
            VoiceAgentCallEndpointType.Earpiece,
            VoiceAgentCallEndpointType.Bluetooth,
            VoiceAgentCallEndpointType.WiredHeadset,
        )

        observedRoutes.forEach { route ->
            recordObservedAutomationRoute(route, fakeRuntime)
        }

        assertEquals(4, recordedEvents.size)
        assertEquals(observedRoutes, recordedEvents.map { it.route })
    }
}
