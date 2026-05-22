package me.rerere.common.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Public API

data class ParseResult(
    val success: Boolean,
    val expr: Expr? = null,
    val errorMessage: String? = null
)

fun parseExpression(input: String): ParseResult {
    return try {
        val lexer = Lexer(input)
        val parser = Parser(lexer)
        val expr = parser.parse()
        ParseResult(true, expr, null)
    } catch (e: ParseException) {
        ParseResult(false, null, e.message)
    } catch (e: Exception) {
        ParseResult(false, null, e.message ?: "Unknown error")
    }
}

fun isJsonExprValid(input: String): Boolean = parseExpression(input).success

/**
 * 针对给定的根JSON对象评估JSON表达式，并将结果作为字符串返回。
 *
 * 支持的语言特性：
 * - 路径导航：`field`、`field.sub`、`array[0]`
 * - 带转义的字符串字面量：`"text"`，支持`\n`、`\r`、`\t`、`\\`、`\"`
 * - 数字：整数和小数（例如，`1`、`3.14`）
 * - 一元运算符：`+expr`、`-expr`
 * - 算术运算符：`+`、`-`、`*`、`/`（`x`作为`*`的别名）
 * - 字符串连接：`++`（操作数被强制转换为字符串）
 *
 * 解析和强制转换规则：
 * - 缺失的字段/索引解析为空字符串。
 * - JSON基本类型：字符串保持不变；数字进行最小化格式化（例如，`3.0` -> `"3"`）。
 * - JSON对象/数组以其JSON字符串表示形式返回。
 *
 * 错误：
 * - 对于无效语法或不支持的运算符，抛出[ParseException]。
 *
 * @param input 要评估的表达式。
 * @param root 用于解析路径的根[JsonObject]。
 * @return 作为字符串的评估值。
 */
fun evaluateJsonExpr(input: String, root: JsonObject): String {
    val lexer = Lexer(input)
    val parser = Parser(lexer)
    val expr = parser.parse()
    val value = Evaluator(root).eval(expr)
    return when (value) {
        is Value.Str -> value.value
        is Value.Num -> formatNumber(value.value)
    }
}

// Lexer

private enum class TokenType {
    IDENT, NUMBER, STRING,
    DOT, LBRACKET, RBRACKET, LPAREN, RPAREN,
    PLUS, MINUS, STAR, SLASH, CONCAT,
    EOF
}

private data class Token(val type: TokenType, val lexeme: String, val position: Int)

private class Lexer(private val src: String) {
    private var i = 0

    fun next(): Token {
        skipWhitespace()
        if (i >= src.length) return Token(TokenType.EOF, "", i)
        val start = i
        val c = src[i]
        when (c) {
            '.' -> { i++; return Token(TokenType.DOT, ".", start) }
            '[' -> { i++; return Token(TokenType.LBRACKET, "[", start) }
            ']' -> { i++; return Token(TokenType.RBRACKET, "]", start) }
            '(' -> { i++; return Token(TokenType.LPAREN, "(", start) }
            ')' -> { i++; return Token(TokenType.RPAREN, ")", start) }
            '+' -> {
                if (peek() == '+') { i += 2; return Token(TokenType.CONCAT, "++", start) }
                i++; return Token(TokenType.PLUS, "+", start)
            }
            '-' -> { i++; return Token(TokenType.MINUS, "-", start) }
            '*' -> { i++; return Token(TokenType.STAR, "*", start) }
            '/' -> { i++; return Token(TokenType.SLASH, "/", start) }
            'x', 'X' -> { i++; return Token(TokenType.STAR, c.toString(), start) }
            '"' -> return stringToken()
        }

        if (c.isDigit()) return numberToken()
        if (isIdentStart(c)) return identToken()

        throw ParseException("Unexpected character '$c' at $start")
    }

    private fun skipWhitespace() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun stringToken(): Token {
        val start = i
        i++ // skip opening quote
        val sb = StringBuilder()
        var escaped = false
        while (i < src.length) {
            val ch = src[i]
            i++
            if (escaped) {
                when (ch) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(ch)
                }
                escaped = false
            } else {
                if (ch == '\\') escaped = true
                else if (ch == '"') break
                else sb.append(ch)
            }
        }
        if (i > src.length || (i <= src.length && src[i - 1] != '"'))
            throw ParseException("Unterminated string starting at $start")
        return Token(TokenType.STRING, sb.toString(), start)
    }

    private fun numberToken(): Token {
        val start = i
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        val text = src.substring(start, i)
        return Token(TokenType.NUMBER, text, start)
    }

    private fun identToken(): Token {
        val start = i
        i++
        while (i < src.length && isIdentPart(src[i])) i++
        val text = src.substring(start, i)
        return Token(TokenType.IDENT, text, start)
    }

    private fun peek(): Char? = if (i + 1 < src.length) src[i + 1] else null

    private fun isIdentStart(c: Char) = c == '_' || c.isLetter()
    private fun isIdentPart(c: Char) = c == '_' || c.isLetterOrDigit()
}

// Parser

private class ParseException(message: String) : RuntimeException(message)

sealed interface Expr
private data class Binary(val left: Expr, val op: Token, val right: Expr) : Expr
private data class Unary(val op: Token, val expr: Expr) : Expr
private data class NumberLiteral(val value: Double) : Expr
private data class StringLiteral(val value: String) : Expr
private data class PathExpr(val parts: List<PathPart>) : Expr

private sealed interface PathPart
private data class Field(val name: String) : PathPart
private data class Index(val index: Int) : PathPart

private class Parser(private val lexer: Lexer) {
    private var current: Token = lexer.next()
    private var last: Token = current

    fun parse(): Expr {
        val expr = parseConcat()
        expect(TokenType.EOF)
        return expr
    }

    private fun parseConcat(): Expr {
        var expr = parseAdditive()
        while (match(TokenType.CONCAT)) {
            val op = previous()
            val right = parseAdditive()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun parseAdditive(): Expr {
        var expr = parseMultiplicative()
        while (match(TokenType.PLUS) || match(TokenType.MINUS)) {
            val op = previous()
            val right = parseMultiplicative()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun parseMultiplicative(): Expr {
        var expr = parseUnary()
        while (match(TokenType.STAR) || match(TokenType.SLASH)) {
            val op = previous()
            val right = parseUnary()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun parseUnary(): Expr {
        if (match(TokenType.PLUS) || match(TokenType.MINUS)) {
            val op = previous()
            val right = parseUnary()
            return Unary(op, right)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        return when (current.type) {
            TokenType.NUMBER -> {
                val n = current
                advance()
                NumberLiteral(n.lexeme.toDouble())
            }
            TokenType.STRING -> {
                val s = current
                advance()
                StringLiteral(s.lexeme)
            }
            TokenType.IDENT -> parsePath()
            TokenType.LPAREN -> {
                advance()
                val e = parseConcat()
                expect(TokenType.RPAREN)
                e
            }
            else -> throw error("Expected primary expression, got ${current.type}")
        }
    }

    private fun parsePath(): Expr {
        val parts = mutableListOf<PathPart>()
        if (current.type != TokenType.IDENT) throw error("Expected identifier for path")
        parts.add(Field(current.lexeme))
        advance()

        loop@ while (true) {
            when {
                match(TokenType.DOT) -> {
                    val id = expect(TokenType.IDENT)
                    parts.add(Field(id.lexeme))
                }
                match(TokenType.LBRACKET) -> {
                    val numTok = expect(TokenType.NUMBER)
                    val idxVal = numTok.lexeme.toDouble().toInt()
                    parts.add(Index(idxVal))
                    expect(TokenType.RBRACKET)
                }
                else -> break@loop
            }
        }
        return PathExpr(parts)
    }

    private fun match(type: TokenType): Boolean {
        if (current.type == type) { advance(); return true }
        return false
    }

    private fun expect(type: TokenType): Token {
        if (current.type != type) throw error("Expected $type but got ${current.type}")
        val tok = current
        advance()
        return tok
    }

    private fun advance() { last = current; current = lexer.next() }
    private fun previous(): Token = last
    private fun error(msg: String) = ParseException(msg)
}

// Evaluator

private sealed interface Value {
    data class Str(val value: String) : Value
    data class Num(val value: Double) : Value
}

private class Evaluator(private val root: JsonObject) {
    fun eval(expr: Expr): Value = when (expr) {
        is NumberLiteral -> Value.Num(expr.value)
        is StringLiteral -> Value.Str(expr.value)
        is Unary -> evalUnary(expr)
        is Binary -> evalBinary(expr)
        is PathExpr -> fromJson(resolvePath(expr))
    }

    private fun evalUnary(expr: Unary): Value {
        val v = eval(expr.expr)
        return when (expr.op.type) {
            TokenType.MINUS -> Value.Num(-toNumber(v))
            TokenType.PLUS -> Value.Num(+toNumber(v))
            else -> throw ParseException("Unsupported unary operator ${expr.op.lexeme}")
        }
    }

    private fun evalBinary(expr: Binary): Value {
        val left = eval(expr.left)
        val right = eval(expr.right)
        return when (expr.op.type) {
            TokenType.CONCAT -> Value.Str(toString(left) + toString(right))
            TokenType.PLUS -> Value.Num(toNumber(left) + toNumber(right))
            TokenType.MINUS -> Value.Num(toNumber(left) - toNumber(right))
            TokenType.STAR -> Value.Num(toNumber(left) * toNumber(right))
            TokenType.SLASH -> Value.Num(toNumber(left) / toNumber(right))
            else -> throw ParseException("Unsupported binary operator ${expr.op.lexeme}")
        }
    }

    private fun resolvePath(path: PathExpr): JsonElement? {
        var cur: JsonElement = root
        for (part in path.parts) {
            when (part) {
                is Field -> {
                    val obj = cur as? JsonObject ?: return null
                    cur = obj[part.name] ?: return null
                }
                is Index -> {
                    val arr = cur as? JsonArray ?: return null
                    val idx = part.index
                    if (idx !in 0 until arr.size) return null
                    cur = arr[idx]
                }
            }
        }
        return cur
    }

    private fun fromJson(elem: JsonElement?): Value {
        if (elem == null || elem is JsonNull) return Value.Str("")
        return when (elem) {
            is JsonPrimitive -> {
                if (elem.isString) Value.Str(elem.content)
                else elem.doubleOrNull()?.let { Value.Num("%.2f".format(it).toDouble()) } ?: Value.Str(elem.content)
            }
            is JsonObject, is JsonArray -> Value.Str(elem.toString())
        }
    }

    private fun toNumber(v: Value): Double = when (v) {
        is Value.Num -> v.value
        is Value.Str -> v.value.toDoubleOrNull() ?: 0.0
    }

    private fun toString(v: Value): String = when (v) {
        is Value.Str -> v.value
        is Value.Num -> formatNumber(v.value)
    }
}

private fun JsonPrimitive.doubleOrNull(): Double? = try {
    this.content.toDouble()
} catch (_: Exception) { null }

private fun formatNumber(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return d.toString()
    val asLong = d.toLong()
    return if (d == asLong.toDouble()) asLong.toString() else d.toString()
}
