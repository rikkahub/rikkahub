package me.rerere.highlight.kotlin.languages.c

import me.rerere.highlight.kotlin.languages.cfamily.CFamilyDialect

internal object CGrammar {
    val dialect = CFamilyDialect(
        keywords = setOf(
            "asm", "auto", "break", "case", "continue", "default", "do", "else", "enum",
            "extern", "for", "fortran", "goto", "if", "inline", "register", "restrict",
            "return", "sizeof", "struct", "switch", "typedef", "typeof", "typeof_unqual",
            "union", "volatile", "while", "_Alignas", "_Alignof", "_Atomic", "_Generic",
            "_Noreturn", "_Pragma", "_Static_assert", "_Thread_local", "alignas", "alignof",
            "noreturn", "static_assert", "thread_local",
        ),
        types = setOf(
            "_BitInt", "_Bool", "_Complex", "_Decimal32", "_Decimal64", "_Decimal64x",
            "_Decimal96", "_Decimal128", "_Decimal128x", "_Float16", "_Float32",
            "_Float32x", "_Float64", "_Float64x", "_Float128", "_Float128x", "_Imaginary",
            "bool", "char", "complex", "const", "constexpr", "double", "float", "imaginary",
            "int", "long", "short", "signed", "static", "unsigned", "void",
        ),
        builtIns = setOf(
            "abort", "abs", "calloc", "exit", "fprintf", "fputs", "free", "fscanf", "malloc",
            "memchr", "memcmp", "memcpy", "memset", "printf", "putchar", "puts", "realloc",
            "scanf", "snprintf", "sprintf", "sscanf", "strcat", "strchr", "strcmp", "strcpy",
            "strlen", "strncmp", "strncpy",
        ),
        typeDeclarationKeywords = setOf("enum", "struct", "union"),
    )
}
