package me.rerere.rikkahub.data.skills

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): SkillFrontmatter {
        if (!content.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(content) ?: return SkillFrontmatter.Empty
        val yamlContent = content.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
            )
        }.getOrDefault(SkillFrontmatter.Empty)
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    /** 把 [values] 写成 frontmatter 接在 [body] 前面，[parse] 和 [extractBody] 能原样读回来 */
    fun compose(values: Map<String, Any?>, body: String): String = buildString {
        append("---\n")
        append(createDumper().dump(values))
        append("---\n")
        val text = body.trimStart('\r', '\n').trimEnd()
        if (text.isNotEmpty()) {
            append('\n')
            append(text)
            append('\n')
        }
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }

    private fun createDumper(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            // 一个值占一行：长句子折行后，按行替换文本的工具就对不上了
            splitLines = false
        }
        // 列表写成 [a, b]，和模型按格式约定写出来的一样
        val representer = object : Representer(options) {
            override fun representSequence(
                tag: Tag,
                sequence: Iterable<*>,
                flowStyle: DumperOptions.FlowStyle,
            ): Node = super.representSequence(tag, sequence, DumperOptions.FlowStyle.FLOW)
        }
        return Yaml(representer, options)
    }
}

class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    fun toMap(): Map<String, Any?> = values

    fun getList(key: String): List<String> =
        (values[key] as? List<*>).orEmpty().mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
