package me.rerere.rikkahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteActivityShareHandlerRequestTest {
    @Test
    fun resolve_share_handler_request_supports_process_text() {
        val request = resolveShareHandlerRequest(
            action = "android.intent.action.PROCESS_TEXT",
            sharedText = null,
            sharedImageUri = "file:///tmp/ignored.png",
            processedText = "selected text",
        )

        assertEquals(ShareHandlerRequest(text = "selected text"), request)
    }

    @Test
    fun resolve_share_handler_request_supports_send() {
        val request = resolveShareHandlerRequest(
            action = "android.intent.action.SEND",
            sharedText = "shared text",
            sharedImageUri = "file:///tmp/image.png",
            processedText = null,
        )

        assertEquals(
            ShareHandlerRequest(
                text = "shared text",
                streamUri = "file:///tmp/image.png",
            ),
            request,
        )
    }

    @Test
    fun resolve_share_handler_request_ignores_other_actions() {
        val request = resolveShareHandlerRequest(
            action = "android.intent.action.VIEW",
            sharedText = "shared text",
            sharedImageUri = "file:///tmp/image.png",
            processedText = "selected text",
        )

        assertNull(request)
    }
}
