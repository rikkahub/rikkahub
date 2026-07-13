package me.rerere.rikkahub.data.ai

import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugHttpResponseEvidenceStoreTest {
    @After
    fun tearDown() {
        DebugHttpResponseEvidenceStore.clear()
    }

    @Test
    fun `records response evidence without query credentials or request content`() {
        val request = Request.Builder()
            .url("https://dev-remote-machine-1.tail83108.ts.net:8642/v1/chat/completions?api_key=secret")
            .header("Authorization", "Bearer device-key")
            .build()

        DebugHttpResponseEvidenceStore.record(request = request, responseCode = 200)

        val evidence = DebugHttpResponseEvidenceStore.snapshot().single()
        assertEquals("https://dev-remote-machine-1.tail83108.ts.net:8642", evidence.origin)
        assertEquals("/chat/completions", evidence.endpointPath)
        assertEquals("GET", evidence.method)
        assertEquals(200, evidence.responseCode)
        assertFalse(evidence.toString().contains("secret"))
        assertFalse(evidence.toString().contains("device-key"))
    }
}
