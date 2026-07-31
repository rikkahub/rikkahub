package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        // Local ML Kit OCR first: offline, free, stable. Falls back to AI OCR when empty.
        val localResult = performLocalOcr(part.url)
        if (!localResult.isNullOrBlank()) {
            Log.i(TAG, "performOcr: using local ML Kit result")
            val localOcrResult = """
                <image_file_ocr>
                   $localResult
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()
            cache.put(part.url, localOcrResult)
            return localOcrResult
        }
        Log.i(TAG, "performOcr: local OCR empty, falling back to AI OCR")

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(settings.ocrPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(part.url))
                )
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText() ?: "[ERROR, OCR failed]"
        Log.i(TAG, "performOcr: $content")
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        return ocrResult
    }.getOrElse {
        "[ERROR, OCR failed: $it]"
    }

    /**
     * Local OCR via ML Kit. Chinese model + Latin model both run, results merged
     * line-by-line (covers mixed CJK + Latin content). Returns null when nothing
     * was recognized (e.g. model not downloaded yet or image decode failure),
     * letting the caller fall back to the configured AI OCR model.
     */
    private suspend fun performLocalOcr(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val context = get<Context>()
            val image: InputImage = when {
                url.startsWith("file://") -> InputImage.fromFilePath(context, Uri.parse(url))
                else -> return@runCatching null
            }

            val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val chineseText = runCatching { chinese.process(image).await() }.getOrNull()?.text?.trim()
                val latinText = runCatching { latin.process(image).await() }.getOrNull()?.text?.trim()

                val combined = LinkedHashSet<String>()
                listOfNotNull(chineseText, latinText).forEach { text ->
                    text.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank()) combined.add(trimmed)
                    }
                }
                combined.joinToString("\n").takeIf { it.isNotBlank() }
            } finally {
                chinese.close()
                latin.close()
            }
        }.getOrNull()
    }
}
