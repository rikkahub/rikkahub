package me.rerere.highlight.kotlin.engine

internal object TokenScope {
    const val KEYWORD = "keyword"
    const val STRING = "string"
    const val NUMBER = "number"
    const val COMMENT = "comment"
    const val FUNCTION = "function"
    const val OPERATOR = "operator"
    const val PUNCTUATION = "punctuation"
    const val CLASS_NAME = "class-name"
    const val PROPERTY = "property"
    const val BOOLEAN = "boolean"
    const val CONSTANT = "constant"
    const val VARIABLE = "variable"
    const val REGEX = "regex"
    const val IMPORTANT = "important"
    const val TAG = "tag"
    const val ATTR_NAME = "attr-name"
    const val ATTR_VALUE = "attr-value"
}
