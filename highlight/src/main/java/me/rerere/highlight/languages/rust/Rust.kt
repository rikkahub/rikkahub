package me.rerere.highlight.languages.rust

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.IDENT_RE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode

/** Rust, ported from `lib/languages/rust.js` of `highlight.js` 11.11.1. */
internal fun rust(): Language {
    // `r#` turns a keyword into an ordinary name, so every identifier may carry that prefix.
    val rawIdentifier = """(r#)?"""
    val underscoreIdentRe = concat(rawIdentifier, UNDERSCORE_IDENT_RE)
    val identRe = concat(rawIdentifier, IDENT_RE)

    val functionInvoke = mode {
        scope = "title.function.invoke"
        relevance = 0.0
        begin = concat(
            """\b""",
            """(?!let|for|while|if|else|match\b)""",
            identRe,
            lookahead("""\s*\("""),
        )
    }
    // Upstream spells the trailing `?` as `\?` inside a JavaScript string, where it is just a
    // question mark; the suffix is optional.
    val numberSuffix = """([ui](8|16|32|64|128|size)|f(32|64))?"""

    val keywordList = listOf(
        "abstract", "as", "async", "await", "become", "box", "break", "const", "continue", "crate",
        "do", "dyn", "else", "enum", "extern", "false", "final", "fn", "for", "if", "impl", "in",
        "let", "loop", "macro", "match", "mod", "move", "mut", "override", "priv", "pub", "ref",
        "return", "self", "Self", "static", "struct", "super", "trait", "true", "try", "type",
        "typeof", "union", "unsafe", "unsized", "use", "virtual", "where", "while", "yield",
    )
    val literals = listOf("true", "false", "Some", "None", "Ok", "Err")
    val builtIns = listOf(
        // Functions. The trailing space is upstream's, and it keeps the entry from ever matching a
        // lexeme, since a lexeme never contains one.
        "drop ",
        // Traits.
        "Copy", "Send", "Sized", "Sync", "Drop", "Fn", "FnMut", "FnOnce", "ToOwned", "Clone",
        "Debug", "PartialEq", "PartialOrd", "Eq", "Ord", "AsRef", "AsMut", "Into", "From",
        "Default", "Iterator", "Extend", "IntoIterator", "DoubleEndedIterator", "ExactSizeIterator",
        "SliceConcatExt", "ToString",
        // Macros.
        "assert!", "assert_eq!", "bitflags!", "bytes!", "cfg!", "col!", "concat!", "concat_idents!",
        "debug_assert!", "debug_assert_eq!", "env!", "eprintln!", "panic!", "file!", "format!",
        "format_args!", "include_bytes!", "include_str!", "line!", "local_data_key!",
        "module_path!", "option_env!", "print!", "println!", "select!", "stringify!", "try!",
        "unimplemented!", "unreachable!", "vec!", "write!", "writeln!", "macro_rules!",
        "assert_ne!", "debug_assert_ne!",
    )
    val types = listOf(
        "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "str", "char", "bool", "Box", "Option", "Result", "String", "Vec",
    )

    return Language(
        name = "Rust",
        aliases = setOf("rust", "rs"),
        root = mode {
            keywords = keywords {
                pattern = IDENT_RE + "!?"
                type(types)
                keyword(keywordList)
                literal(literals)
                builtIn(builtIns)
            }
            illegal = "</"
            contains = listOf(
                C_LINE_COMMENT_MODE,
                comment("""/\*""", """\*/""") { contains = listOf(Mode.SELF) },
                QUOTE_STRING_MODE.inherit {
                    begin = "b?\""
                    illegal = null
                },
                mode {
                    scope = "symbol"
                    // The negative lookahead keeps a lifetime apart from a character literal.
                    begin = """'[a-zA-Z_][a-zA-Z0-9_]*(?!')"""
                },
                mode {
                    scope = "string"
                    variants = listOf(
                        { begin = """b?r(#*)"(.|\n)*?"\1(?!#)""" },
                        {
                            begin = """b?'"""
                            end = "'"
                            contains = listOf(
                                mode {
                                    scope = "char.escape"
                                    match = """\\('|\w|x\w{2}|u\w{4}|U\w{8})"""
                                },
                            )
                        },
                    )
                },
                mode {
                    scope = "number"
                    variants = listOf(
                        { begin = """\b0b([01_]+)$numberSuffix""" },
                        { begin = """\b0o([0-7_]+)$numberSuffix""" },
                        { begin = """\b0x([A-Fa-f0-9_]+)$numberSuffix""" },
                        { begin = """\b(\d[\d_]*(\.[0-9_]+)?([eE][+-]?[0-9_]+)?)$numberSuffix""" },
                    )
                    relevance = 0.0
                },
                mode {
                    beginList = listOf("""fn""", """\s+""", underscoreIdentRe)
                    beginScopes = mapOf(1 to "keyword", 3 to "title.function")
                },
                mode {
                    scope = "meta"
                    begin = """#!?\["""
                    end = """\]"""
                    contains = listOf(
                        mode {
                            scope = "string"
                            begin = "\""
                            end = "\""
                            contains = listOf(BACKSLASH_ESCAPE)
                        },
                    )
                },
                mode {
                    beginList = listOf("""let""", """\s+""", """(?:mut\s+)?""", underscoreIdentRe)
                    beginScopes = mapOf(1 to "keyword", 3 to "keyword", 4 to "variable")
                },
                // Must come before the `impl`/`for` rule below.
                mode {
                    beginList = listOf("""for""", """\s+""", underscoreIdentRe, """\s+""", """in""")
                    beginScopes = mapOf(1 to "keyword", 3 to "variable", 5 to "keyword")
                },
                mode {
                    beginList = listOf("""type""", """\s+""", underscoreIdentRe)
                    beginScopes = mapOf(1 to "keyword", 3 to "title.class")
                },
                mode {
                    beginList = listOf(
                        """(?:trait|enum|struct|union|impl|for)""",
                        """\s+""",
                        underscoreIdentRe,
                    )
                    beginScopes = mapOf(1 to "keyword", 3 to "title.class")
                },
                mode {
                    begin = IDENT_RE + "::"
                    keywords = keywords {
                        keyword("Self")
                        builtIn(builtIns)
                        type(types)
                    }
                },
                mode {
                    scope = "punctuation"
                    begin = "->"
                },
                functionInvoke,
            )
        },
    )
}
