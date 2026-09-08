package me.rerere.asr

import org.junit.Assert.*
import org.junit.Test

class ASRVoiceTurnTest {
    @Test fun `speech stop waits for final text`() {
        val turn = ASRVoiceTurn().started("a").stopped("a")
        assertFalse(turn.isComplete)
        assertEquals("hello", turn.completed("a", " hello ").finalText)
        assertTrue(turn.completed("a", "hello").isComplete)
    }

    @Test fun `final text can arrive before stopped event`() {
        val turn = ASRVoiceTurn().completed("a", "hello")
        assertFalse(turn.isComplete)
        assertTrue(turn.stopped("a").isComplete)
    }

    @Test fun `another utterance cannot overwrite first turn`() {
        val turn = ASRVoiceTurn().started("a").stopped("a")
        assertEquals(turn, turn.completed("b", "wrong").stopped("b"))
        assertEquals("right", turn.completed("a", "right").finalText)
    }

    @Test fun `blank final result still completes and missing ids do not`() {
        assertTrue(ASRVoiceTurn().stopped("a").completed("a", " ").isComplete)
        assertFalse(ASRVoiceTurn().stopped("").completed("", "hello").isComplete)
    }

    @Test fun `only adapters with integrated server endpoint detection support voice mode`() {
        assertTrue(ASRProviderSetting.OpenAIRealtime().supportsServerVadVoiceMode)
        assertTrue(ASRProviderSetting.DashScope().supportsServerVadVoiceMode)
        assertTrue(ASRProviderSetting.Volcengine().supportsServerVadVoiceMode)
        assertFalse(ASRProviderSetting.Step().supportsServerVadVoiceMode)
        assertFalse(ASRProviderSetting.MiMo().supportsServerVadVoiceMode)
    }
}
