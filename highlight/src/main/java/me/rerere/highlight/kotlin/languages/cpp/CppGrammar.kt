package me.rerere.highlight.kotlin.languages.cpp

import me.rerere.highlight.kotlin.languages.cfamily.CFamilyDialect

internal object CppGrammar {
    val dialect = CFamilyDialect(
        keywords = setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "atomic_cancel", "atomic_commit",
            "atomic_noexcept", "auto", "bitand", "bitor", "break", "case", "catch", "class",
            "co_await", "co_return", "co_yield", "compl", "concept", "const_cast", "consteval",
            "constexpr", "constinit", "continue", "decltype", "default", "delete", "do",
            "dynamic_cast", "else", "enum", "explicit", "export", "extern", "final", "for",
            "friend", "goto", "if", "import", "inline", "module", "mutable", "namespace", "new",
            "noexcept", "not", "not_eq", "operator", "or", "or_eq", "override", "private",
            "protected", "public", "reflexpr", "register", "reinterpret_cast", "requires",
            "return", "sizeof", "static_assert", "static_cast", "struct", "switch",
            "synchronized", "template", "this", "thread_local", "throw", "transaction_safe",
            "transaction_safe_dynamic", "try", "typedef", "typeid", "typename", "union",
            "using", "virtual", "volatile", "while", "xor", "xor_eq",
        ),
        types = setOf(
            "any", "auto_ptr", "barrier", "binary_semaphore", "bitset", "bool", "char",
            "char8_t", "char16_t", "char32_t", "complex", "condition_variable",
            "condition_variable_any", "const", "counting_semaphore", "deque", "double",
            "false_type", "flat_map", "flat_set", "float", "future", "initializer_list", "int",
            "istringstream", "jthread", "latch", "list", "lock_guard", "long", "map",
            "multimap", "multiset", "mutex", "optional", "ostringstream", "packaged_task",
            "pair", "promise", "priority_queue", "queue", "recursive_mutex",
            "recursive_timed_mutex", "scoped_lock", "set", "shared_future", "shared_lock",
            "shared_mutex", "shared_ptr", "short", "signed", "stack", "static", "string",
            "string_view", "stringstream", "thread", "timed_mutex", "true_type", "tuple",
            "uint", "unique_lock", "unique_ptr", "unordered_map", "unordered_multimap",
            "unordered_multiset", "unordered_set", "unsigned", "variant", "vector", "void",
            "wchar_t", "weak_ptr", "wstring", "wstring_view",
        ),
        builtIns = setOf(
            "abort", "abs", "apply", "as_const", "calloc", "cerr", "cin", "clog", "cout",
            "declval", "endl", "exchange", "exit", "fprintf", "free", "invoke", "make_pair",
            "make_shared", "make_tuple", "make_unique", "malloc", "memcpy", "move", "printf",
            "puts", "realloc", "scanf", "sqrt", "swap", "terminate", "visit",
        ),
        typeDeclarationKeywords = setOf(
            "class", "enum", "namespace", "struct", "union",
        ),
    )
}
