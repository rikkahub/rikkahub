package me.rerere.highlight.languages.typescript

import me.rerere.highlight.core.IDENT_RE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.shebang
import me.rerere.highlight.languages.javascript.JS_BUILT_INS
import me.rerere.highlight.languages.javascript.JS_BUILT_IN_VARIABLES
import me.rerere.highlight.languages.javascript.JS_IDENT_RE
import me.rerere.highlight.languages.javascript.JS_KEYWORDS
import me.rerere.highlight.languages.javascript.JS_LITERALS
import me.rerere.highlight.languages.javascript.javascriptGrammar

/**
 * TypeScript, ported from `lib/languages/typescript.js` of `highlight.js` 11.11.1.
 *
 * Upstream builds the JavaScript grammar and then patches it; this does the same, which is why the
 * modes it reaches for are looked up rather than rebuilt.
 */
internal fun typescript(): Language {
    val types = listOf(
        "any", "void", "number", "boolean", "string", "object", "never", "symbol", "bigint",
        "unknown",
    )

    // `namespace` is a TS keyword, but using it as a variable name is fine, so it is left out here
    // and recognised by the `NAMESPACE` rule instead.
    val tsSpecificKeywords = listOf(
        "type", "interface", "public", "private", "protected", "implements", "declare", "abstract",
        "readonly", "enum", "override", "satisfies",
    )

    // Upstream assigns these onto the keyword object the JavaScript grammar was built with, so
    // every mode sharing it sees them; passing them in reaches exactly the same modes.
    val tsKeywords = keywords {
        pattern = JS_IDENT_RE
        keyword(JS_KEYWORDS + tsSpecificKeywords)
        literal(JS_LITERALS)
        builtIn(JS_BUILT_INS + types)
        scope("variable.language", JS_BUILT_IN_VARIABLES)
    }

    val grammar = javascriptGrammar(tsKeywords)

    val namespace = mode {
        beginList = listOf("namespace", """\s+""", IDENT_RE)
        beginScopes = mapOf(1 to "keyword", 3 to "title.class")
    }

    val interfaceMode = mode {
        beginKeywords = "interface"
        end = """\{"""
        excludeEnd = true
        keywords = keywords {
            keyword("interface extends")
            builtIn(types)
        }
        contains = listOf(grammar.classReference)
    }

    val useStrict = mode {
        scope = "meta"
        relevance = 10.0
        begin = """^\s*['"]use strict['"]"""
    }

    val decorator = mode {
        scope = "meta"
        begin = "@" + JS_IDENT_RE
    }

    grammar.paramsContains += decorator

    // Highlight the function params.
    val attributeHighlight = grammar.contains.first { it.scope == "attr" }

    // The default `attr` rule, extended to also cover optionals.
    val optionalKeyOrArgument = attributeHighlight.inherit {
        match = concat(JS_IDENT_RE, lookahead("""\s*\?:"""))
    }

    grammar.paramsContains += listOf(
        grammar.classReference, // class reference, for highlighting the param types
        attributeHighlight, // highlight the param key
        optionalKeyOrArgument, // optional property assignment
    )

    // Optional property assignment highlighting for objects and classes.
    grammar.contains += listOf(decorator, namespace, interfaceMode, optionalKeyOrArgument)

    // TypeScript gets a simpler shebang rule than JavaScript.
    swapMode(grammar.contains, "shebang", shebang())
    // The JavaScript `use strict` rule purposely includes `asm`, which makes no sense here.
    swapMode(grammar.contains, "use_strict", useStrict)

    // `() => {}` is more typical in TypeScript.
    grammar.contains.modeLabelled("func.def").relevance = 0.0

    return Language(
        name = "TypeScript",
        aliases = setOf("typescript", "ts", "tsx", "mts", "cts"),
        root = grammar.root,
    )
}

/** Replaces the mode labelled [label] in [contains], mirroring `swapMode()` upstream. */
private fun swapMode(contains: MutableList<Mode>, label: String, replacement: Mode) {
    val index = contains.indexOfFirst { it.label == label }
    require(index != -1) { "can not find mode to replace" }
    contains[index] = replacement
}

/** Looks a mode up by [Mode.label], the way upstream finds the modes it patches. */
private fun MutableList<Mode>.modeLabelled(label: String): Mode =
    requireNotNull(firstOrNull { it.label == label }) { "no mode labelled $label" }
