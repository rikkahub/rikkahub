package me.rerere.highlight.kotlin.languages.sql

internal object SqlGrammar {
    val keywords = setOf(
        "add", "all", "alter", "and", "any", "as", "asc", "authorization", "begin",
        "between", "both", "by", "call", "cascade", "case", "check", "collate", "column",
        "commit", "constraint", "create", "cross", "current", "current_date",
        "current_schema", "current_time", "current_timestamp", "current_user", "database",
        "default", "delete", "desc", "distinct", "do", "drop", "each", "else", "end",
        "escape", "except", "execute", "exists", "explain", "fetch", "filter", "first",
        "following", "for", "foreign", "from", "full", "function", "grant", "group",
        "having", "if", "in", "index", "inner", "insert", "intersect", "into", "is",
        "join", "key", "language", "last", "lateral", "left", "like", "limit", "materialized",
        "merge", "natural", "next", "no", "not", "nulls", "of", "offset", "on", "only",
        "or", "order", "outer", "over", "partition", "preceding", "primary", "procedure",
        "range", "recursive", "references", "returning", "revoke", "right", "row", "rows",
        "schema", "select", "set", "some", "table", "then", "to", "transaction", "trigger",
        "truncate", "union", "unique", "update", "using", "values", "view", "when", "where",
        "window", "with",
    )

    val types = setOf(
        "bigint", "bigserial", "binary", "bit", "blob", "boolean", "bytea", "char",
        "character", "clob", "date", "datetime", "dec", "decimal", "double", "enum", "float",
        "int", "integer", "interval", "json", "jsonb", "money", "nchar", "nclob", "numeric",
        "real", "serial", "smallint", "text", "time", "timestamp", "tinyint", "uuid",
        "varbinary", "varchar", "varying", "xml",
    )

    val functions = setOf(
        "abs", "avg", "cast", "ceil", "ceiling", "char_length", "coalesce", "concat",
        "convert", "count", "current_date", "current_time", "current_timestamp", "date_part",
        "extract", "floor", "greatest", "json_array", "json_object", "json_query",
        "json_value", "lag", "last_value", "lead", "least", "length", "lower", "max", "min",
        "now", "nullif", "position", "rank", "replace", "round", "row_number", "substring",
        "sum", "trim", "upper",
    )

    val objectStarterKeywords = setOf(
        "database", "from", "index", "into", "join", "schema", "table", "trigger", "update",
        "view",
    )

    val identifierPattern = Regex("""[A-Za-z_][A-Za-z0-9_$]*""")
    val numberPattern = Regex(
        """(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)""" +
            """(?![A-Za-z0-9_$])""",
    )
}
