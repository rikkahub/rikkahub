package me.rerere.mediagen.provider.providers.volcengine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bodyAsString
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VolcengineMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val client = OkHttpClient()
    private val seedream = MediaGenerationModel(modelId = "doubao-seedream-5-0-pro-260628", kind = MediaKind.IMAGE)

    private fun parse(json: String): JsonObject = mediaGenerationJson.parseToJsonElement(json).jsonObject

    @Test
    fun volcengineMapsPromptSizeAndReferenceImages() {
        val local = tempFolder.newFile("ref.PNG").apply { writeText("png") }
        val generation = VolcengineImageGeneration(client)

        val textOnly = generation.buildBody(
            model = seedream,
            request = MediaGenerationRequest(
                prompt = "一只猫",
                count = 1,
                resolution = "2K",
                watermark = false,
                extraParameters = buildJsonObject {
                    put("size", "1K")
                    put("output_format", "png")
                },
            ),
        )
        assertEquals(JsonPrimitive("doubao-seedream-5-0-pro-260628"), textOnly["model"])
        assertEquals(JsonPrimitive("一只猫"), textOnly["prompt"])
        assertEquals(JsonPrimitive("2K"), textOnly["size"])
        assertEquals(JsonPrimitive(false), textOnly["watermark"])
        assertEquals(JsonPrimitive("png"), textOnly["output_format"])
        assertNull(textOnly["image"])

        // 单张参考图是字符串，本地文件编码成 data URI
        val single = generation.buildBody(
            seedream,
            MediaGenerationRequest(prompt = "换背景", inputs = listOf(MediaGenerationInput.Image(local.absolutePath))),
        )
        assertEquals(JsonPrimitive("data:image/png;base64,cG5n"), single["image"])

        // 多张参考图是数组，公网地址原样下发
        val multiple = generation.buildBody(
            seedream,
            MediaGenerationRequest(
                prompt = "融合",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/a.png"),
                    MediaGenerationInput.Image("file://${local.absolutePath}"),
                ),
            ),
        )
        assertEquals(
            listOf("https://example.com/a.png", "data:image/png;base64,cG5n"),
            multiple["image"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun volcengineRejectsFieldsTheImageApiDoesNotExpose() {
        val generation = VolcengineImageGeneration(client)
        fun body(request: MediaGenerationRequest) = generation.buildBody(seedream, request)

        assertThrows(IllegalArgumentException::class.java) { body(MediaGenerationRequest(prompt = "cat", count = 4)) }
        assertThrows(IllegalArgumentException::class.java) { body(MediaGenerationRequest(prompt = "cat", seed = 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat", aspectRatio = "16:9"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat", inputs = listOf(MediaGenerationInput.Video("https://e.com/a.mp4"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat", inputs = listOf(MediaGenerationInput.Image("/not/exist.png"))))
        }
    }

    @Test
    fun volcengineParsesImagesAndSkipsFailedItems() {
        val task = VolcengineImageGeneration(client).parseTask(
            parse(
                """
                {
                  "model": "doubao-seedream-5-0-lite-260128",
                  "created": 1785125529,
                  "data": [
                    {"url": "https://tos.example.com/seedream/a.jpeg?X-Tos-Expires=86400", "size": "2048x2048"},
                    {"error": {"code": "OutputImageSensitiveContentDetected", "message": "blocked"}},
                    {"b64_json": "aGVsbG8=", "size": "1024x1024", "output_format": "png"}
                  ],
                  "usage": {"generated_images": 2, "output_tokens": 20480, "total_tokens": 20480}
                }
                """.trimIndent()
            ),
        )

        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertNull(task.error)
        assertEquals("doubao-seedream-5-0-lite-260128", task.model)
        assertEquals(1785125529L, task.createdAtEpochSeconds)
        assertEquals(20480L, task.usage?.totalTokens)
        assertEquals(2, task.outputs.size)
        val (first, second) = task.outputs
        assertEquals("https://tos.example.com/seedream/a.jpeg?X-Tos-Expires=86400", first.url)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals("2048x2048", first.resolution)
        assertEquals("hello", second.data!!.decodeToString())
        assertEquals("image/png", second.mimeType)
        // 逐图信息保留，但 base64 不进 metadata
        val items = task.metadata["data"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, items.size)
        assertFalse(items.any { "b64_json" in it })
    }

    @Test
    fun volcengineReportsFailedTaskWhenNoImageIsReturned() {
        val generation = VolcengineImageGeneration(client)

        val topLevel = generation.parseTask(parse("""{"error":{"code":"InternalServiceError","message":"boom"}}"""))
        assertEquals(MediaGenerationStatus.FAILED, topLevel.status)
        assertTrue(topLevel.isTerminal)
        assertEquals("InternalServiceError", topLevel.error?.code)
        assertEquals("boom", topLevel.error?.message)

        val perImage = generation.parseTask(parse("""{"data":[{"error":{"code":"Sensitive","message":"blocked"}}]}"""))
        assertEquals(MediaGenerationStatus.FAILED, perImage.status)
        assertEquals("Sensitive", perImage.error?.code)
    }

    @Test
    fun volcengineRoutesImageAndVideoModelsToDifferentEndpoints() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            if (request.url.encodedPath.endsWith("/images/generations")) {
                jsonResponse(request, """{"data":[{"url":"https://tos.example.com/a.png","size":"2048x2048"}]}""")
            } else {
                jsonResponse(request, """{"id":"cgt-1","status":"succeeded","content":{"video_url":"https://e.com/a.mp4"}}""")
            }
        })
        val setting = MediaGenerationProviderSetting.Volcengine(apiKey = "key", baseUrl = "https://ark.example.com/api/v3/")
        val video = MediaGenerationModel(modelId = "doubao-seedance-2-0-260128", kind = MediaKind.VIDEO)

        assertEquals(setOf(MediaKind.IMAGE, MediaKind.VIDEO), setting.supportedKinds)
        val image = manager.generate(setting, seedream, MediaGenerationRequest(prompt = "cat")).toList().single()
        assertEquals(MediaGenerationStatus.SUCCEEDED, image.status)
        assertEquals("volcengine", image.provider)
        assertEquals("image/png", image.outputs.single().mimeType)
        assertTrue(manager.query(setting, seedream, image.id).exceptionOrNull() is UnsupportedOperationException)

        val clip = manager.create(setting, video, MediaGenerationRequest(prompt = "cat")).getOrThrow()
        assertEquals("video/mp4", clip.outputs.single().mimeType)

        assertEquals(
            listOf(
                "https://ark.example.com/api/v3/images/generations",
                "https://ark.example.com/api/v3/contents/generations/tasks",
            ),
            requests.map { it.url.toString() },
        )
        assertEquals("Bearer key", requests.first().header("Authorization"))
        val sent = parse(requests.first().bodyAsString())
        assertEquals(JsonPrimitive("doubao-seedream-5-0-pro-260628"), sent["model"])
    }

    @Test
    fun volcengineMapsReferenceContent() {
        val body = VolcengineVideoGeneration(client).buildCreateBody(
            model = MediaGenerationModel(modelId = "seedance-test", kind = MediaKind.VIDEO),
            request = MediaGenerationRequest(
                prompt = "参考图和音频生成视频",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/ref.png"),
                    MediaGenerationInput.Audio("https://example.com/ref.mp3"),
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
}
