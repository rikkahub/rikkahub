package me.rerere.document.pdf

internal sealed interface PdfObject

internal data object PdfNull : PdfObject

internal data class PdfBoolean(val value: Boolean) : PdfObject

internal data class PdfNumber(val value: Double) : PdfObject {
    val int: Int get() = value.toInt()
    val float: Float get() = value.toFloat()
}

internal class PdfString(val bytes: ByteArray) : PdfObject {
    // PDF 文本字符串：UTF-16BE/UTF-8 带 BOM，否则按 PDFDocEncoding
    fun text(): String = PdfEncodings.decodeTextString(bytes)

    override fun toString(): String = "PdfString(${String(bytes, Charsets.ISO_8859_1)})"
}

internal data class PdfName(val name: String) : PdfObject

internal class PdfArray(val items: List<PdfObject>) : PdfObject {
    operator fun get(index: Int): PdfObject? = items.getOrNull(index)

    override fun toString(): String = "PdfArray($items)"
}

internal class PdfDict(val map: Map<String, PdfObject>) : PdfObject {
    operator fun get(key: String): PdfObject? = map[key]

    override fun toString(): String = "PdfDict($map)"
}

// raw 是文件中的原始字节（尚未解密、解码），由 PdfFile 负责解密与解码
internal class PdfStream(val dict: PdfDict, val raw: ByteArray) : PdfObject {
    override fun toString(): String = "PdfStream($dict, ${raw.size} bytes)"
}

internal data class PdfRef(val num: Int, val gen: Int) : PdfObject

// 仅出现在内容流中
internal data class PdfOperator(val name: String) : PdfObject

// 内容流中的内联图片，仅占位，不解码
internal data object PdfInlineImage : PdfObject

internal class PdfException(message: String) : Exception(message)

// Java 资源目录，用绝对路径加载以免受 R8 重命名包影响
internal const val RESOURCE_DIR = "/me/rerere/document/pdf"
