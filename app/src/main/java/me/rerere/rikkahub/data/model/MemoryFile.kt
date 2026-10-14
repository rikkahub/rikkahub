package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import java.security.MessageDigest

/**
 * 记忆库里的一个 Markdown 文件。
 *
 * [version] 由内容算出，写操作用它做乐观锁：内容没变版本就不变。
 */
data class MemoryFile(
    val path: String,
    val content: String,
    val updatedAt: Long,
) {
    val version: String by lazy { memoryVersionOf(content) }

    private val frontmatter by lazy { SkillFrontmatterParser.parse(content) }

    /** frontmatter 里的一句话描述，用于清单 */
    val description: String get() = frontmatter["description"]?.trim().orEmpty()

    val aliases: List<String> get() = frontmatter.getList("aliases")

    /** 去掉 frontmatter 后的正文 */
    val body: String get() = SkillFrontmatterParser.extractBody(content)

    /** 给用户看的正文：`[stated]` 是写给模型的标记，界面上不显示 */
    val displayBody: String get() = body.replace(statedMarkerRegex, "$1")

    /**
     * 编辑页里的正文。frontmatter 读不出来时（比如某个值里有没加引号的冒号）整段原文都算正文：
     * 那一段在各个输入框里都看不到，不留在正文里的话一保存就被悄悄换掉了。
     */
    val editableBody: String get() = if (frontmatter.toMap().isEmpty()) content else body

    /** 所在目录，根目录下的文件是 "/" */
    val directory: String get() = path.substringBeforeLast('/').ifEmpty { "/" }

    /** 去掉目录和 .md 后缀的文件名 */
    val name: String get() = path.substringAfterLast('/').removeSuffix(".md")

    /** 界面上显示的标题：frontmatter 里的 title，没写时从文件名推出来（frontend-tooling 显示成 Frontend Tooling） */
    val title: String get() = frontmatter["title"]?.trim()?.ifEmpty { null } ?: memoryTitleOf(name)

    /**
     * 用编辑页分开填的各项拼出新的文件内容。
     *
     * name 跟着路径走，不由用户填；留空的项不写；frontmatter 里其他的键原样保留。
     */
    fun contentWith(title: String, description: String, aliases: List<String>, body: String): String {
        val values = linkedMapOf<String, Any?>("name" to name)
        title.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["title"] = it }
        description.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["description"] = it }
        aliases.takeIf { it.isNotEmpty() }?.let { values["aliases"] = it }
        frontmatter.toMap().forEach { (key, value) -> if (key !in editableKeys) values[key] = value }
        return SkillFrontmatterParser.compose(values, body)
    }

    companion object {
        const val PROFILE_PATH = "/profile.md"
        const val PREFERENCES_PATH = "/preferences.md"
        const val MAX_BYTES = 48 * 1024
        private const val MAX_PATH_LENGTH = 128

        /** 规范化模型或用户给出的路径，不合法时返回 null */
        fun normalizePath(raw: String): String? {
            val path = raw.trim().let { if (it.startsWith("/")) it else "/$it" }
            if (path.length > MAX_PATH_LENGTH || !path.endsWith(".md")) return null
            if (path.any { it.isISOControl() || it == '\\' }) return null
            val segments = path.removePrefix("/").split("/")
            if (segments.any { it.isBlank() || it != it.trim() || it.startsWith(".") }) return null
            return path
        }
    }
}

// 编辑页有对应输入框的键，加上跟着路径走的 name
private val editableKeys = setOf("name", "title", "description", "aliases")

// 清单里一个文件占一行，标题和描述里不能有换行
private fun String.toSingleLine(): String = replace(Regex("\\s+"), " ").trim()

// 列表项开头的 [stated] 标记，保留前面的列表符号
private val statedMarkerRegex = Regex("""(?m)^(\s*[-*+]\s+)\[stated\]\s*""", RegexOption.IGNORE_CASE)

// 每次都全文注入的两个文件排在最前，其余目录按记忆格式约定的顺序
private val pinnedPathOrder = listOf(MemoryFile.PROFILE_PATH, MemoryFile.PREFERENCES_PATH)
private val directoryOrder = listOf("/", "/topics", "/areas", "/people")

private fun <T> List<T>.orderOf(item: T) = indexOf(item).let { if (it < 0) size else it }

/** 把路径里用连字符或下划线拼的名字转成界面上的标题 */
fun memoryTitleOf(name: String): String =
    name.split('-', '_', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

/** 按目录分组，用于在界面上展示 */
fun List<MemoryFile>.groupByDirectory(): List<Pair<String, List<MemoryFile>>> =
    sortedWith(compareBy({ pinnedPathOrder.orderOf(it.path) }, { it.path }))
        .groupBy { it.directory }
        .toList()
        .sortedWith(compareBy({ directoryOrder.orderOf(it.first) }, { it.first }))

private fun memoryVersionOf(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
