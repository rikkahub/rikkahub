package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.toLocalDateString
import me.rerere.rikkahub.utils.toLocalTimeString
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter

class TemplateTransformer(
    private val engine: PebbleEngine,
    private val settingsStore: SettingsStore
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = engine.getTemplate(ctx.assistant.id.toString())
        return messages.map { message ->
            // system / tool 等非用户/助手消息：不套用模板，原样返回。
            // 这些消息的 createdAt 每次请求都不同，若套模板并注入时间戳会导致最前缀缓存失效；
            // 且它们本不需要 {{ role }}/{{ message }} 等模板变量，跳过更合理。
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) {
                return@map message
            }

            // 用户/助手消息：使用消息自身的创建时间（历史时间戳），而非当前请求时刻。
            // 这样历史消息渲染结果跨请求保持稳定，避免破坏各 Provider 的前缀 prompt 缓存。
            val createdAt = message.createdAt.toJavaLocalDateTime()
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            template.evaluate(
                                result, mapOf(
                                    "message" to part.text,
                                    "role" to message.role.name.lowercase(),
                                    "time" to createdAt.toLocalTimeString(),
                                    "date" to createdAt.toLocalDateString(),
                                )
                            )
                            part.copy(
                                text = result.toString()
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        return StringReader(content)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
