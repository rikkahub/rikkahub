package me.rerere.highlight.languages.sql

import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_NUMBER_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.either
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/**
 * SQL, ported from `lib/languages/sql.js` of `highlight.js` 11.11.1.
 *
 * The grammar deliberately sticks to what pretty much every SQL server supports rather than to any
 * one vendor's dialect; only the list of data types is a little more expansive than that.
 */
internal fun sql(): Language {
    val commentMode = comment("--", "${'$'}")
    val string = mode {
        scope = "string"
        variants = listOf(
            {
                begin = "'"
                end = "'"
                contains = listOf(mode { match = "''" })
            },
        )
    }
    val quotedIdentifier = mode {
        begin = "\""
        end = "\""
        contains = listOf(mode { match = "\"\"" })
    }

    val literals = listOf(
        "true",
        "false",
        // Calling NULL a literal is arguably wrong, and it makes clauses like `IS [NOT] NULL` look
        // strange, so it is left out.
        "unknown",
    )

    val multiWordTypes = listOf(
        "double precision", "large object", "with timezone", "without timezone",
    )

    val types = listOf(
        "bigint", "binary", "blob", "boolean", "char", "character", "clob", "date", "dec",
        "decfloat", "decimal", "float", "int", "integer", "interval", "nchar", "nclob", "national",
        "numeric", "real", "row", "smallint", "time", "timestamp", "varchar",
        "varying", // Modifier, as in `character varying`.
        "varbinary",
    )

    val nonReservedWords = listOf(
        "add", "asc", "collation", "desc", "final", "first", "last", "view",
    )

    // https://jakewheat.github.io/sql-overview/sql-2016-foundation-grammar.html#reserved-word
    val reservedWords = listOf(
        "abs", "acos", "all", "allocate", "alter", "and", "any", "are", "array", "array_agg",
        "array_max_cardinality", "as", "asensitive", "asin", "asymmetric", "at", "atan", "atomic",
        "authorization", "avg", "begin", "begin_frame", "begin_partition", "between", "bigint",
        "binary", "blob", "boolean", "both", "by", "call", "called", "cardinality", "cascaded",
        "case", "cast", "ceil", "ceiling", "char", "char_length", "character", "character_length",
        "check", "classifier", "clob", "close", "coalesce", "collate", "collect", "column",
        "commit", "condition", "connect", "constraint", "contains", "convert", "copy", "corr",
        "corresponding", "cos", "cosh", "count", "covar_pop", "covar_samp", "create", "cross",
        "cube", "cume_dist", "current", "current_catalog", "current_date",
        "current_default_transform_group", "current_path", "current_role", "current_row",
        "current_schema", "current_time", "current_timestamp", "current_path", "current_role",
        "current_transform_group_for_type", "current_user", "cursor", "cycle", "date", "day",
        "deallocate", "dec", "decimal", "decfloat", "declare", "default", "define", "delete",
        "dense_rank", "deref", "describe", "deterministic", "disconnect", "distinct", "double",
        "drop", "dynamic", "each", "element", "else", "empty", "end", "end_frame", "end_partition",
        "end-exec", "equals", "escape", "every", "except", "exec", "execute", "exists", "exp",
        "external", "extract", "false", "fetch", "filter", "first_value", "float", "floor", "for",
        "foreign", "frame_row", "free", "from", "full", "function", "fusion", "get", "global",
        "grant", "group", "grouping", "groups", "having", "hold", "hour", "identity", "in",
        "indicator", "initial", "inner", "inout", "insensitive", "insert", "int", "integer",
        "intersect", "intersection", "interval", "into", "is", "join", "json_array",
        "json_arrayagg", "json_exists", "json_object", "json_objectagg", "json_query", "json_table",
        "json_table_primitive", "json_value", "lag", "language", "large", "last_value", "lateral",
        "lead", "leading", "left", "like", "like_regex", "listagg", "ln", "local", "localtime",
        "localtimestamp", "log", "log10", "lower", "match", "match_number", "match_recognize",
        "matches", "max", "member", "merge", "method", "min", "minute", "mod", "modifies", "module",
        "month", "multiset", "national", "natural", "nchar", "nclob", "new", "no", "none",
        "normalize", "not", "nth_value", "ntile", "null", "nullif", "numeric", "octet_length",
        "occurrences_regex", "of", "offset", "old", "omit", "on", "one", "only", "open", "or",
        "order", "out", "outer", "over", "overlaps", "overlay", "parameter", "partition", "pattern",
        "per", "percent", "percent_rank", "percentile_cont", "percentile_disc", "period", "portion",
        "position", "position_regex", "power", "precedes", "precision", "prepare", "primary",
        "procedure", "ptf", "range", "rank", "reads", "real", "recursive", "ref", "references",
        "referencing", "regr_avgx", "regr_avgy", "regr_count", "regr_intercept", "regr_r2",
        "regr_slope", "regr_sxx", "regr_sxy", "regr_syy", "release", "result", "return", "returns",
        "revoke", "right", "rollback", "rollup", "row", "row_number", "rows", "running",
        "savepoint", "scope", "scroll", "search", "second", "seek", "select", "sensitive",
        "session_user", "set", "show", "similar", "sin", "sinh", "skip", "smallint", "some",
        "specific", "specifictype", "sql", "sqlexception", "sqlstate", "sqlwarning", "sqrt",
        "start", "static", "stddev_pop", "stddev_samp", "submultiset", "subset", "substring",
        "substring_regex", "succeeds", "sum", "symmetric", "system", "system_time", "system_user",
        "table", "tablesample", "tan", "tanh", "then", "time", "timestamp", "timezone_hour",
        "timezone_minute", "to", "trailing", "translate", "translate_regex", "translation", "treat",
        "trigger", "trim", "trim_array", "true", "truncate", "uescape", "union", "unique",
        "unknown", "unnest", "update", "upper", "user", "using", "value", "values", "value_of",
        "var_pop", "var_samp", "varbinary", "varchar", "varying", "versioning", "when", "whenever",
        "where", "width_bucket", "window", "with", "within", "without", "year",
    )

    // Reserved words we identified as functions; they should only be highlighted in a dispatch-like
    // context, such as `array_agg(...)`.
    val reservedFunctions = listOf(
        "abs", "acos", "array_agg", "asin", "atan", "avg", "cast", "ceil", "ceiling", "coalesce",
        "corr", "cos", "cosh", "count", "covar_pop", "covar_samp", "cume_dist", "dense_rank",
        "deref", "element", "exp", "extract", "first_value", "floor", "json_array", "json_arrayagg",
        "json_exists", "json_object", "json_objectagg", "json_query", "json_table",
        "json_table_primitive", "json_value", "lag", "last_value", "lead", "listagg", "ln", "log",
        "log10", "lower", "max", "min", "mod", "nth_value", "ntile", "nullif", "percent_rank",
        "percentile_cont", "percentile_disc", "position", "position_regex", "power", "rank",
        "regr_avgx", "regr_avgy", "regr_count", "regr_intercept", "regr_r2", "regr_slope",
        "regr_sxx", "regr_sxy", "regr_syy", "row_number", "sin", "sinh", "sqrt", "stddev_pop",
        "stddev_samp", "substring", "substring_regex", "sum", "tan", "tanh", "translate",
        "translate_regex", "treat", "trim", "trim_array", "unnest", "upper", "value_of", "var_pop",
        "var_samp", "width_bucket",
    )

    // Functions that may appear without parentheses.
    val possibleWithoutParens = listOf(
        "current_catalog", "current_date", "current_default_transform_group", "current_path",
        "current_role", "current_schema", "current_transform_group_for_type", "current_user",
        "session_user", "system_time", "system_user", "current_time", "localtime",
        "current_timestamp", "localtimestamp",
    )

    // These exist purely to boost relevance: such keyword combos are very "SQL like" and are worth
    // one extra point.
    val combos = listOf(
        "create table", "insert into", "primary key", "foreign key", "not null", "alter table",
        "add constraint", "grouping sets", "on overflow", "character set", "respect nulls",
        "ignore nulls", "nulls first", "nulls last", "depth first", "breadth first",
    )

    val functions = reservedFunctions
    val sqlKeywords = (reservedWords + nonReservedWords).filter { it !in reservedFunctions }

    val variable = mode {
        scope = "variable"
        match = """@[a-z0-9][a-z0-9_]*"""
    }

    val operator = mode {
        scope = "operator"
        match = """[-+*/=%^~]|&&?|\|\|?|!=?|<(?:=>?|<|>)?|>[>=]?"""
        relevance = 0.0
    }

    val functionCall = mode {
        match = concat("""\b""", either(functions), """\s*\(""")
        relevance = 0.0
        keywords = keywords { builtIn(functions) }
    }

    val multiWordKeywords = mode {
        scope = "keyword"
        match = keywordsToRegex(combos)
        relevance = 0.0
    }

    return Language(
        name = "SQL",
        aliases = setOf("sql"),
        caseInsensitive = true,
        root = mode {
            // Neither braces nor an HTML closing tag belong in SQL.
            illegal = """[{}]|<\/"""
            keywords = keywords {
                pattern = """\b[\w\.]+"""
                keyword(reduceRelevancy(sqlKeywords) { it.length < 3 })
                literal(literals)
                type(types)
                builtIn(possibleWithoutParens)
            }
            contains = listOf(
                mode {
                    scope = "type"
                    match = keywordsToRegex(multiWordTypes)
                },
                multiWordKeywords,
                functionCall,
                variable,
                string,
                quotedIdentifier,
                C_NUMBER_MODE,
                C_BLOCK_COMMENT_MODE,
                commentMode,
                operator,
            )
        },
    )
}

/**
 * Turns a list of multi-word keywords into a regex that does not care how much whitespace separates
 * the words: `"start query"` becomes `\b(?:start\s+query)\b`.
 *
 * Upstream replaces only the first run of whitespace; every entry it passes in holds exactly one
 * space, so replacing all of them is the same thing.
 */
private fun keywordsToRegex(words: List<String>): String =
    concat("""\b""", either(words.map { it.replace(" ", """\s+""") }), """\b""")

/** Marks keywords for which [qualify] holds as carrying no relevance. */
private fun reduceRelevancy(words: List<String>, qualify: (String) -> Boolean): List<String> =
    words.map { word ->
        when {
            // A score the grammar spelled out itself always wins.
            EXPLICIT_RELEVANCE.containsMatchIn(word) -> word
            qualify(word) -> "$word|0"
            else -> word
        }
    }

private val EXPLICIT_RELEVANCE = Regex("""\|\d+${'$'}""")
