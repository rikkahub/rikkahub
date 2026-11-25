package me.rerere.mediagen.provider.providers.apimart

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import me.rerere.mediagen.provider.providers.bodyAsString
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds

class ApiMartMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val client = OkHttpClient()
    private val setting = MediaGenerationProviderSetting.ApiMart(apiKey = "key")
    private val imageModel = MediaGenerationModel(modelId = "gpt-image-2.6-flare", kind = MediaKind.IMAGE)
    private val videoModel = MediaGenerationModel(modelId = "MiniMax-H3", kind = MediaKind.VIDEO)

    private fun parse(json: String): JsonObject = mediaGenerationJson.parseToJsonElement(json).jsonObject

    @Test
    fun imageMapsTierRatioAndReferences() {
        val local = tempFolder.newFile("ref.png").apply { writeText("png") }

        val body = ApiMartImageGeneration(client).buildBody(
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
                extraParameters = buildJsonObject { put("quality", "high") },
            ),
        )

        assertEquals("gpt-image-2.6-flare", body["model"]!!.jsonPrimitive.content)
        assertEquals("2k", body["resolution"]!!.jsonPrimitive.content)
        assertEquals("16:9", body["size"]!!.jsonPrimitive.content)
        assertEquals(2, body["n"]!!.jsonPrimitive.content.toInt())
        assertEquals("high", body["quality"]!!.jsonPrimitive.content)
        val images = body["image_urls"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("https://example.com/a.png", images[0])
        assertTrue(images[1].startsWith("data:image/png;base64,"))
    }

    @Test
    fun imagePixelSizeGoesToSizeAndExcludesRatio() {
        val generation = ApiMartImageGeneration(client)

        val body = generation.buildBody(imageModel, MediaGenerationRequest(prompt = "cat", resolution = "1536X1024"))
        assertEquals("1536x1024", body["size"]!!.jsonPrimitive.content)
        assertFalse("resolution" in body)

        assertThrows(IllegalArgumentException::class.java) {
            generation.buildBody(
                imageModel,
                MediaGenerationRequest(prompt = "cat", resolution = "1536x1024", aspectRatio = "16:9"),
            )
        }
    }

    @Test
    fun videoMapsRolesAndReferenceMedia() {
        val body = ApiMartVideoGeneration(client).buildCreateBody(
            model = videoModel,
            request = MediaGenerationRequest(
                prompt = "角色说话",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/char.png"),
                    MediaGenerationInput.Video("https://example.com/motion.mp4"),
                    MediaGenerationInput.Audio("https://example.com/voice.mp3"),
                ),
                resolution = "2K",
                durationSeconds = 6,
                aspectRatio = "16:9",
                watermark = false,
                callbackUrl = "https://example.com/hook",
            ),
        )

        val image = body["image_with_roles"]!!.jsonArray.single().jsonObject
        assertEquals("https://example.com/char.png", image["url"]!!.jsonPrimitive.content)
        assertEquals("reference_image", image["role"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/motion.mp4", body["video_urls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("https://example.com/voice.mp3", body["audio_urls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("2K", body["resolution"]!!.jsonPrimitive.content)
        assertEquals(6, body["duration"]!!.jsonPrimitive.content.toInt())
        assertEquals("16:9", body["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("false", body["watermark"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/hook", body["webhook"]!!.jsonPrimitive.content)
    }

    @Test
    fun videoMapsFirstAndLastFrames() {
        val body = ApiMartVideoGeneration(client).buildCreateBody(
            model = videoModel,
            request = MediaGenerationRequest(
                prompt = "从清晨到日落",
                inputs = listOf(
                    MediaGenerationInput.Image("https://example.com/morning.png", role = ImageRole.FIRST_FRAME),
                    MediaGenerationInput.Image("https://example.com/sunset.png", role = ImageRole.LAST_FRAME),
                ),
            ),
        )

        assertEquals(
            listOf("first_frame", "last_frame"),
            body["image_with_roles"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertFalse("video_urls" in body)
    }

    @Test
    fun parsesSubmissionAndCompletedImageTask() {
        val submitted = ApiMartMediaGenerationProvider.parseSubmission(
            parse("""{"code":200,"data":[{"status":"submitted","task_id":"task_01"}]}"""),
            imageModel,
        )
        assertEquals("task_01", submitted.id)
        assertEquals(MediaGenerationStatus.QUEUED, submitted.status)
        assertFalse(submitted.isTerminal)

        val task = ApiMartMediaGenerationProvider.parseTask(
            parse(
                """
                {
                  "code": 200,
                  "data": {
                    "id": "task_01",
                    "status": "completed",
                    "cost": 0.15,
                    "progress": 100,
                    "result": {
                      "images": [
                        {"url": ["https://upload.apimart.ai/f/image/a.png", "https://upload.apimart.ai/f/image/b.webp"], "expires_at": 1763174708}
                      ]
                    },
                    "created": 1763088289,
                    "completed": 1763088308
                  }
                }
                """.trimIndent()
            )
        )
        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertEquals(listOf("image/png", "image/webp"), task.outputs.map { it.mimeType })
        assertEquals(1763088308L, task.updatedAtEpochSeconds)
    }

    @Test
    fun parsesVideoResultAndFailure() {
        val video = ApiMartMediaGenerationProvider.parseTask(
            parse(
                """{"data":{"id":"t","status":"completed","result":{"videos":[{"url":"https://cdn.example.com/v.mp4"}]}}}"""
            )
        )
        assertEquals("https://cdn.example.com/v.mp4", video.outputs.single().url)
        assertEquals("video/mp4", video.outputs.single().mimeType)

        val failed = ApiMartMediaGenerationProvider.parseTask(
            parse(
                """{"data":{"id":"t","status":"failed","error":{"code":422,"message":"内容安全审核未通过","type":"invalid_request_error"}}}"""
            )
        )
        assertEquals(MediaGenerationStatus.FAILED, failed.status)
        assertEquals("422", failed.error?.code)
        assertEquals("内容安全审核未通过", failed.error?.message)
    }

    @Test
    fun generatePollsImageTaskUntilTerminal() = runBlocking {
        val requests = mutableListOf<Request>()
        var queries = 0
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            if (request.method == "POST") {
                jsonResponse(request, """{"code":200,"data":[{"status":"submitted","task_id":"t1"}]}""")
            } else {
                val status = if (++queries < 2) "processing" else "completed"
                jsonResponse(
                    request,
                    """{"code":200,"data":{"id":"t1","status":"$status","result":{"images":[{"url":["https://upload.apimart.ai/a.png"]}]}}}""",
                )
            }
        })

        val tasks = manager
            .generate(setting, imageModel, MediaGenerationRequest(prompt = "cat"), interval = 1.milliseconds)
            .toList()

        assertEquals(
            listOf(MediaGenerationStatus.QUEUED, MediaGenerationStatus.RUNNING, MediaGenerationStatus.SUCCEEDED),
            tasks.map { it.status },
        )
        assertEquals("https://api.apimart.ai/v1/images/generations", requests.first().url.toString())
        assertTrue(requests.first().bodyAsString().contains("\"model\":\"gpt-image-2.6-flare\""))
        assertEquals("https://api.apimart.ai/v1/tasks/t1", requests.last().url.toString())
        assertEquals("Bearer key", requests.last().header("Authorization"))
    }
}
