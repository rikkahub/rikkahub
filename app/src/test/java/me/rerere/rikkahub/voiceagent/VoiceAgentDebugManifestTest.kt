package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioDebugInjector
import me.rerere.rikkahub.voiceagent.debug.VoiceAgentDebugSeedReceiver
import me.rerere.rikkahub.voiceagent.debug.HermesTextDebugReceiver

class VoiceAgentDebugManifestTest {
    @Test
    fun `debug receivers require shell held permission`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceAudioDebugInjectionReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")
        val textReceiver = findReceiver(".voiceagent.debug.HermesTextDebugReceiver")

        assertEquals("android.permission.DUMP", audioReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", seedReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", textReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", findService(".voiceagent.VoiceAgentCallService").getAttribute("android:permission"))
    }

    @Test
    fun `debug receivers remain exported for adb workflows`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceAudioDebugInjectionReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")
        val textReceiver = findReceiver(".voiceagent.debug.HermesTextDebugReceiver")

        assertEquals("true", audioReceiver.getAttribute("android:exported"))
        assertEquals("true", seedReceiver.getAttribute("android:exported"))
        assertEquals("true", textReceiver.getAttribute("android:exported"))
        assertEquals("true", findService(".voiceagent.VoiceAgentCallService").getAttribute("android:exported"))
    }

    @Test
    fun `debug receivers keep expected actions`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceAudioDebugInjectionReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")
        val textReceiver = findReceiver(".voiceagent.debug.HermesTextDebugReceiver")

        assertEquals(
            listOf(VoiceAudioDebugInjector.ACTION_INJECT_PCM),
            audioReceiver.actionNames(),
        )
        assertEquals(
            listOf(VoiceAgentDebugSeedReceiver.ACTION_SEED_HERMES_PROVIDER),
            seedReceiver.actionNames(),
        )
        assertEquals(
            listOf(HermesTextDebugReceiver.ACTION_SEND_HERMES_TEXT),
            textReceiver.actionNames(),
        )
    }

    private fun findReceiver(name: String): Element {
        return findManifestElement(tagName = "receiver", name = name)
    }

    private fun findService(name: String): Element {
        return findManifestElement(tagName = "service", name = name)
    }

    private fun findManifestElement(tagName: String, name: String): Element {
        val manifest = File("src/debug/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val elements = document.getElementsByTagName(tagName)

        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .first { it.getAttribute("android:name") == name }
    }

    private fun Element.actionNames(): List<String> {
        val actions = getElementsByTagName("action")

        return (0 until actions.length)
            .map { actions.item(it) as Element }
            .map { it.getAttribute("android:name") }
    }
}
