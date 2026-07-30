package me.rerere.highlight.languages.lua

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.C_NUMBER_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/** Lua, ported from `lib/languages/lua.js` of `highlight.js` 11.11.1. */
internal fun lua(): Language {
    val openingLongBracket = """\[=*\["""
    val closingLongBracket = """\]=*\]"""
    val longBrackets = mode {
        begin = openingLongBracket
        end = closingLongBracket
        contains = listOf(Mode.SELF)
    }
    val comments = listOf(
        comment("--(?!$openingLongBracket)", "$"),
        comment("--$openingLongBracket", closingLongBracket) {
            contains = listOf(longBrackets)
            relevance = 10.0
        },
    )
    val luaKeywords = keywords {
        pattern = UNDERSCORE_IDENT_RE
        literal("true false nil")
        keyword("and break do else elseif end for goto if in local not or repeat return then until while")
        builtIn(
            // Metatags and globals.
            "_G _ENV _VERSION __index __newindex __mode __call __metatable __tostring __len " +
                "__gc __add __sub __mul __div __mod __pow __concat __unm __eq __lt __le assert " +
                // Standard methods and properties.
                "collectgarbage dofile error getfenv getmetatable ipairs load loadfile loadstring " +
                "module next pairs pcall print rawequal rawget rawset require select setfenv " +
                "setmetatable tonumber tostring type unpack xpcall arg self " +
                // Coroutine and debug libraries.
                "coroutine resume yield status wrap create running debug getupvalue " +
                "debug sethook getmetatable gethook setmetatable setlocal traceback setfenv " +
                "getinfo setupvalue getlocal getregistry getfenv " +
                // IO, math, OS, package, string, and table libraries.
                "io lines write close flush open output type read stderr stdin input stdout popen tmpfile " +
                "math log max acos huge ldexp pi cos tanh pow deg tan cosh sinh random randomseed " +
                "frexp ceil floor rad abs sqrt modf asin min mod fmod log10 atan2 exp sin atan " +
                "os exit setlocale date getenv difftime remove time clock tmpname rename execute " +
                "package preload loadlib loaded loaders cpath config path seeall " +
                "string sub upper len gfind rep find match char dump gmatch reverse byte format gsub lower " +
                "table setn insert getn foreachi maxn foreach concat sort remove",
        )
    }

    return Language(
        name = "Lua",
        aliases = setOf("lua", "pluto"),
        root = mode {
            keywords = luaKeywords
            contains = comments + listOf(
                mode {
                    scope = "function"
                    beginKeywords = "function"
                    end = """\)"""
                    contains = listOf(
                        TITLE_MODE.inherit {
                            begin = """([_a-zA-Z]\w*\.)*([_a-zA-Z]\w*:)?[_a-zA-Z]\w*"""
                        },
                        mode {
                            scope = "params"
                            begin = """\("""
                            endsWithParent = true
                            contains = comments
                        },
                    ) + comments
                },
                C_NUMBER_MODE,
                APOS_STRING_MODE,
                QUOTE_STRING_MODE,
                mode {
                    scope = "string"
                    begin = openingLongBracket
                    end = closingLongBracket
                    contains = listOf(longBrackets)
                    relevance = 5.0
                },
            )
        },
    )
}
