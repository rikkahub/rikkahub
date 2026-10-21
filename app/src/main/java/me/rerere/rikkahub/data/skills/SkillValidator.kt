package me.rerere.rikkahub.data.skills

/**
 * 安装技能前校验 SKILL.md。
 *
 * 技能目录名直接取 frontmatter 的 name，之后读取、编辑、删除都靠两者一致来定位目录，
 * 所以 name 必须能原样当作目录名，并且 [SkillManager] 能把这个技能列出来。
 */
object SkillValidator {
    const val MAX_NAME_LENGTH = 64

    /** 通过时返回技能名，失败时为 [SkillValidationException] */
    fun validate(content: String): Result<String> = runCatching {
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: throw SkillValidationException(SkillValidationError.MissingName)
        // 安装时若 trim 后作为目录名，读回来的 name 仍带空白，两者就对不上了
        ensure(name == name.trim(), SkillValidationError.NameHasSurroundingWhitespace)
        ensure(name.length <= MAX_NAME_LENGTH, SkillValidationError.NameTooLong)
        // 以 . 开头的目录会被当作隐藏目录跳过，装上也看不到
        ensure(!name.startsWith('.'), SkillValidationError.NameStartsWithDot)
        ensure(name.none { it == '/' || it == '\\' || it.isISOControl() }, SkillValidationError.NameHasInvalidChars)
        ensure(!frontmatter["description"].isNullOrBlank(), SkillValidationError.MissingDescription)
        name
    }

    private fun ensure(condition: Boolean, error: SkillValidationError) {
        if (!condition) throw SkillValidationException(error)
    }
}

enum class SkillValidationError {
    MissingName,
    NameHasSurroundingWhitespace,
    NameTooLong,
    NameStartsWithDot,
    NameHasInvalidChars,
    MissingDescription,
}

/** 界面按 [error] 显示本地化文案 */
class SkillValidationException(val error: SkillValidationError) : IllegalArgumentException(error.name)
