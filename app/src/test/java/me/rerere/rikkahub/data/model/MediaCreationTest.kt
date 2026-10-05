package me.rerere.rikkahub.data.model

import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCreationTest {
    private val openAIImage = MediaGenerationProviderSetting.OpenAI().capabilities(MediaKind.IMAGE)!!
    private val volcengineImage = MediaGenerationProviderSetting.Volcengine().capabilities(MediaKind.IMAGE)!!
    private val volcengineVideo = MediaGenerationProviderSetting.Volcengine().capabilities(MediaKind.VIDEO)!!
    private val miniMaxVideo = MediaGenerationProviderSetting.MiniMax().capabilities(MediaKind.VIDEO)!!

    private fun image(path: String, role: ImageRole = ImageRole.REFERENCE) =
        MediaCreationAsset(path = path, type = MediaCreationAssetType.IMAGE, role = role)

    private fun video(path: String) = MediaCreationAsset(path = path, type = MediaCreationAssetType.VIDEO)

    @Test
    fun `params keep only what the adapter supports`() {
        val params = MediaCreationParams(
            count = 2,
            resolution = "1536x1024",
            aspectRatio = "16:9",
            durationSeconds = 5,
            generateAudio = true,
            watermark = false,
            seed = 7,
            promptEnhancement = true,
        )

        assertEquals(MediaCreationParams(count = 2, resolution = "1536x1024"), params.supportedBy(openAIImage))
        assertEquals(
            MediaCreationParams(
                resolution = "1536x1024",
                aspectRatio = "16:9",
                durationSeconds = 5,
                generateAudio = true,
                watermark = false,
                seed = 7,
            ),
            params.supportedBy(volcengineVideo),
        )
    }

    @Test
    fun `blank text params fall back to the default`() {
        val params = MediaCreationParams(resolution = "  ", aspectRatio = "")

        assertEquals(MediaCreationParams(), params.supportedBy(volcengineVideo))
    }

    @Test
    fun `image models turn frames into references and drop videos`() {
        val assets = listOf(
            image("first.png", ImageRole.FIRST_FRAME),
            video("clip.mp4"),
            image("ref.png"),
        )

        assertEquals(listOf(image("first.png"), image("ref.png")), assets.supportedBy(volcengineImage))
    }

    @Test
    fun `video models keep one first frame and one last frame`() {
        val assets = listOf(
            image("first-old.png", ImageRole.FIRST_FRAME),
            image("ref-1.png"),
            image("last.png", ImageRole.LAST_FRAME),
            image("first-new.png", ImageRole.FIRST_FRAME),
            image("ref-2.png"),
            video("clip.mp4"),
        )

        assertEquals(
            listOf(
                image("ref-1.png"),
                image("last.png", ImageRole.LAST_FRAME),
                image("first-new.png", ImageRole.FIRST_FRAME),
                image("ref-2.png"),
                video("clip.mp4"),
            ),
            assets.supportedBy(volcengineVideo),
        )
    }

    @Test
    fun `draft needs a prompt unless the adapter can work from assets alone`() {
        val promptOnly = MediaCreationDraft(prompt = "a cat")
        val assetsOnly = MediaCreationDraft(assets = listOf(image("first.png", ImageRole.FIRST_FRAME)))

        assertTrue(promptOnly.canSubmit(miniMaxVideo))
        assertTrue(assetsOnly.canSubmit(volcengineVideo))
        assertFalse(assetsOnly.canSubmit(miniMaxVideo))
        assertFalse(MediaCreationDraft(prompt = "  ").canSubmit(volcengineVideo))
    }

    @Test
    fun `frames and references cannot be mixed on video models`() {
        val frame = image("first.png", ImageRole.FIRST_FRAME)
        val framesOnly = listOf(frame, image("last.png", ImageRole.LAST_FRAME))
        val referencesOnly = listOf(image("ref.png"), video("clip.mp4"))

        assertFalse(framesOnly.mixesFramesWithReferences(volcengineVideo))
        assertFalse(referencesOnly.mixesFramesWithReferences(volcengineVideo))
        assertTrue((framesOnly + image("ref.png")).mixesFramesWithReferences(volcengineVideo))
        assertTrue(listOf(frame, video("clip.mp4")).mixesFramesWithReferences(miniMaxVideo))

        val mixed = MediaCreationDraft(prompt = "a cat", assets = listOf(frame, image("ref.png")))
        assertFalse(mixed.canSubmit(volcengineVideo))
        assertTrue(mixed.copy(assets = listOf(frame)).canSubmit(volcengineVideo))
    }

    @Test
    fun `image models need a prompt even with a reference image`() {
        val assetsOnly = MediaCreationDraft(assets = listOf(image("ref.png")))

        assertFalse(assetsOnly.canSubmit(openAIImage))
        assertFalse(assetsOnly.canSubmit(volcengineImage))
    }

    @Test
    fun `required params fall back to the given defaults`() {
        val defaults = MediaCreationParams(count = 1, resolution = "768P", aspectRatio = "16:9", durationSeconds = 5)

        // MiniMax 要求分辨率、比例、时长；数量不是必填，不会被补上
        assertEquals(
            MediaCreationParams(resolution = "768P", aspectRatio = "16:9", durationSeconds = 5),
            MediaCreationParams().withRequired(miniMaxVideo, defaults),
        )
        assertEquals(
            MediaCreationParams(resolution = "2K", aspectRatio = "16:9", durationSeconds = 10, watermark = true),
            MediaCreationParams(resolution = "2K", aspectRatio = " ", durationSeconds = 10, watermark = true)
                .withRequired(miniMaxVideo, defaults),
        )
        assertEquals(MediaCreationParams(), MediaCreationParams().withRequired(volcengineVideo, defaults))
    }

    @Test
    fun `request maps assets to inputs and drops unsupported params`() {
        val request = buildMediaGenerationRequest(
            prompt = "镜头缓慢推近",
            params = MediaCreationParams(count = 2, resolution = "1080p", durationSeconds = 5, promptEnhancement = true),
            assets = listOf(image("media/first.png", ImageRole.FIRST_FRAME), video("media/clip.mp4")),
            inputUrls = listOf("https://s3.example.com/first.png", "https://s3.example.com/clip.mp4"),
            capabilities = volcengineVideo,
        )

        assertEquals("镜头缓慢推近", request.prompt)
        assertEquals(
            listOf(
                MediaGenerationInput.Image(url = "https://s3.example.com/first.png", role = ImageRole.FIRST_FRAME),
                MediaGenerationInput.Video(url = "https://s3.example.com/clip.mp4"),
            ),
            request.inputs,
        )
        assertEquals("1080p", request.resolution)
        assertEquals(5, request.durationSeconds)
        // 火山视频不接受数量和提示词优化
        assertNull(request.count)
        assertNull(request.promptEnhancement)
    }

    @Test
    fun `request without a prompt sends none`() {
        val request = buildMediaGenerationRequest(
            prompt = "  ",
            params = MediaCreationParams(),
            assets = listOf(image("media/first.png", ImageRole.FIRST_FRAME)),
            inputUrls = listOf("https://s3.example.com/first.png"),
            capabilities = volcengineVideo,
        )

        assertNull(request.prompt)
    }

    @Test
    fun `title comes from the first non-blank line of the prompt`() {
        assertEquals("雪山下的小木屋", deriveMediaCreationTitle("\n  雪山下的小木屋  \n黄昏，暖色灯光"))
        assertEquals("a".repeat(24), deriveMediaCreationTitle("a".repeat(40)))
        assertEquals("", deriveMediaCreationTitle("   "))
    }
}
