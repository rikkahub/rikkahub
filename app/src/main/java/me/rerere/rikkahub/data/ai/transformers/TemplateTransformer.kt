package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.rikkahub.utils.toLocalTime
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import kotlin.time.toJavaInstant

class TemplateTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // Compile the template carried by this generation's frozen Assistant instead of consulting
        // the live SettingsStore-backed loader during an approval continuation.
        val frozenTemplates = ctx.settings.assistants.associate {
            it.id.toString() to it.messageTemplate
        } + (ctx.assistant.id.toString() to ctx.assistant.messageTemplate)
        val frozenEngine = PebbleEngine.Builder()
            .loader(SnapshotAssistantTemplateLoader(frozenTemplates))
            .autoEscaping(false)
            .build()
        val template = frozenEngine.getLiteralTemplate(ctx.assistant.messageTemplate)
        val timeZone = TimeZone.currentSystemDefault()
        return messages.map { message ->
            // 使用消息本身的发送时间而不是当前时间, 保证多次请求时渲染结果稳定, 不破坏 prompt 缓存
            val createdAt = message.createdAt.toInstant(timeZone).toJavaInstant()
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            template.evaluate(
                                result, mapOf(
                                    "message" to part.text,
                                    "role" to message.role.name.lowercase(),
                                    "time" to createdAt.toLocalTime(),
                                    "date" to createdAt.toLocalDate(),
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

private class SnapshotAssistantTemplateLoader(
    private val templates: Map<String, String>,
) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? =
        templates[cacheKey]?.let(::StringReader)

    override fun setCharset(charset: String?) = Unit
    override fun setPrefix(prefix: String?) = Unit
    override fun setSuffix(suffix: String?) = Unit
    override fun resolveRelativePath(relativePath: String?, anchorPath: String?): String? = relativePath
    override fun createCacheKey(templateName: String?): String? = templateName
    override fun resourceExists(templateName: String?): Boolean = templateName in templates
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
