package me.rerere.rikkahub.data.ai.tools

private val chineseIdRegex =
    Regex("""(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)""")
private val paymentCardRegex = Regex("""(?<![\d-])\d(?:[ -]?\d){14,18}(?![\d-])""")
private val ssnRegex = Regex("""(?<![\d-])(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}(?![\d-])""")

/**
 * 记忆写入前的兜底拦截：证件号、卡号不能只靠提示词约束。
 *
 * @return 命中时返回内容类别的描述，否则返回 null
 */
internal fun findForbiddenMemoryContent(text: String): String? = when {
    chineseIdRegex.findAll(text).any { isValidChineseId(it.value) } -> "an ID number"
    ssnRegex.containsMatchIn(text) -> "a social security number"
    paymentCardRegex.findAll(text).any { isLuhnValid(it.value.filter(Char::isDigit)) } -> "a bank card number"
    else -> null
}

private val chineseIdWeights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
private const val CHINESE_ID_CHECK_CHARS = "10X98765432"

private fun isValidChineseId(id: String): Boolean {
    val sum = chineseIdWeights.indices.sumOf { (id[it] - '0') * chineseIdWeights[it] }
    return CHINESE_ID_CHECK_CHARS[sum % 11] == id.last().uppercaseChar()
}

private fun isLuhnValid(digits: String): Boolean {
    var sum = 0
    digits.reversed().forEachIndexed { index, char ->
        var digit = char - '0'
        if (index % 2 == 1) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
    }
    return sum % 10 == 0
}
