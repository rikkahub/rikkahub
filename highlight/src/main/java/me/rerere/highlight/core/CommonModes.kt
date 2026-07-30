package me.rerere.highlight.core

/**
 * The shared regular expressions and modes exported as `MODES` by `highlight.js` 11.11.1.
 *
 * Every mode declared as a `val` here is shared between grammars and therefore [frozen]: the
 * compiler copies it before touching it.
 */

internal const val MATCH_NOTHING_RE = """\b\B"""
internal const val IDENT_RE = """[a-zA-Z]\w*"""
internal const val UNDERSCORE_IDENT_RE = """[a-zA-Z_]\w*"""
internal const val NUMBER_RE = """\b\d+(\.\d+)?"""

/** `0x...`, `0...`, decimal and float. */
internal const val C_NUMBER_RE =
    """(-?)(\b0[xX][a-fA-F0-9]+|(\b\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?)"""

internal const val BINARY_NUMBER_RE = """\b(0b[01]+)"""

internal const val RE_STARTERS_RE =
    """!|!=|!==|%|%=|&|&&|&=|\*|\*=|\+|\+=|,|-|-=|/=|/|:|;|<<|<<=|<=|<|===|==|=|>>>=|>>=|>=|""" +
        """>>>|>>|>|\?|\[|\{|\(|\^|\^=|\||\|=|\|\||~"""

internal val BACKSLASH_ESCAPE: Mode = mode {
    begin = """\\[\s\S]"""
    relevance = 0.0
}.frozen()

internal val APOS_STRING_MODE: Mode = mode {
    scope = "string"
    begin = "'"
    end = "'"
    illegal = """\n"""
    contains = listOf(BACKSLASH_ESCAPE)
}.frozen()

internal val QUOTE_STRING_MODE: Mode = mode {
    scope = "string"
    begin = "\""
    end = "\""
    illegal = """\n"""
    contains = listOf(BACKSLASH_ESCAPE)
}.frozen()

internal val PHRASAL_WORDS_MODE: Mode = mode {
    begin = """\b(a|an|the|are|I'm|isn't|don't|doesn't|won't|but|just|should|pretty|simply|""" +
        """enough|gonna|going|wtf|so|such|will|you|your|they|like|more)\b"""
}.frozen()

/**
 * Builds a comment mode.
 *
 * The extra `contains` entries are what upstream adds to every comment: doctags such as `TODO:`,
 * plus a rule that recognises runs of ordinary English words, which is a strong signal that the
 * text really is prose and not code that merely looked like a comment opener.
 */
internal fun comment(
    begin: String? = null,
    end: String? = null,
    block: (Mode.() -> Unit)? = null,
): Mode {
    val mode = mode {
        scope = "comment"
        this.begin = begin
        this.end = end
    }
    block?.invoke(mode)

    val doctag = mode {
        scope = "doctag"
        // The leading spaces have to be matched so the prose rule below cannot swallow a doctag,
        // but they must not be part of the emitted token.
        this.begin = """[ ]*(?=($DOCTAG_WORDS):)"""
        this.end = """($DOCTAG_WORDS):"""
        excludeBegin = true
        relevance = 0.0
    }
    val prose = mode {
        this.begin = concat("""[ ]+""", "(", ENGLISH_WORD, """[.]?[:]?([.][ ]|[ ])""", "){3}")
    }

    mode.contains = mode.contains + doctag + prose
    return mode
}

private const val DOCTAG_WORDS = "TODO|FIXME|NOTE|BUG|OPTIMIZE|HACK|XXX"

/** Common one and two letter English words, contractions and ordinary capitalised words. */
private val ENGLISH_WORD = either(
    "I", "a", "is", "so", "us", "to", "at", "if", "in", "it", "on",
    """[A-Za-z]+['](d|ve|re|ll|t|s|n)""",
    """[A-Za-z]+[-][a-z]+""",
    """[A-Za-z][a-z]{2,}""",
)

internal val C_LINE_COMMENT_MODE: Mode = comment("//", "$").frozen()

internal val C_BLOCK_COMMENT_MODE: Mode = comment("""/\*""", """\*/""").frozen()

internal val HASH_COMMENT_MODE: Mode = comment("#", "$").frozen()

internal val NUMBER_MODE: Mode = mode {
    scope = "number"
    begin = NUMBER_RE
    relevance = 0.0
}.frozen()

internal val C_NUMBER_MODE: Mode = mode {
    scope = "number"
    begin = C_NUMBER_RE
    relevance = 0.0
}.frozen()

internal val BINARY_NUMBER_MODE: Mode = mode {
    scope = "number"
    begin = BINARY_NUMBER_RE
    relevance = 0.0
}.frozen()

internal val REGEXP_MODE: Mode = mode {
    scope = "regexp"
    begin = """/(?=[^/\n]*/)"""
    end = """/[gimuy]*"""
    contains = listOf(
        BACKSLASH_ESCAPE,
        mode {
            begin = """\["""
            end = """\]"""
            relevance = 0.0
            contains = listOf(BACKSLASH_ESCAPE)
        },
    )
}.frozen()

internal val TITLE_MODE: Mode = mode {
    scope = "title"
    begin = IDENT_RE
    relevance = 0.0
}.frozen()

internal val UNDERSCORE_TITLE_MODE: Mode = mode {
    scope = "title"
    begin = UNDERSCORE_IDENT_RE
    relevance = 0.0
}.frozen()

/** Keeps method names out of keyword processing. */
internal val METHOD_GUARD: Mode = mode {
    begin = """\.\s*""" + UNDERSCORE_IDENT_RE
    relevance = 0.0
}.frozen()

/**
 * A `#!` line, which only counts when it really is the first thing in the file.
 *
 * [binary] restricts the match to shebangs naming a particular interpreter.
 */
internal fun shebang(binary: String? = null, block: (Mode.() -> Unit)? = null): Mode {
    val beginShebang = """^#![ ]*/"""
    return mode {
        scope = "meta"
        begin = if (binary == null) beginShebang else concat(beginShebang, """.*\b""", binary, """\b.*""")
        end = "$"
        relevance = 0.0
        onBegin = { match, response -> if (match.index != 0) response.ignoreMatch() }
    }.apply { block?.invoke(this) }
}

/**
 * Adds "the end must repeat the begin" mechanics to [this].
 *
 * The mode must declare at least one capture group; that group is what the two ends are compared
 * on. Mirrors `END_SAME_AS_BEGIN` upstream.
 */
internal fun Mode.endSameAsBegin(): Mode = apply {
    onBegin = { match, response -> response.data[BEGIN_MATCH_KEY] = match[1] }
    onEnd = { match, response ->
        if (response.data[BEGIN_MATCH_KEY] != match[1]) response.ignoreMatch()
    }
}

private const val BEGIN_MATCH_KEY = "_beginMatch"
