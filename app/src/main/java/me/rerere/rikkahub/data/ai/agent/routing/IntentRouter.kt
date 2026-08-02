package me.rerere.rikkahub.data.ai.agent.routing

import me.rerere.ai.ui.UIMessagePart
import java.text.Normalizer
import java.util.Locale

data class IntentRoutingInput(
    val textSegments: List<String>,
    val hasAttachments: Boolean,
    val trust: InputTrust,
    val hasWorkspace: Boolean,
) {
    companion object {
        /**
         * Extracts only text authored in the user input surface. Document bodies, OCR, reasoning,
         * tool inputs, and tool outputs are deliberately excluded from intent authorization.
         */
        fun fromUserParts(
            parts: List<UIMessagePart>,
            trust: InputTrust,
            hasWorkspace: Boolean,
        ): IntentRoutingInput =
            IntentRoutingInput(
                textSegments = parts.filterIsInstance<UIMessagePart.Text>().map(UIMessagePart.Text::text),
                hasAttachments = parts.any { part ->
                    part is UIMessagePart.Image ||
                        part is UIMessagePart.Video ||
                        part is UIMessagePart.Audio ||
                        part is UIMessagePart.Document
                },
                trust = trust,
                hasWorkspace = hasWorkspace,
            )
    }
}

data class IntentDecision(
    val intent: AgentIntent,
    /** Stable, content-free identifier suitable for an [AgentRoutingSnapshot]. */
    val reasonCode: String,
)

fun interface IntentRouter {
    fun route(input: IntentRoutingInput): IntentDecision
}

/**
 * Conservative deterministic router. It grants execution only for an affirmative mutation or
 * command in direct user text; uncertainty stays read-only or asks for clarification.
 */
class RuleBasedIntentRouter : IntentRouter {
    override fun route(input: IntentRoutingInput): IntentDecision {
        val masked = IntentTextMasker.mask(input.textSegments.joinToString("\n"))
        val meaningful = masked
            .replace(IntentTextMasker.CODE_SENTINEL, "")
            .replace(IntentTextMasker.QUOTE_SENTINEL, "")
            .trim()
        if (meaningful.isBlank()) {
            return decision(AgentIntent.CLARIFY, "empty_or_inert_request")
        }

        if (isAmbiguousRequest(masked)) {
            return decision(AgentIntent.CLARIFY, "ambiguous_request")
        }

        val waitsForConfirmation = WAIT_FOR_CONFIRMATION.any { it.containsMatchIn(masked) }
        val affirmativeText = maskNegatedActions(masked)
        val requestsExecution = hasExplicitExecutionRequest(affirmativeText)
        if (requestsExecution && !waitsForConfirmation) {
            if (input.trust != InputTrust.USER_DIRECT) {
                return decision(AgentIntent.EXPLORE, "untrusted_execution_downgraded")
            }
            if (!input.hasWorkspace && hasExplicitWorkspaceTarget(affirmativeText)) {
                return decision(AgentIntent.CLARIFY, "workspace_not_available")
            }
            return decision(AgentIntent.EXECUTE, "explicit_mutation")
        }

        if (waitsForConfirmation) {
            return if (hasExplorationSignal(masked) || requestsExecution) {
                decision(AgentIntent.EXPLORE, "confirmation_required")
            } else {
                decision(AgentIntent.CLARIFY, "confirmation_required")
            }
        }

        if (hasTargetlessActionRequest(affirmativeText)) {
            return decision(AgentIntent.CLARIFY, "missing_action_target")
        }

        if (hasExplorationSignal(masked, input.hasAttachments)) {
            return decision(AgentIntent.EXPLORE, "explicit_exploration")
        }

        return decision(AgentIntent.ANSWER, "general_answer")
    }

    private fun hasExplicitExecutionRequest(text: String): Boolean =
        clauses(text).any { clause ->
            hasChineseExecutionRequest(clause) || hasEnglishExecutionRequest(clause)
        }

    private fun hasChineseExecutionRequest(clause: String): Boolean {
        if (!CHINESE_ACTION.containsMatchIn(clause)) return false
        if (!hasChineseActionTarget(clause)) return false

        if (CHINESE_DIRECT_QUESTION.containsMatchIn(clause)) return true
        if (CHINESE_PROPOSAL_OR_QUESTION.containsMatchIn(clause)) return false
        if (CHINESE_NOUN_ACTION.containsMatchIn(clause)) return false

        return CHINESE_IMPERATIVE_START.containsMatchIn(clause) ||
            CHINESE_REQUEST_PREFIX.containsMatchIn(clause) ||
            CHINESE_SEQUENCE_ACTION.containsMatchIn(clause) ||
            CHINESE_LOCATION_ACTION.containsMatchIn(clause)
    }

    private fun hasEnglishExecutionRequest(clause: String): Boolean {
        if (!hasEnglishActionTarget(clause)) return false
        if (ENGLISH_DIRECT_REQUEST.containsMatchIn(clause)) return true
        if (ENGLISH_IMPERATIVE_START.containsMatchIn(clause)) return true
        if (ENGLISH_PERSONAL_REQUEST.containsMatchIn(clause)) return true
        if (ENGLISH_SEQUENCE_ACTION.containsMatchIn(clause)) return true
        if (ENGLISH_LOCATION_ACTION.containsMatchIn(clause)) return true
        if (ENGLISH_WRITE_TO_WORKSPACE.containsMatchIn(clause)) return true
        if (ENGLISH_PROPOSAL_OR_QUESTION.containsMatchIn(clause)) return false
        return false
    }

    private fun hasChineseActionTarget(clause: String): Boolean =
        CHINESE_ACTION.findAll(clause).any { action ->
            val after = clause.substring(action.range.last + 1).trim()
            val before = clause.substring(0, action.range.first).trim()
            (after.isNotBlank() && !CHINESE_NON_TARGET_SUFFIX.matches(after)) ||
                CHINESE_PREPOSED_TARGET.containsMatchIn(before)
        }

    private fun hasEnglishActionTarget(clause: String): Boolean =
        ENGLISH_ACTION.findAll(clause).any { action ->
            val after = clause.substring(action.range.last + 1).trim()
            after.isNotBlank() && !ENGLISH_NON_TARGET_SUFFIX.matches(after)
        }

    private fun hasExplicitWorkspaceTarget(text: String): Boolean =
        CHINESE_WORKSPACE_TARGET.containsMatchIn(text) || ENGLISH_WORKSPACE_TARGET.containsMatchIn(text)

    private fun hasTargetlessActionRequest(text: String): Boolean = clauses(text).any { clause ->
        val chineseRequest = CHINESE_ACTION.containsMatchIn(clause) &&
            !CHINESE_PROPOSAL_OR_QUESTION.containsMatchIn(clause) &&
            !CHINESE_NOUN_ACTION.containsMatchIn(clause) &&
            (CHINESE_IMPERATIVE_START.containsMatchIn(clause) ||
                CHINESE_REQUEST_PREFIX.containsMatchIn(clause) ||
                CHINESE_SEQUENCE_ACTION.containsMatchIn(clause))
        val englishRequest = !ENGLISH_PROPOSAL_OR_QUESTION.containsMatchIn(clause) &&
            (ENGLISH_DIRECT_REQUEST.containsMatchIn(clause) ||
                ENGLISH_IMPERATIVE_START.containsMatchIn(clause) ||
                ENGLISH_PERSONAL_REQUEST.containsMatchIn(clause) ||
                ENGLISH_SEQUENCE_ACTION.containsMatchIn(clause))
        (chineseRequest && !hasChineseActionTarget(clause)) ||
            (englishRequest && !hasEnglishActionTarget(clause))
    }

    private fun hasExplorationSignal(text: String, hasAttachments: Boolean = false): Boolean {
        if (CHINESE_HOW_TO_ANSWER.containsMatchIn(text) || ENGLISH_HOW_TO_ANSWER.containsMatchIn(text)) {
            return false
        }
        if (CHINESE_EXPLORE.containsMatchIn(text) || ENGLISH_EXPLORE.containsMatchIn(text)) {
            return true
        }
        return hasAttachments && (CHINESE_ATTACHMENT_READ.containsMatchIn(text) ||
            ENGLISH_ATTACHMENT_READ.containsMatchIn(text))
    }

    private fun isAmbiguousRequest(text: String): Boolean =
        CHINESE_AMBIGUOUS.matches(text.trim()) || ENGLISH_AMBIGUOUS.matches(text.trim())

    private fun maskNegatedActions(text: String): String =
        ENGLISH_NEGATED_ACTION.replace(
            CHINESE_NEGATED_ACTION.replace(text, " <negated_action> "),
            " <negated_action> ",
        )

    private fun clauses(text: String): List<String> = text
        .split(CLAUSE_BOUNDARY)
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun decision(intent: AgentIntent, reasonCode: String) = IntentDecision(intent, reasonCode)
}

/** Masks inert quoted or code samples so their verbs cannot authorize workspace mutations. */
internal object IntentTextMasker {
    const val CODE_SENTINEL = "<code>"
    const val QUOTE_SENTINEL = "<quote>"

    private const val MAX_INPUT_CHARS = 65_536
    private val WHITESPACE = Regex("\\s+")
    private val QUOTE_PAIRS = mapOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
        '"' to '"',
        '\'' to '\'',
    )

    fun mask(text: String): String {
        val bounded = if (text.length <= MAX_INPUT_CHARS) {
            text
        } else {
            text.take(MAX_INPUT_CHARS / 2) + "\n" + text.takeLast(MAX_INPUT_CHARS / 2)
        }
        val normalized = Normalizer.normalize(bounded, Normalizer.Form.NFKC)
        val output = StringBuilder(normalized.length.coerceAtMost(MAX_INPUT_CHARS))
        var fence: Fence? = null

        normalized.split('\n').forEach { line ->
            val lineFence = fenceMarker(line)
            val activeFence = fence
            if (activeFence != null) {
                if (lineFence?.closes(activeFence) == true) fence = null
                output.append(' ')
                return@forEach
            }
            if (isMarkdownBlockquote(line)) {
                output.append(' ').append(QUOTE_SENTINEL).append(' ')
                return@forEach
            }
            if (lineFence != null) {
                fence = lineFence
                output.append(' ').append(CODE_SENTINEL).append(' ')
                return@forEach
            }
            output.append(maskInlineCodeAndQuotes(line)).append('\n')
        }

        return WHITESPACE.replace(output.toString(), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun maskInlineCodeAndQuotes(line: String): String {
        val output = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val current = line[index]
            if (current == '`' && !line.isEscaped(index)) {
                val delimiterLength = line.runLength(index, '`')
                val closing = line.findRun(index + delimiterLength, '`', delimiterLength)
                output.append(' ').append(CODE_SENTINEL).append(' ')
                if (closing == -1) break
                index = closing + line.runLength(closing, '`')
                continue
            }

            val quoteEnd = QUOTE_PAIRS[current]
            if (quoteEnd != null && line.isQuoteOpening(index, current)) {
                val closing = line.findQuoteEnd(index + 1, quoteEnd)
                output.append(' ').append(QUOTE_SENTINEL).append(' ')
                if (closing == -1) break
                index = closing + 1
                continue
            }

            output.append(current)
            index++
        }
        return output.toString()
    }

    private fun fenceMarker(line: String): Fence? {
        var index = 0
        while (index < line.length && line[index] == ' ' && index <= 3) index++
        if (index > 3 || index >= line.length) return null
        val marker = line[index]
        if (marker != '`' && marker != '~') return null
        val length = line.runLength(index, marker)
        return length.takeIf { it >= 3 }?.let { Fence(marker, it) }
    }

    private fun isMarkdownBlockquote(line: String): Boolean {
        var index = 0
        while (index < line.length && line[index] == ' ' && index <= 3) index++
        return index <= 3 && line.getOrNull(index) == '>'
    }

    private fun String.isQuoteOpening(index: Int, quote: Char): Boolean {
        if (isEscaped(index)) return false
        if (quote != '\'') return true
        val previous = getOrNull(index - 1)
        val next = getOrNull(index + 1)
        return !(previous?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true)
    }

    private fun String.findQuoteEnd(start: Int, quoteEnd: Char): Int {
        var index = start
        while (index < length) {
            if (this[index] == quoteEnd && !isEscaped(index)) return index
            index++
        }
        return -1
    }

    private fun String.findRun(start: Int, marker: Char, minimumLength: Int): Int {
        var index = start
        while (index < length) {
            if (this[index] == marker && !isEscaped(index) && runLength(index, marker) >= minimumLength) {
                return index
            }
            index++
        }
        return -1
    }

    private fun String.runLength(start: Int, marker: Char): Int {
        var end = start
        while (end < length && this[end] == marker) end++
        return end - start
    }

    private fun String.isEscaped(index: Int): Boolean {
        var slashes = 0
        var cursor = index - 1
        while (cursor >= 0 && this[cursor] == '\\') {
            slashes++
            cursor--
        }
        return slashes % 2 == 1
    }

    private data class Fence(val marker: Char, val length: Int) {
        fun closes(open: Fence): Boolean = marker == open.marker && length >= open.length
    }
}

private const val CHINESE_ACTION_PATTERN =
    "(?:修复|修改|改成|改为|改动|改写|更改|实现|创建|新建|新增|添加|删除|移除|重命名|移动|替换|" +
        "重构|安装|提交|推送|写入|执行|运行|构建|编译|更新|优化|完善|调整|处理|改)"
private const val ENGLISH_ACTION_PATTERN =
    "(?:fix|modify|change|implement|create|add|delete|remove|rename|move|replace|refactor|install|commit|" +
        "push|apply|run|execute|build|compile|update|optimize|optimise)"

private val CLAUSE_BOUNDARY = Regex("[。！？!?；;,，\\n]+")
private val CHINESE_ACTION = Regex(CHINESE_ACTION_PATTERN)
private val ENGLISH_ACTION = Regex("\\b$ENGLISH_ACTION_PATTERN\\b", RegexOption.IGNORE_CASE)
private val CHINESE_NON_TARGET_SUFFIX = Regex("^(?:一下|下|吧|吗|呢|看看|试试|一下吧|下吧|直接|立即|现在)*$")
private val CHINESE_PREPOSED_TARGET = Regex("(?:把|将).+(?:直接|立即|现在)?$")
private val ENGLISH_NON_TARGET_SUFFIX = Regex(
    "^(?:(?:please|directly|now|again|if\\s+needed|when\\s+needed)\\s*)*[.!?]*$",
    RegexOption.IGNORE_CASE,
)
private val CHINESE_WORKSPACE_TARGET = Regex(
    "(?:工作区|项目|仓库|代码|源码|文件|目录|模块|readme|测试|构建|编译|gradle|app\\s*模块)",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_WORKSPACE_TARGET = Regex(
    "\\b(?:workspace|repo|repository|project|module|file|readme|source|code|tests?|build|gradle)\\b",
    RegexOption.IGNORE_CASE,
)
private val CHINESE_DIRECT_QUESTION = Regex(
    "(?:你能|你可以|能不能帮我|可以帮我|可否帮我).{0,24}$CHINESE_ACTION_PATTERN",
)
private val CHINESE_PROPOSAL_OR_QUESTION = Regex(
    "(?:如何|怎么|为什么|是什么|能否|是否|可不可以|有没有必要|方案|建议|思路|示例|例子|教程|风险)",
)
private val CHINESE_NOUN_ACTION = Regex("(?:修复|修改|实现|重构|删除|优化)(?:方案|建议|思路|方法|教程|示例)")
private val CHINESE_IMPERATIVE_START = Regex(
    "^(?:请|请你|帮我|麻烦你?|直接|立即|现在|开始|继续|然后|再|只)?\\s*$CHINESE_ACTION_PATTERN",
)
private val CHINESE_REQUEST_PREFIX = Regex(
    "^(?:请|请你|帮我|麻烦你?|直接|立即|现在|开始|继续|把|将|给我|我想让你|我要你|我希望你)" +
        ".{0,40}$CHINESE_ACTION_PATTERN",
)
private val CHINESE_SEQUENCE_ACTION = Regex(
    "(?:并|然后|再|后|之后|接着|随后|直接|只)\\s*$CHINESE_ACTION_PATTERN",
)
private val CHINESE_LOCATION_ACTION = Regex("^(?:在|于).{0,40}$CHINESE_ACTION_PATTERN")
private val CHINESE_NEGATED_ACTION = Regex(
    "(?:不要|无需|不必|禁止|切勿|暂不|先别|别)\\s*(?:直接|立即|现在|再|去)?\\s*$CHINESE_ACTION_PATTERN",
)
private val CHINESE_AMBIGUOUS = Regex(
    "^(?:(?:请|帮我|麻烦你?)\\s*)?(?:处理|优化|完善|调整|搞|弄)(?:这个|它|一下|下|一下吧|吧|看看)?[。.!！?？]*$|" +
        "^(?:改改看|随便改改)[。.!！?？]*$",
)
private val CHINESE_HOW_TO_ANSWER = Regex("^(?:请问)?(?:分析|解释)?(?:一下)?(?:如何|怎么)|^(?:给我|提供).*(?:方案|建议)")
private val CHINESE_EXPLORE = Regex(
    "(?:检查|查看|看看|分析|审查|找出|定位|搜索|检索|调查|研究|比较|阅读|总结|梳理|排查|确认)",
)
private val CHINESE_ATTACHMENT_READ = Regex("(?:总结|阅读|查看|分析|解释|提取).*(?:这|该|附件|文档|pdf|文件)")

private val ENGLISH_DIRECT_REQUEST = Regex(
    "\\b(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?(?:\\w+\\s+){0,3}$ENGLISH_ACTION_PATTERN\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_IMPERATIVE_START = Regex(
    "^(?:(?:please|directly|now)\\s+|go\\s+ahead(?:\\s+and)?\\s+)?$ENGLISH_ACTION_PATTERN\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_PERSONAL_REQUEST = Regex(
    "\\bi\\s+(?:want|need|would\\s+like)\\s+you\\s+to\\s+$ENGLISH_ACTION_PATTERN\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_SEQUENCE_ACTION = Regex(
    "\\b(?:and|then|and\\s+then)\\s+(?:please\\s+)?$ENGLISH_ACTION_PATTERN\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_LOCATION_ACTION = Regex(
    "^(?:in|inside|within)\\b.{0,50}\\b$ENGLISH_ACTION_PATTERN\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_WRITE_TO_WORKSPACE = Regex(
    "^(?:(?:please|directly)\\s+)?write\\b.{0,40}(?:\\bfile\\b|\\bmodule\\b|\\bproject\\b|" +
        "\\bworkspace\\b|\\brepository\\b|\\brepo\\b|<code>)",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_PROPOSAL_OR_QUESTION = Regex(
    "^(?:what|why|how|when|where|is|are|can\\s+this|could\\s+this|should\\s+(?:i|we))\\b|" +
        "\\b(?:how\\s+to|plan|proposal|suggestion|example|tutorial|risks?)\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_NEGATED_ACTION = Regex(
    "\\b(?:do\\s+not|don't|dont|never)\\s+(?:just\\s+)?$ENGLISH_ACTION_PATTERN\\b|" +
        "\\bwithout\\s+(?:changing|modifying|deleting|removing|running|executing|writing)\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_AMBIGUOUS = Regex(
    "^(?:please\\s+)?(?:handle(?:\\s+(?:this|it))?|improve(?:\\s+(?:this|it))?|" +
        "optimi[sz]e(?:\\s+(?:this|it))?|make\\s+it\\s+better|take\\s+care\\s+of\\s+this)[.!?]*$",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_HOW_TO_ANSWER = Regex(
    "^(?:please\\s+)?(?:analy[sz]e|explain)?\\s*(?:how\\s+to|how\\s+would|what\\s+does|show\\s+me\\s+how)",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_EXPLORE = Regex(
    "\\b(?:inspect|investigate|review|find|locate|search|look\\s+up|analy[sz]e|check|summari[sz]e|" +
        "read|compare|audit)\\b",
    RegexOption.IGNORE_CASE,
)
private val ENGLISH_ATTACHMENT_READ = Regex(
    "\\b(?:summari[sz]e|read|inspect|analy[sz]e|explain|extract)\\b.{0,40}" +
        "\\b(?:this|the|attached|attachment|document|pdf|file)\\b",
    RegexOption.IGNORE_CASE,
)
private val WAIT_FOR_CONFIRMATION = listOf(
    Regex("(?:等|等待).{0,6}(?:我)?(?:确认|批准|同意)"),
    Regex("先(?:不要|别|不).{0,8}(?:动手|修改|改动|执行|运行)"),
    Regex("\\bwait\\s+for\\s+(?:my\\s+)?(?:approval|confirmation)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:do\\s+not|don't)\\s+make\\s+changes\\s+yet\\b", RegexOption.IGNORE_CASE),
)
