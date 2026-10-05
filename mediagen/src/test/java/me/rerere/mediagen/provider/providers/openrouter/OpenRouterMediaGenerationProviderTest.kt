package me.rerere.mediagen.provider.providers.openrouter

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
import kotlin.time.Duration.Companion.milliseconds

class OpenRouterMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val client = OkHttpClient()
    private val setting = MediaGenerationProviderSetting.OpenRouter(apiKey = "key")
    private val imageModel = MediaGenerationModel(modelId = "openai/gpt-image-2", kind = MediaKind.IMAGE)
    private val videoModel = MediaGenerationModel(modelId = "google/veo-3.1", kind = MediaKind.VIDEO)

    private fun parse(json: String): JsonObject = mediaGenerationJson.parseToJsonElement(json).jsonObject

    @Test
    fun openRouterMapsImageFieldsAndReferences() {
        val local = tempFolder.newFile("ref.png").apply { writeText("png") }
        val generation = OpenRouterImageGeneration(client)

        val body = generation.buildBody(
            model = imageModel,
            request = MediaGenerationRequest(
                prompt = "一只猫",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/a.png"),
                    MediaGenerationInput.Image(local.absolutePath),
                ),
                count = 2,
                resolution = "2K",
                aspectRatio = "16:9",
                seed = 7,
                extraParameters = buildJsonObject {
                    put("n", 9)
                    put("quality", "high")
                },
            ),
        )

        assertEquals(JsonPrimitive("openai/gpt-image-2"), body["model"])
        assertEquals(JsonPrimitive("一只猫"), body["prompt"])
        assertEquals(JsonPrimitive(2), body["n"])
        assertEquals(JsonPrimitive("2K"), body["resolution"])
        assertNull(body["size"])
        assertEquals(JsonPrimitive("16:9"), body["aspect_ratio"])
        assertEquals(JsonPrimitive(7), body["seed"])
        assertEquals(JsonPrimitive("high"), body["quality"])
        // 公网地址原样下发，本地文件编码成 data URI
        val references = body["input_references"]!!.jsonArray.map { it.jsonObject }
        assertTrue(references.all { it["type"] == JsonPrimitive("image_url") })
        assertEquals(
            listOf("https://example.com/a.png", "data:image/png;base64,cG5n"),
            references.map { it["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content },
        )

        // 像素尺寸走 size
        val sized = generation.buildBody(imageModel, MediaGenerationRequest(prompt = "cat", resolution = "1536x1024"))
        assertEquals(JsonPrimitive("1536x1024"), sized["size"])
        assertNull(sized["resolution"])
        assertNull(sized["input_references"])
    }

    @Test
    fun openRouterRejectsFieldsTheImageApiDoesNotExpose() {
        val generation = OpenRouterImageGeneration(client)
        fun body(request: MediaGenerationRequest) = generation.buildBody(imageModel, request)
        val image = MediaGenerationInput.Image("https://example.com/a.png")

        assertThrows(IllegalArgumentException::class.java) { body(MediaGenerationRequest(inputs = listOf(image))) }
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat", watermark = false))
        }
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat", inputs = listOf(MediaGenerationInput.Video("https://e.com/a.mp4"))))
        }
    }

    @Test
    fun openRouterParsesInlineImages() {
        val generation = OpenRouterImageGeneration(client)

        val task = generation.parseTask(
            parse(
                """
                {
                  "created": 1748372400,
                  "data": [
                    {"b64_json": "aGVsbG8=", "media_type": "image/webp"},
                    {"b64_json": "aGVsbG8="}
                  ],
                  "usage": {"prompt_tokens": 0, "completion_tokens": 4175, "total_tokens": 4175, "cost": 0.04}
                }
                """.trimIndent()
            ),
            fallbackModel = "openai/gpt-image-2",
        )

        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertEquals("openrouter", task.provider)
        assertEquals("openai/gpt-image-2", task.model)
        assertEquals(1748372400L, task.createdAtEpochSeconds)
        assertEquals(4175L, task.usage?.totalTokens)
        assertEquals(listOf("image/webp", "image/png"), task.outputs.map { it.mimeType })
        assertEquals("hello", task.outputs.first().data!!.decodeToString())
        // 费用留在 metadata 里，base64 不进 metadata
        assertEquals(JsonPrimitive(0.04), task.metadata["usage"]!!.jsonObject["cost"])
        assertFalse("data" in task.metadata)

        val empty = generation.parseTask(parse("""{"created":1,"data":[]}"""))
        assertEquals(MediaGenerationStatus.FAILED, empty.status)
        assertEquals("OpenRouter returned no image", empty.error?.message)
    }

    @Test
    fun openRouterSplitsFramesAndReferences() {
        val generation = OpenRouterVideoGeneration(client)

        val body = generation.buildCreateBody(
            model = videoModel,
            request = MediaGenerationRequest(
                prompt = "镜头缓慢推进",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/first.png", ImageRole.FIRST_FRAME),
                    MediaGenerationInput.Image("https://example.com/last.png", ImageRole.LAST_FRAME),
                    MediaGenerationInput.Image("https://example.com/ref.png"),
                    MediaGenerationInput.Video("https://example.com/ref.mp4"),
                    MediaGenerationInput.Audio("https://example.com/ref.mp3"),
                ),
                resolution = "1080p",
                aspectRatio = "16:9",
                durationSeconds = 8,
                generateAudio = false,
                seed = 3,
                callbackUrl = "https://example.com/hook",
            ),
        )

        assertEquals(JsonPrimitive("google/veo-3.1"), body["model"])
        assertEquals(JsonPrimitive("镜头缓慢推进"), body["prompt"])
        assertEquals(JsonPrimitive("1080p"), body["resolution"])
        assertEquals(JsonPrimitive("16:9"), body["aspect_ratio"])
        assertEquals(JsonPrimitive(8), body["duration"])
        assertEquals(JsonPrimitive(false), body["generate_audio"])
        assertEquals(JsonPrimitive(3), body["seed"])
        assertEquals(JsonPrimitive("https://example.com/hook"), body["callback_url"])

        val frames = body["frame_images"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("first_frame", "last_frame"), frames.map { it["frame_type"]!!.jsonPrimitive.content })
        assertEquals(
            "https://example.com/first.png",
            frames.first()["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        val references = body["input_references"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("image_url", "video_url", "audio_url"),
            references.map { it["type"]!!.jsonPrimitive.content },
        )
        assertFalse(references.any { "frame_type" in it })
        assertEquals(
            "https://example.com/ref.mp4",
            references[1]["video_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )

        // 只有图片也能提交；像素尺寸走 size
        val imageOnly = generation.buildCreateBody(
            videoModel,
            MediaGenerationRequest(
                inputs = listOf(MediaGenerationInput.Image("https://example.com/first.png", ImageRole.FIRST_FRAME)),
                resolution = "1280x720",
            ),
        )
        assertNull(imageOnly["prompt"])
        assertNull(imageOnly["input_references"])
        assertEquals(JsonPrimitive("1280x720"), imageOnly["size"])
    }

    @Test
    fun openRouterParsesVideoJobs() {
        val generation = OpenRouterVideoGeneration(client)

        val pending = generation.parseTask(
            setting,
            parse("""{"id":"gen-vid-1","polling_url":"/api/v1/videos/gen-vid-1","status":"pending"}"""),
            fallbackModel = "google/veo-3.1",
        )
        assertEquals("gen-vid-1", pending.id)
        assertEquals(MediaGenerationStatus.QUEUED, pending.status)
        assertEquals("google/veo-3.1", pending.model)
        assertFalse(pending.isTerminal)

        val completed = generation.parseTask(
            setting,
            parse(
                """
                {
                  "id": "gen-vid-1",
                  "status": "completed",
                  "unsigned_urls": [
                    "https://openrouter.ai/api/v1/videos/gen-vid-1/content?index=0",
                    "/api/v1/videos/gen-vid-1/content?index=1"
                  ],
                  "usage": {"cost": 0.25, "is_byok": false}
                }
                """.trimIndent()
            ),
        )
        assertEquals(MediaGenerationStatus.SUCCEEDED, completed.status)
        assertEquals(
            listOf(
                "https://openrouter.ai/api/v1/videos/gen-vid-1/content?index=0",
                "https://openrouter.ai/api/v1/videos/gen-vid-1/content?index=1",
            ),
            completed.outputs.map { it.url },
        )
        assertTrue(completed.outputs.all { it.mimeType == "video/mp4" })
        assertEquals(JsonPrimitive(0.25), completed.metadata["usage"]!!.jsonObject["cost"])

        val failed = generation.parseTask(setting, parse("""{"id":"j","status":"failed","error":"Content policy violation"}"""))
        assertEquals(MediaGenerationStatus.FAILED, failed.status)
        assertEquals("Content policy violation", failed.error?.message)
        assertTrue(failed.outputs.isEmpty())

        val expired = generation.parseTask(setting, parse("""{"id":"j","status":"expired","error":{"message":"too late"}}"""))
        assertEquals(MediaGenerationStatus.EXPIRED, expired.status)
        assertEquals("too late", expired.error?.message)
        assertEquals(
            MediaGenerationStatus.RUNNING,
            generation.parseTask(setting, parse("""{"id":"j","status":"in_progress"}""")).status,
        )
    }

    @Test
    fun openRouterRoutesImageAndVideoModelsToDifferentEndpoints() = runBlocking {
        val requests = mutableListOf<Request>()
        var queries = 0
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            when {
                request.url.encodedPath.endsWith("/images") ->
                    jsonResponse(request, """{"created":1,"data":[{"b64_json":"aGVsbG8=","media_type":"image/png"}]}""")

                request.method == "POST" ->
                    jsonResponse(request, """{"id":"job-1","polling_url":"/api/v1/videos/job-1","status":"pending"}""", 202)

                ++queries < 2 -> jsonResponse(request, """{"id":"job-1","status":"in_progress"}""")

                else -> jsonResponse(
                    request,
                    """{"id":"job-1","status":"completed","unsigned_urls":["https://or.example.com/api/v1/videos/job-1/content?index=0"]}""",
                )
            }
        })
        val setting = MediaGenerationProviderSetting.OpenRouter(apiKey = "key", baseUrl = "https://or.example.com/api/v1/")

        assertEquals(setOf(MediaKind.IMAGE, MediaKind.VIDEO), setting.supportedKinds)
        val image = manager.generate(setting, imageModel, MediaGenerationRequest(prompt = "cat")).toList().single()
        assertEquals(MediaGenerationStatus.SUCCEEDED, image.status)
        assertEquals("hello", image.outputs.single().data!!.decodeToString())
        assertTrue(manager.query(setting, imageModel, image.id).exceptionOrNull() is UnsupportedOperationException)

        val statuses = manager
            .generate(setting, videoModel, MediaGenerationRequest(prompt = "cat"), interval = 1.milliseconds)
            .toList()
        assertEquals(
            listOf(MediaGenerationStatus.QUEUED, MediaGenerationStatus.RUNNING, MediaGenerationStatus.SUCCEEDED),
            statuses.map { it.status },
        )

        assertEquals(
            listOf(
                "POST https://or.example.com/api/v1/images",
                "POST https://or.example.com/api/v1/videos",
                "GET https://or.example.com/api/v1/videos/job-1",
                "GET https://or.example.com/api/v1/videos/job-1",
            ),
            requests.map { "${it.method} ${it.url}" },
        )
        assertTrue(requests.all { it.header("Authorization") == "Bearer key" })
        assertEquals("RikkaHub", requests.first().header("X-Title"))
        assertEquals(JsonPrimitive("google/veo-3.1"), parse(requests[1].bodyAsString())["model"])
    }

    @Test
    fun openRouterSurfacesApiErrors() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { request ->
            jsonResponse(request, """{"error":{"code":402,"message":"Insufficient credits"}}""", 402)
        })

        val error = manager.create(setting, videoModel, MediaGenerationRequest(prompt = "cat")).exceptionOrNull()

        assertTrue(error is MediaGenerationApiException)
        error as MediaGenerationApiException
        assertEquals(402, error.statusCode)
        assertEquals("402", error.code)
        assertEquals("Insufficient credits", error.message)
    }

    @Test
    fun downloadHeadersCarryTheKeyOnlyToTheApiHost() {
        val authorized = mapOf("Authorization" to "Bearer key")

        assertEquals(authorized, setting.downloadHeaders("https://openrouter.ai/api/v1/videos/job-1/content?index=0"))
        assertTrue(setting.downloadHeaders("https://storage.example.com/video.mp4").isEmpty())
        assertTrue(setting.downloadHeaders("http://openrouter.ai/api/v1/videos/job-1/content").isEmpty())
        assertTrue(setting.downloadHeaders("not a url").isEmpty())
        // 其它厂商的结果地址自带签名
        assertTrue(
            MediaGenerationProviderSetting.Volcengine(apiKey = "key")
                .downloadHeaders("https://ark.cn-beijing.volces.com/api/v3/a.mp4")
                .isEmpty()
        )
    }
}
