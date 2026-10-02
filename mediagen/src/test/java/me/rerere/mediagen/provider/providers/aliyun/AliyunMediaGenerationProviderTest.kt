package me.rerere.mediagen.provider.providers.aliyun

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AliyunMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val client = OkHttpClient()
    private val wan = MediaGenerationModel(modelId = "wan2.7-image-pro", kind = MediaKind.IMAGE)

    private fun parse(json: String): JsonObject = mediaGenerationJson.parseToJsonElement(json).jsonObject

    @Test
    fun aliyunMapsMessagesAndParameters() {
        val local = tempFolder.newFile("ref.jpg").apply { writeText("jpg") }

        val body = AliyunImageGeneration(client).buildBody(
            model = wan,
            request = MediaGenerationRequest(
                prompt = "把背景换成雪山",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/a.png"),
                    MediaGenerationInput.Image(local.absolutePath),
                ),
                count = 2,
                resolution = "1536x1024",
                watermark = false,
                seed = 42,
                promptEnhancement = true,
                extraParameters = buildJsonObject {
                    put("n", 9)
                    put("negative_prompt", "模糊")
                },
            ),
        )

        assertEquals(JsonPrimitive("wan2.7-image-pro"), body["model"])
        val message = body["input"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("user"), message["role"])
        val content = message["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("把背景换成雪山"), content[0]["text"])
        assertEquals(JsonPrimitive("https://example.com/a.png"), content[1]["image"])
        assertEquals(JsonPrimitive("data:image/jpeg;base64,anBn"), content[2]["image"])
        val parameters = body["parameters"]!!.jsonObject
        assertEquals(JsonPrimitive("1536*1024"), parameters["size"])
        assertEquals(JsonPrimitive(2), parameters["n"])
        assertEquals(JsonPrimitive(false), parameters["watermark"])
        assertEquals(JsonPrimitive(42L), parameters["seed"])
        assertEquals(JsonPrimitive(true), parameters["prompt_extend"])
        assertEquals(JsonPrimitive("模糊"), parameters["negative_prompt"])
    }

    @Test
    fun aliyunKeepsResolutionTiersAndRejectsUnsupportedFields() {
        val generation = AliyunImageGeneration(client)

        val body = generation.buildBody(wan, MediaGenerationRequest(prompt = "cat", resolution = "2K"))
        assertEquals(JsonPrimitive("2K"), body["parameters"]!!.jsonObject["size"])

        assertThrows(IllegalArgumentException::class.java) {
            generation.buildBody(wan, MediaGenerationRequest(prompt = "cat", aspectRatio = "16:9"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            generation.buildBody(wan, MediaGenerationRequest(inputs = listOf(MediaGenerationInput.Image("https://e.com/a.png"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            generation.buildBody(
                wan,
                MediaGenerationRequest(prompt = "cat", inputs = listOf(MediaGenerationInput.Audio("https://e.com/a.mp3"))),
            )
        }
    }

    @Test
    fun aliyunParsesWanAndQwenResponses() {
        val generation = AliyunImageGeneration(client)

        val wanTask = generation.parseTask(
            parse(
                """
                {
                  "output": {
                    "choices": [
                      {"finish_reason": "stop", "message": {"role": "assistant", "content": [
                        {"type": "text", "text": "说明"},
                        {"type": "image", "image": "https://oss.example.com/a.png?Expires=1"}
                      ]}},
                      {"message": {"content": [{"type": "image", "image": "https://oss.example.com/b.jpg?Expires=1"}]}}
                    ],
                    "finished": true
                  },
                  "usage": {"image_count": 2, "size": "1488*704", "total_tokens": 10869},
                  "request_id": "71dfc3c6"
                }
                """.trimIndent()
            ),
            fallbackModel = "wan2.7-image-pro",
        )
        assertEquals(MediaGenerationStatus.SUCCEEDED, wanTask.status)
        assertEquals("wan2.7-image-pro", wanTask.model)
        assertEquals(10869L, wanTask.usage?.totalTokens)
        assertEquals(
            listOf("https://oss.example.com/a.png?Expires=1", "https://oss.example.com/b.jpg?Expires=1"),
            wanTask.outputs.map { it.url },
        )
        assertEquals(listOf("image/png", "image/jpeg"), wanTask.outputs.map { it.mimeType })
        assertEquals("1488x704", wanTask.outputs.first().resolution)

        val qwenTask = generation.parseTask(
            parse(
                """
                {
                  "output": {"choices": [{"message": {"content": [{"image": "https://oss.example.com/c.png"}]}}]},
                  "usage": {"height": 2048, "image_count": 1, "width": 1024}
                }
                """.trimIndent()
            ),
        )
        assertEquals("1024x2048", qwenTask.outputs.single().resolution)

        val empty = generation.parseTask(parse("""{"output":{"choices":[]},"request_id":"r1"}"""))
        assertEquals(MediaGenerationStatus.FAILED, empty.status)
        assertEquals("Aliyun returned no image", empty.error?.message)
    }

    @Test
    fun aliyunRoutesImageAndVideoModelsToDifferentEndpoints() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            if (request.url.encodedPath.endsWith("/multimodal-generation/generation")) {
                jsonResponse(
                    request,
                    """{"output":{"choices":[{"message":{"content":[{"image":"https://oss.example.com/a.png"}]}}]}}""",
                )
            } else {
                jsonResponse(request, """{"output":{"task_id":"t1","task_status":"PENDING"}}""")
            }
        })
        // 不含占位符的地址（如旧的 dashscope 域名）原样使用，不需要 workspaceId
        val setting = MediaGenerationProviderSetting.Aliyun(apiKey = "key", baseUrl = "https://dash.example.com/api/v1")
        val video = setting.models.single { it.kind == MediaKind.VIDEO }

        val image = manager.generate(setting, wan, MediaGenerationRequest(prompt = "cat")).toList().single()
        assertEquals(MediaGenerationStatus.SUCCEEDED, image.status)
        assertEquals("aliyun", image.provider)
        assertTrue(manager.query(setting, wan, image.id).exceptionOrNull() is UnsupportedOperationException)

        val clip = manager.create(setting, video, MediaGenerationRequest(prompt = "cat")).getOrThrow()
        assertEquals(MediaGenerationStatus.QUEUED, clip.status)

        val (imageRequest, videoRequest) = requests
        assertEquals(
            "https://dash.example.com/api/v1/services/aigc/multimodal-generation/generation",
            imageRequest.url.toString(),
        )
        assertEquals("Bearer key", imageRequest.header("Authorization"))
        // 图像走同步接口，不带异步头
        assertNull(imageRequest.header("X-DashScope-Async"))
        assertEquals(
            "https://dash.example.com/api/v1/services/aigc/video-generation/video-synthesis",
            videoRequest.url.toString(),
        )
        assertEquals("enable", videoRequest.header("X-DashScope-Async"))
    }

    @Test
    fun aliyunCallsTheWorkspaceDomain() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            if (request.method == "GET") {
                jsonResponse(request, """{"output":{"task_id":"t1","task_status":"RUNNING"}}""")
            } else if (request.url.encodedPath.endsWith("/video-synthesis")) {
                jsonResponse(request, """{"output":{"task_id":"t1","task_status":"PENDING"}}""")
            } else {
                jsonResponse(
                    request,
                    """{"output":{"choices":[{"message":{"content":[{"image":"https://oss.example.com/a.png"}]}}]}}""",
                )
            }
        })
        val setting = MediaGenerationProviderSetting.Aliyun(apiKey = "key", workspaceId = " llm-abc ")
        val video = setting.models.single { it.kind == MediaKind.VIDEO }

        manager.create(setting, wan, MediaGenerationRequest(prompt = "cat")).getOrThrow()
        manager.create(setting, video, MediaGenerationRequest(prompt = "cat")).getOrThrow()
        manager.query(setting, video, "t1").getOrThrow()

        val base = "https://llm-abc.cn-beijing.maas.aliyuncs.com/api/v1"
        assertEquals(
            listOf(
                "$base/services/aigc/multimodal-generation/generation",
                "$base/services/aigc/video-generation/video-synthesis",
                "$base/tasks/t1",
            ),
            requests.map { it.url.toString() },
        )

        // 换地域只改 baseUrl 里的地域段
        requests.clear()
        val singapore = setting.copy(baseUrl = "https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api/v1/")
        manager.create(singapore, wan, MediaGenerationRequest(prompt = "cat")).getOrThrow()
        assertEquals("llm-abc.ap-southeast-1.maas.aliyuncs.com", requests.single().url.host)
    }

    @Test
    fun aliyunRequiresWorkspaceIdForTheWorkspaceDomain() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("missing workspaceId must not hit the network") })
        val setting = MediaGenerationProviderSetting.Aliyun(apiKey = "key")
        val video = setting.models.single { it.kind == MediaKind.VIDEO }

        val request = MediaGenerationRequest(prompt = "cat")
        assertTrue(manager.create(setting, wan, request).exceptionOrNull() is IllegalArgumentException)
        assertTrue(manager.create(setting, video, request).exceptionOrNull() is IllegalArgumentException)
        assertTrue(manager.query(setting, video, "t1").exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aliyunApiErrorIsSurfacedWithStatusAndCode() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"request_id":"r1","code":"InvalidParameter","message":"n must be 1"}""", code = 400)
        })

        val error = manager.create(
            MediaGenerationProviderSetting.Aliyun(apiKey = "key", workspaceId = "llm-abc"),
            wan,
            MediaGenerationRequest(prompt = "cat"),
        ).exceptionOrNull() as MediaGenerationApiException
        assertEquals(400, error.statusCode)
        assertEquals("InvalidParameter", error.code)
        assertEquals("n must be 1", error.message)
    }

    @Test
    fun aliyunMapsWan30MediaAndParameters() {
        val body = AliyunVideoGeneration(client).buildCreateBody(
            model = MediaGenerationModel(modelId = "wan3.0-video", kind = MediaKind.VIDEO),
            request = MediaGenerationRequest(
                prompt = "产品广告",
                inputs = listOf(
                    MediaGenerationInput.Image(
                        "https://example.com/start.png",
                        ImageRole.FIRST_FRAME
                    ),
                    MediaGenerationInput.Document("https://example.com/brief.pdf"),
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
}
