package me.rerere.rikkahub.service

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MediaPlaybackManifestTest {
    @Test
    fun mediaButtonReceiverHasMatchingServiceIntentFilter() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val hasMediaButtonReceiver = document
            .getElementsByTagName("receiver")
            .asElements()
            .any { receiver ->
                receiver.androidName == "androidx.media.session.MediaButtonReceiver" &&
                    receiver.hasAction("android.intent.action.MEDIA_BUTTON")
            }

        val hasMatchingService = document
            .getElementsByTagName("service")
            .asElements()
            .any { service ->
                service.androidName == ".service.MediaPlaybackService" &&
                    service.hasAction("android.intent.action.MEDIA_BUTTON")
            }

        assertTrue("MediaButtonReceiver should be declared", hasMediaButtonReceiver)
        assertTrue(
            "MediaPlaybackService must handle MEDIA_BUTTON so MediaButtonReceiver can resolve it",
            hasMatchingService,
        )
    }

    private fun Element.hasAction(name: String): Boolean =
        getElementsByTagName("action")
            .asElements()
            .any { it.androidName == name }

    private val Element.androidName: String?
        get() = getAttribute("android:name").takeIf { it.isNotBlank() }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
