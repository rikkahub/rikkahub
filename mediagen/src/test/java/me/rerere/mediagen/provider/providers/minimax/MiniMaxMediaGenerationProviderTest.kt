package me.rerere.mediagen.provider.providers.minimax

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class MiniMaxMediaGenerationProviderTest {
    private val client = OkHttpClient()

    @Test
    fun minimaxParsesSucceededTask() {
        val root = mediaGenerationJson.parseToJsonElement(
            """
            {
              "task": {
                "id": "424010985738629",
                "model": "MiniMax-H3",
                "status": "succeeded",
                "created_at": 1785125529,
                "updated_at": 1785125946,
                "content": {"url": "https://example.com/video.mp4"},
                "resolution": "2K",
                "duration": 5,
                "ratio": "16:9",
                "usage": {"total_seconds": 5, "output_seconds": 5, "total_tokens": 273890}
              }
            }
            """.trimIndent()
        ).jsonObject

        val task = MiniMaxMediaGenerationProvider(client).parseTask(root)
        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertTrue(task.isTerminal)
        assertEquals("https://example.com/video.mp4", task.outputs.single().url)
        assertEquals(273890L, task.usage?.totalTokens)
        assertFalse(task.outputs.isEmpty())
    }

    @Test
    fun generatePollsAsyncProviderUntilTerminal() = runBlocking {
        var queries = 0
        val manager = MediaGenerationManager(fakeClient { request ->
            if (request.method == "POST") {
                jsonResponse(request, """{"task_id":"t1"}""")
            } else {
                val status = if (++queries < 2) "running" else "succeeded"
                jsonResponse(request, """{"task":{"id":"t1","status":"$status"}}""")
            }
        })
        val setting = MediaGenerationProviderSetting.MiniMax(apiKey = "key")
        val model = setting.models.single()

        val statuses = manager
            .generate(setting, model, MediaGenerationRequest(prompt = "跑车"), interval = 1.milliseconds)
            .toList()
            .map { it.status }

        assertEquals(MediaKind.VIDEO, model.kind)
        assertEquals(
            listOf(MediaGenerationStatus.QUEUED, MediaGenerationStatus.RUNNING, MediaGenerationStatus.SUCCEEDED),
            statuses,
        )
    }
}
