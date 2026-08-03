package me.rerere.rikkahub.voiceagent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class VoiceAutomationDebugManifestTest {
    @Test
    fun `automation receiver is exported only from debug behind dump permission`() {
        val debugReceiver = receiver(
            manifestPath = "src/debug/AndroidManifest.xml",
            className = ".voiceagent.debug.VoiceAutomationControlReceiver",
        )

        assertEquals("true", debugReceiver.getAttribute("android:exported"))
        assertEquals("android.permission.DUMP", debugReceiver.getAttribute("android:permission"))
        assertEquals(
            setOf(
                "me.rerere.rikkahub.voiceagent.automation.PREPARE",
                "me.rerere.rikkahub.voiceagent.automation.STATUS",
                "me.rerere.rikkahub.voiceagent.automation.MARK",
                "me.rerere.rikkahub.voiceagent.automation.ROUTE",
                "me.rerere.rikkahub.voiceagent.automation.FINALIZE",
                "me.rerere.rikkahub.voiceagent.automation.FINALIZE_BOUND",
                "me.rerere.rikkahub.voiceagent.automation.DUMP",
            ),
            debugReceiver.getElementsByTagName("action").let { actions ->
                (0 until actions.length)
                    .map { actions.item(it) as Element }
                    .map { it.getAttribute("android:name") }
                    .toSet()
            },
        )

        val mainManifest = parseManifest("src/main/AndroidManifest.xml")
        val mainReceivers = mainManifest.getElementsByTagName("receiver")
        assertFalse(
            (0 until mainReceivers.length)
                .map { mainReceivers.item(it) as Element }
                .any { it.getAttribute("android:name").contains("VoiceAutomation") },
        )
        val releaseManifest = File("src/release/AndroidManifest.xml")
        if (releaseManifest.exists()) {
            val releaseReceivers = parseManifest(releaseManifest.path).getElementsByTagName("receiver")
            assertTrue(
                (0 until releaseReceivers.length)
                    .map { releaseReceivers.item(it) as Element }
                    .none { it.getAttribute("android:name").contains("VoiceAutomation") },
            )
        }
    }

    private fun receiver(manifestPath: String, className: String): Element {
        val receivers = parseManifest(manifestPath).getElementsByTagName("receiver")
        return (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .single { it.getAttribute("android:name") == className }
    }

    private fun parseManifest(path: String) =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(path))
}
