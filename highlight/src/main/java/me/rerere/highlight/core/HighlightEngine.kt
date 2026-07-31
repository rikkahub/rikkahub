package me.rerere.highlight.core

import me.rerere.highlight.HighlightToken

/**
 * Rethrow grammar failures instead of degrading to plain text.
 *
 * Mirrors `hljs.debugMode()`; tests turn it on so a broken grammar surfaces as an error rather than
 * as a silently unhighlighted fixture.
 */
internal var highlightDebugMode: Boolean = false

/**
 * The mode-stack highlighter, ported from `_highlight()` in `highlight.js` 11.11.1.
 *
 * A language is compiled once into a tree of [Mode]s. Highlighting then walks the source with the
 * terminator matcher of the mode currently on top of the stack: text between matches is buffered
 * and later run through the mode's keywords (or a sub-language), `begin` matches push a new mode,
 * and `end` matches pop back out.
 */
internal class HighlightEngine(languages: List<Language>) {

    private val languagesByAlias: Map<String, Language> = buildMap {
        languages.forEach { language ->
            language.aliases.forEach { alias ->
                require(put(alias.lowercase(), language) == null) {
                    "Duplicate language alias: $alias"
                }
            }
        }
    }

    private val compiledRoots = HashMap<String, Mode>()

    fun supports(language: String): Boolean =
        languagesByAlias.containsKey(language.trim().lowercase())

    /** Highlights [code], or returns `null` when [language] is not registered. */
    fun highlight(code: String, language: String): List<HighlightToken>? {
        val definition = languagesByAlias[language.trim().lowercase()] ?: return null
        return Run(definition).highlight(code, ignoreIllegals = true, continuation = null).tokens
    }

    private fun compiledRootOf(language: Language): Mode = synchronized(compiledRoots) {
        compiledRoots.getOrPut(language.name) { ModeCompiler(language).compile() }
    }

    /** A mode on the highlighting stack, replacing the prototype-linked objects used upstream. */
    private class Frame(val mode: Mode, val parent: Frame?)

    private class Result(
        val tokens: List<HighlightToken>,
        val relevance: Double,
        val top: Frame,
    )

    private class IllegalLexemeException(message: String) : Exception(message)

    /** One highlighting pass over one string; holds the mutable parser state. */
    private inner class Run(private val language: Language) {
        private val emitter = TokenEmitter()
        private val keywordHits = HashMap<String, Int>()
        private val continuations = HashMap<String, Frame>()

        private lateinit var code: String
        private lateinit var root: Mode
        private lateinit var top: Frame

        private var ignoreIllegals = true
        private var modeBuffer = StringBuilder()
        private var relevance = 0.0
        private var index = 0
        private var iterations = 0
        private var resumeScanAtSamePosition = false
        private var lastMatch: MultiMatch? = null

        fun highlight(code: String, ignoreIllegals: Boolean, continuation: Frame?): Result {
            this.code = code
            this.ignoreIllegals = ignoreIllegals
            root = compiledRootOf(language)
            top = continuation ?: Frame(root, null)
            processContinuations()

            return try {
                scan()
                emitter.finalize()
                Result(emitter.build(), relevance, top)
            } catch (_: IllegalLexemeException) {
                // Upstream reports illegal input as a zero relevance result rather than as a
                // failure, even in debug mode: that is how auto detection rules a candidate out.
                Result(plainTokens(code), 0.0, top)
            } catch (error: RuntimeException) {
                // Upstream runs in "safe mode" by default: a broken grammar degrades to plain text
                // rather than taking the whole page down.
                if (highlightDebugMode) throw error
                Result(plainTokens(code), 0.0, top)
            }
        }

        private fun plainTokens(code: String): List<HighlightToken> =
            if (code.isEmpty()) emptyList() else listOf(HighlightToken.Plain(code))

        private fun scan() {
            top.mode.matcher!!.considerAll()

            while (true) {
                iterations++
                val matcher = top.mode.matcher!!
                if (resumeScanAtSamePosition) {
                    resumeScanAtSamePosition = false
                } else {
                    matcher.considerAll()
                }
                matcher.lastIndex = index

                val match = matcher.exec(code) ?: break
                val beforeMatch = code.substring(index, match.index)
                index = match.index + processLexeme(beforeMatch, match)
            }
            processLexeme(code.substring(index), match = null)
        }

        /** Re-opens the scopes of an inherited continuation stack. */
        private fun processContinuations() {
            val open = ArrayDeque<String>()
            var current: Frame? = top
            while (current != null && current.mode !== root) {
                current.mode.scope?.let { open.addFirst(it) }
                current = current.parent
            }
            open.forEach { emitter.startScope(it) }
        }

        // ---- buffer handling ------------------------------------------------------------------

        private fun processBuffer() {
            val mode = top.mode
            if (mode.subLanguage != null || mode.subLanguageList != null) {
                processSubLanguage()
            } else {
                processKeywords()
            }
            modeBuffer = StringBuilder()
        }

        private fun processKeywords() {
            val keywords = top.mode.compiledKeywords
            val buffer = modeBuffer.toString()
            if (keywords == null) {
                emitter.addText(buffer)
                return
            }

            val matcher = top.mode.keywordPatternRe!!.matcher(buffer)
            val pending = StringBuilder()
            var lastIndex = 0

            while (matcher.find()) {
                pending.append(buffer, lastIndex, matcher.start())
                val matched = matcher.group()
                val word = if (language.caseInsensitive) matched.lowercase() else matched
                val data = keywords[word]
                if (data != null) {
                    emitter.addText(pending.toString())
                    pending.setLength(0)

                    val hits = (keywordHits[word] ?: 0) + 1
                    keywordHits[word] = hits
                    if (hits <= MAX_KEYWORD_HITS) relevance += data.relevance

                    if (data.scope.startsWith("_")) {
                        // A leading underscore means "count for relevance only, do not colour".
                        pending.append(matched)
                    } else {
                        emitKeyword(matched, aliasOf(data.scope))
                    }
                } else {
                    pending.append(matched)
                }
                lastIndex = matcher.end()
            }
            pending.append(buffer, lastIndex, buffer.length)
            emitter.addText(pending.toString())
        }

        private fun processSubLanguage() {
            if (modeBuffer.isEmpty()) return
            val buffer = modeBuffer.toString()
            val name = top.mode.subLanguage

            val result: Result
            if (name != null) {
                val definition = languagesByAlias[name.lowercase()]
                if (definition == null) {
                    emitter.addText(buffer)
                    return
                }
                result = Run(definition).highlight(
                    code = buffer,
                    ignoreIllegals = true,
                    continuation = continuations[name],
                )
                continuations[name] = result.top
            } else {
                // A list of candidates carries no continuation upstream either: every chunk is
                // detected on its own.
                result = highlightAuto(buffer, top.mode.subLanguageList!!)
            }

            // Zeroing the relevance of the containing mode is how a grammar opts out of counting
            // the embedded language towards its own score.
            if ((top.mode.relevance ?: 0.0) > 0) relevance += result.relevance
            emitter.addSublanguage(result.tokens)
        }

        /**
         * Highlights [code] with the best scoring language of [subset], mirroring `highlightAuto()`.
         *
         * Upstream puts a plain text result in front of the candidates and sorts them stably by
         * relevance, so a tie — the usual case being that nothing scored at all — is won by plain
         * text. Illegal input scores zero and can therefore never win.
         */
        private fun highlightAuto(code: String, subset: List<String>): Result {
            val best = subset
                .mapNotNull { languagesByAlias[it.lowercase()] }
                .map { Run(it).highlight(code, ignoreIllegals = false, continuation = null) }
                .maxByOrNull { it.relevance }

            return if (best != null && best.relevance > 0.0) {
                best
            } else {
                Result(plainTokens(code), 0.0, top)
            }
        }

        private fun emitKeyword(keyword: String, scope: String) {
            if (keyword.isEmpty()) return
            emitter.startScope(scope)
            emitter.addText(keyword)
            emitter.endScope()
        }

        private fun emitMultiClass(scope: CompiledScope, match: MultiMatch) {
            for (group in 1..match.groupCount) {
                if (group !in scope.emit) continue
                val text = match[group].orEmpty()
                val name = scope.positions[group]
                if (name != null) {
                    emitKeyword(text, aliasOf(name))
                } else {
                    modeBuffer = StringBuilder(text)
                    processKeywords()
                    modeBuffer = StringBuilder()
                }
            }
        }

        private fun aliasOf(scope: String): String = language.classNameAliases[scope] ?: scope

        // ---- mode transitions -----------------------------------------------------------------

        private fun startNewMode(mode: Mode, match: MultiMatch) {
            mode.scope?.let { emitter.startScope(aliasOf(it)) }

            mode.compiledBeginScope?.let { scope ->
                if (scope.wrap != null) {
                    emitKeyword(modeBuffer.toString(), aliasOf(scope.wrap))
                } else {
                    emitMultiClass(scope, match)
                }
                modeBuffer = StringBuilder()
            }

            top = Frame(mode, top)
        }

        /** Which mode, if any, is ended by this match? */
        private fun endOfMode(frame: Frame, match: MatchData, matchPlusRemainder: String): Frame? {
            var matched = startsWith(frame.mode.endRe, matchPlusRemainder)

            if (matched) {
                frame.mode.onEnd?.let { callback ->
                    val response = CallbackResponse(frame.mode)
                    callback(match, response)
                    if (response.isMatchIgnored) matched = false
                }
                if (matched) {
                    var ended = frame
                    while (ended.mode.endsParent) ended = ended.parent ?: break
                    return ended
                }
            }

            // An ignored `on:end` can still be ended by a parent that shares the terminator.
            if (frame.mode.endsWithParent) {
                return frame.parent?.let { endOfMode(it, match, matchPlusRemainder) }
            }
            return null
        }

        private fun doIgnore(lexeme: String): Int {
            return if (top.mode.matcher!!.regexIndex == 0) {
                // Nothing else can match here, so step the cursor forward one character.
                modeBuffer.append(lexeme.take(1))
                1
            } else {
                // Additional rules remain untried at this very position.
                resumeScanAtSamePosition = true
                0
            }
        }

        private fun doBeginMatch(match: MultiMatch): Int {
            val lexeme = match.value
            val newMode = match.rule!!

            val response = CallbackResponse(newMode)
            for (callback in listOfNotNull(newMode.beforeBegin, newMode.onBegin)) {
                callback(match, response)
                if (response.isMatchIgnored) return doIgnore(lexeme)
            }

            if (newMode.skip) {
                modeBuffer.append(lexeme)
            } else {
                if (newMode.excludeBegin) modeBuffer.append(lexeme)
                processBuffer()
                if (!newMode.returnBegin && !newMode.excludeBegin) {
                    modeBuffer = StringBuilder(lexeme)
                }
            }
            startNewMode(newMode, match)
            return if (newMode.returnBegin) 0 else lexeme.length
        }

        /** Returns `null` when the match did not actually end anything. */
        private fun doEndMatch(match: MultiMatch): Int? {
            val lexeme = match.value
            val endMode = endOfMode(top, match, code.substring(match.index)) ?: return null

            val origin = top.mode
            val endScope = origin.compiledEndScope
            when {
                endScope?.wrap != null -> {
                    processBuffer()
                    emitKeyword(lexeme, aliasOf(endScope.wrap))
                }

                endScope != null -> {
                    processBuffer()
                    emitMultiClass(endScope, match)
                }

                origin.skip -> modeBuffer.append(lexeme)

                else -> {
                    if (!origin.returnEnd && !origin.excludeEnd) modeBuffer.append(lexeme)
                    processBuffer()
                    if (origin.excludeEnd) modeBuffer = StringBuilder(lexeme)
                }
            }

            var current: Frame? = top
            do {
                val frame = current ?: break
                if (frame.mode.scope != null) emitter.endScope()
                if (!frame.mode.skip &&
                    frame.mode.subLanguage == null &&
                    frame.mode.subLanguageList == null
                ) {
                    relevance += frame.mode.relevance ?: 0.0
                }
                current = frame.parent
            } while (current !== endMode.parent)
            top = current ?: Frame(root, null)

            endMode.mode.starts?.let { startNewMode(it, match) }
            return if (origin.returnEnd) 0 else lexeme.length
        }

        /** Processes the text since the previous match plus the match itself. */
        private fun processLexeme(textBeforeMatch: String, match: MultiMatch?): Int {
            modeBuffer.append(textBeforeMatch)

            if (match == null) {
                processBuffer()
                return 0
            }

            val lexeme = match.value
            val previous = lastMatch

            // A zero width match that we are stuck on: emit the skipped character and move on.
            if (previous != null &&
                previous.type == MatchType.BEGIN &&
                match.type == MatchType.END &&
                previous.index == match.index &&
                lexeme.isEmpty()
            ) {
                modeBuffer.append(code, match.index, (match.index + 1).coerceAtMost(code.length))
                return 1
            }
            lastMatch = match

            when {
                match.type == MatchType.BEGIN -> return doBeginMatch(match)

                match.type == MatchType.ILLEGAL && !ignoreIllegals ->
                    throw IllegalLexemeException(
                        "Illegal lexeme \"$lexeme\" for mode \"${top.mode.scope ?: "<unnamed>"}\"",
                    )

                match.type == MatchType.END -> doEndMatch(match)?.let { return it }
            }

            // `illegal` matching `$` is a zero width match that is neither a begin nor an end.
            if (match.type == MatchType.ILLEGAL && lexeme.isEmpty()) {
                modeBuffer.append('\n')
                return 1
            }

            // Last ditch guard: far more iterations than progress means something is very wrong.
            check(iterations <= MAX_ITERATIONS || iterations <= match.index * 3) {
                "potential infinite loop, way more iterations than matches"
            }

            modeBuffer.append(lexeme)
            return lexeme.length
        }
    }

    private companion object {
        const val MAX_KEYWORD_HITS = 7
        const val MAX_ITERATIONS = 100_000
    }
}
