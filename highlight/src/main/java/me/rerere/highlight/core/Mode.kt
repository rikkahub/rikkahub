package me.rerere.highlight.core

import java.util.regex.Pattern

/**
 * A single grammar rule, mirroring `Mode` in `highlight.js` 11.11.1.
 *
 * Modes are written uncompiled by grammar authors and are turned into their compiled form in place
 * by [compileLanguage], exactly like upstream. Regular expressions are held as JavaScript flavoured
 * *sources*; [compilePattern] performs the translation to [Pattern].
 */
internal class Mode {
    // ---- scopes -------------------------------------------------------------------------------

    /** Scope wrapping everything the mode matches, from `begin` up to and including `end`. */
    var scope: String? = null

    /**
     * `scope: {1: "keyword", 2: "title"}` sugar, only valid together with [matchList].
     *
     * Moved onto [beginScopes] during compilation.
     */
    var scopes: Map<Int, String?>? = null

    /** Scope wrapping only the `begin` match. */
    var beginScope: String? = null

    /** Per capture group scopes for a [beginList] match. */
    var beginScopes: Map<Int, String?>? = null

    /** Scope wrapping only the `end` match. */
    var endScope: String? = null

    /** Per capture group scopes for an [endList] match. */
    var endScopes: Map<Int, String?>? = null

    // ---- matching -----------------------------------------------------------------------------

    var begin: String? = null

    /** `begin: [/a/, /b/]` upstream: concatenated at compile time, one capture group per entry. */
    var beginList: List<String>? = null

    var end: String? = null

    var endList: List<String>? = null

    /** Sugar for a mode that only has a `begin`; moved onto [begin] during compilation. */
    var match: String? = null

    var matchList: List<String>? = null

    /** Qualifier that must precede [begin] without being part of the mode itself. */
    var beforeMatch: String? = null

    var illegal: String? = null

    var illegalList: List<String>? = null

    // ---- keywords -----------------------------------------------------------------------------

    var keywords: Keywords? = null

    /** Sugar producing a `begin` that matches any of the listed keywords. */
    var beginKeywords: String? = null

    // ---- structure ----------------------------------------------------------------------------

    /** Nested modes. Use [SELF] to recurse into this very mode. */
    var contains: List<Mode> = emptyList()

    /** Mode entered right after this one ends. */
    var starts: Mode? = null

    /**
     * Alternative shapes of this mode. Every entry is applied to a copy of this mode, mirroring
     * `inherit(mode, {variants: null}, variant)` upstream.
     */
    var variants: List<Mode.() -> Unit>? = null

    /** Highlight the content of this mode with another language instead of with [keywords]. */
    var subLanguage: String? = null

    /**
     * `subLanguage: ['css', 'xml']` upstream: the content is highlighted with whichever of the
     * listed languages scores best, rather than with one fixed language.
     */
    var subLanguageList: List<String>? = null

    /**
     * Free form name a grammar can use to find one of its own modes again.
     *
     * Upstream only uses it for debugging and so that `typescript` can swap modes out of the
     * `javascript` tree it extends.
     */
    var label: String? = null

    // ---- flags --------------------------------------------------------------------------------

    var relevance: Double? = null

    /** Emit the `begin` match into the parent buffer instead of into this mode. */
    var excludeBegin: Boolean = false

    /** Emit the `end` match into the mode that follows instead of into this mode. */
    var excludeEnd: Boolean = false

    /** Do not consume the `begin` match, so nested modes get a chance at it. */
    var returnBegin: Boolean = false

    /** Do not consume the `end` match, so the parent gets a chance at it. */
    var returnEnd: Boolean = false

    /** Ending this mode also ends its parent. */
    var endsParent: Boolean = false

    /** This mode also ends whenever its parent would end. */
    var endsWithParent: Boolean = false

    /** Hold matched content in the parent buffer rather than emitting it when the mode ends. */
    var skip: Boolean = false

    // ---- callbacks ----------------------------------------------------------------------------

    var onBegin: ModeCallback? = null

    var onEnd: ModeCallback? = null

    /** Internal callback installed by the `beginKeywords` compiler extension. */
    internal var beforeBegin: ModeCallback? = null

    /** Scratch space shared by [onBegin] and [onEnd] of the same mode. */
    internal val data: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Shared modes must never be mutated by compilation; frozen modes are copied on use, mirroring
     * the `Object.isFrozen` branch of `expandOrCloneMode()` upstream.
     */
    internal var frozen: Boolean = false

    // ---- compiled state -----------------------------------------------------------------------

    /**
     * The expansion of [variants], computed once per mode instance.
     *
     * Caching is not an optimisation: a mode whose variants contain the mode itself — Kotlin's
     * parenthesised type is one — only terminates because the second expansion hands back the
     * very objects the first one produced, which are compiled by then.
     */
    internal var cachedVariants: List<Mode>? = null

    internal var isCompiled: Boolean = false
    internal var compiledKeywords: Map<String, KeywordData>? = null
    internal var keywordPatternRe: Pattern? = null
    internal var beginRe: Pattern? = null
    internal var endRe: Pattern? = null
    internal var illegalRe: Pattern? = null
    internal var terminatorEnd: String? = null
    internal var compiledBeginScope: CompiledScope? = null
    internal var compiledEndScope: CompiledScope? = null
    internal var matcher: ResumableMultiRegex? = null

    /** Shallow copy, mirroring `inherit()` upstream. Compiled state is intentionally dropped. */
    fun copy(): Mode = Mode().also { copy ->
        copy.scope = scope
        copy.scopes = scopes
        copy.beginScope = beginScope
        copy.beginScopes = beginScopes
        copy.endScope = endScope
        copy.endScopes = endScopes
        copy.begin = begin
        copy.beginList = beginList
        copy.end = end
        copy.endList = endList
        copy.match = match
        copy.matchList = matchList
        copy.beforeMatch = beforeMatch
        copy.illegal = illegal
        copy.illegalList = illegalList
        copy.keywords = keywords
        copy.beginKeywords = beginKeywords
        copy.contains = contains
        copy.starts = starts
        copy.variants = variants
        copy.subLanguage = subLanguage
        copy.subLanguageList = subLanguageList
        copy.label = label
        copy.relevance = relevance
        copy.excludeBegin = excludeBegin
        copy.excludeEnd = excludeEnd
        copy.returnBegin = returnBegin
        copy.returnEnd = returnEnd
        copy.endsParent = endsParent
        copy.endsWithParent = endsWithParent
        copy.skip = skip
        copy.onBegin = onBegin
        copy.onEnd = onEnd
        // Upstream keeps the compiled scope on the very field it was declared on, so a copy taken
        // after compilation carries it along; keep that property here too.
        copy.compiledBeginScope = compiledBeginScope
        copy.compiledEndScope = compiledEndScope
    }

    /** Shallow copy with [block] applied on top, mirroring `inherit(mode, overrides)` upstream. */
    fun inherit(block: Mode.() -> Unit): Mode = copy().apply(block)

    /**
     * Copies every property [source] actually declares onto this mode.
     *
     * Upstream can merge two mode objects because a JavaScript object only owns the keys it was
     * written with. Here "declared" means non-null, `true`, or a non-empty `contains`.
     */
    fun overwriteFrom(source: Mode) {
        source.scope?.let { scope = it }
        source.scopes?.let { scopes = it }
        source.beginScope?.let { beginScope = it }
        source.beginScopes?.let { beginScopes = it }
        source.endScope?.let { endScope = it }
        source.endScopes?.let { endScopes = it }
        source.begin?.let { begin = it }
        source.beginList?.let { beginList = it }
        source.end?.let { end = it }
        source.endList?.let { endList = it }
        source.match?.let { match = it }
        source.matchList?.let { matchList = it }
        source.beforeMatch?.let { beforeMatch = it }
        source.illegal?.let { illegal = it }
        source.illegalList?.let { illegalList = it }
        source.keywords?.let { keywords = it }
        source.beginKeywords?.let { beginKeywords = it }
        source.contains.takeIf { it.isNotEmpty() }?.let { contains = it }
        source.starts?.let { starts = it }
        source.subLanguage?.let { subLanguage = it }
        source.subLanguageList?.let { subLanguageList = it }
        source.label?.let { label = it }
        source.relevance?.let { relevance = it }
        source.onBegin?.let { onBegin = it }
        source.onEnd?.let { onEnd = it }
        if (source.excludeBegin) excludeBegin = true
        if (source.excludeEnd) excludeEnd = true
        if (source.returnBegin) returnBegin = true
        if (source.returnEnd) returnEnd = true
        if (source.endsParent) endsParent = true
        if (source.endsWithParent) endsWithParent = true
        if (source.skip) skip = true
    }

    /**
     * Clears every grammar property while keeping the object identity.
     *
     * The `beforeMatch` compiler extension rewrites a mode into a wrapper around itself, and the
     * parent already holds a reference to this instance, so the rewrite has to happen in place.
     */
    internal fun resetForRewrite() {
        scope = null
        scopes = null
        beginScope = null
        beginScopes = null
        endScope = null
        endScopes = null
        begin = null
        beginList = null
        end = null
        endList = null
        match = null
        matchList = null
        beforeMatch = null
        illegal = null
        illegalList = null
        keywords = null
        beginKeywords = null
        contains = emptyList()
        starts = null
        variants = null
        subLanguage = null
        subLanguageList = null
        label = null
        relevance = null
        excludeBegin = false
        excludeEnd = false
        returnBegin = false
        returnEnd = false
        endsParent = false
        endsWithParent = false
        skip = false
        onBegin = null
        onEnd = null
        compiledBeginScope = null
        compiledEndScope = null
    }

    companion object {
        /** Sentinel usable inside [contains] to recurse into the containing mode. */
        val SELF: Mode = Mode()
    }
}

internal fun mode(block: Mode.() -> Unit): Mode = Mode().apply(block)

/** Uses an existing [source] mode as a [Mode.variants] entry. */
internal fun variant(source: Mode): Mode.() -> Unit = { overwriteFrom(source) }

/**
 * Marks [this] and everything it contains as shared, so compilation copies before mutating.
 *
 * Mirrors the `deepFreeze` applied to the exported `MODES` upstream.
 */
internal fun Mode.frozen(): Mode = apply {
    if (frozen) return@apply
    frozen = true
    contains.forEach { it.frozen() }
    starts?.frozen()
}

/**
 * A compiled `beginScope` / `endScope`.
 *
 * [wrap] is set when the whole match shares one scope; [positions] and [emit] are set when every
 * capture group carries its own scope.
 */
internal class CompiledScope(
    val wrap: String? = null,
    val positions: Map<Int, String?> = emptyMap(),
    val emit: Set<Int> = emptySet(),
) {
    val isMulti: Boolean get() = wrap == null
}

/** A language definition: the root [Mode] plus the metadata the compiler needs. */
internal class Language(
    val name: String,
    val aliases: Set<String>,
    val root: Mode,
    val caseInsensitive: Boolean = false,
    val unicodeRegex: Boolean = false,
    val classNameAliases: Map<String, String> = emptyMap(),
)

/** The subset of a regex match that grammar callbacks may inspect. */
internal interface MatchData {
    val index: Int
    val input: String
    operator fun get(group: Int): String?
}

internal val MatchData.value: String get() = this[0].orEmpty()

internal typealias ModeCallback = (match: MatchData, response: CallbackResponse) -> Unit

/** Mirrors `Response` upstream: lets a callback veto the match it was handed. */
internal class CallbackResponse(mode: Mode) {
    val data: MutableMap<String, Any?> = mode.data

    var isMatchIgnored: Boolean = false
        private set

    fun ignoreMatch() {
        isMatchIgnored = true
    }
}
