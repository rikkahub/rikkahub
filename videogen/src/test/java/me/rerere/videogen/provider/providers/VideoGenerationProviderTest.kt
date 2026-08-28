package me.rerere.videogen.provider.providers

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.videogen.model.ImageRole
import me.rerere.videogen.model.VideoGenerationInput
import me.rerere.videogen.model.VideoGenerationRequest
import me.rerere.videogen.model.VideoGenerationStatus
import me.rerere.videogen.provider.VideoGenerationProviderSetting
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGenerationProviderTest {
    private val client = OkHttpClient()

    @Test
    fun aliyunMapsWan30MediaAndParameters() {
        val body = AliyunVideoGenerationProvider(client).buildCreateBody(
            setting = VideoGenerationProviderSetting.Aliyun(model = "wan3.0-video"),
            request = VideoGenerationRequest(
                prompt = "产品广告",
                inputs = listOf(
                    VideoGenerationInput.Image(
                        "https://example.com/start.png",
                        ImageRole.FIRST_FRAME
                    ),
                    VideoGenerationInput.Document("https://example.com/brief.pdf"),
                ),
                resolution = "1080P",
                durationSeconds = 10,
                extraParameters = buildJsonObject {
                    put("duration", 99)
                    put("custom", true)
                },
            ),
        )

        val input = body["input"]!!.jsonObject
        val media = input["media"]!!.jsonArray
        val parameters = body["parameters"]!!.jsonObject
        assertEquals("first_frame", media[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("file", media[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(10, parameters["duration"]!!.jsonPrimitive.content.toInt())
        assertTrue(parameters["custom"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun volcengineMapsReferenceContent() {
        val body = VolcengineVideoGenerationProvider(client).buildCreateBody(
            setting = VideoGenerationProviderSetting.Volcengine(model = "seedance-test"),
            request = VideoGenerationRequest(
                prompt = "参考图和音频生成视频",
                inputs = listOf(
                    VideoGenerationInput.Image("https://example.com/ref.png"),
                    VideoGenerationInput.Audio("https://example.com/ref.mp3"),
                ),
                generateAudio = true,
            ),
        )

        val content = body["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", content[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("reference_audio", content[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), body["generate_audio"])
    }

    @Test
    fun minimaxParsesSucceededTask() {
        val root = videoGenerationJson.parseToJsonElement(
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

        val task = MiniMaxVideoGenerationProvider(client).parseTask(root)
        assertEquals(VideoGenerationStatus.SUCCEEDED, task.status)
        assertTrue(task.isTerminal)
        assertEquals("https://example.com/video.mp4", task.outputs.single().url)
        assertEquals(273890L, task.usage?.totalTokens)
        assertFalse(task.outputs.isEmpty())
    }
}
