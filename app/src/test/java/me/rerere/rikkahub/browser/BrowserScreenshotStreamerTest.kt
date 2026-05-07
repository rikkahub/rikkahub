package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pass 3: tests for the [BrowserScreenshotStreamer] interface contract.
 *
 *  - The default [BrowserScreenshotStreamer.NoOp] must be safe to call from any
 *    coroutine without throwing — it's the fallback the controller uses when Koin has
 *    no implementation registered (JVM tests, headless build).
 *  - A fake recording-streamer pattern verifies the interface shape and provides a
 *    template for plugging into [BrowserController.streamScreenshotIfHeadless] testing
 *    via the device-walk path (where Koin DI is wired).
 */
class BrowserScreenshotStreamerTest {

    @Test fun `NoOp send swallows the call`() = runBlocking {
        // No assertion target other than "doesn't throw". This is load-bearing because
        // the controller falls back to NoOp when Koin isn't registered, and a throw here
        // would propagate through streamScreenshotIfHeadless and crash the FGS.
        BrowserScreenshotStreamer.NoOp.send(
            callerConvId = "any-conv-id",
            screenshotPath = "/dev/null",
            actionLabel = "any action",
            currentUrl = null,
        )
        BrowserScreenshotStreamer.NoOp.send(
            callerConvId = "another",
            screenshotPath = "/tmp/whatever",
            actionLabel = "click",
            currentUrl = "https://example.com",
        )
    }

    @Test fun `recording streamer captures call args verbatim`() = runBlocking {
        val recorded = CopyOnWriteArrayList<RecordedCall>()
        val streamer = object : BrowserScreenshotStreamer {
            override suspend fun send(
                callerConvId: String,
                screenshotPath: String,
                actionLabel: String,
                currentUrl: String?,
            ) {
                recorded.add(RecordedCall(callerConvId, screenshotPath, actionLabel, currentUrl))
            }
        }
        streamer.send("conv-1", "/path/a.png", "Clicked Sign In", "https://github.com")
        streamer.send("conv-2", "/path/b.png", "Opened https://x.com", null)

        assertEquals(2, recorded.size)
        assertEquals("conv-1", recorded[0].callerConvId)
        assertEquals("/path/a.png", recorded[0].screenshotPath)
        assertEquals("Clicked Sign In", recorded[0].actionLabel)
        assertEquals("https://github.com", recorded[0].currentUrl)
        assertEquals("conv-2", recorded[1].callerConvId)
        assertEquals("/path/b.png", recorded[1].screenshotPath)
        assertEquals("Opened https://x.com", recorded[1].actionLabel)
        assertTrue("currentUrl must be nullable", recorded[1].currentUrl == null)
    }

    private data class RecordedCall(
        val callerConvId: String,
        val screenshotPath: String,
        val actionLabel: String,
        val currentUrl: String?,
    )
}
