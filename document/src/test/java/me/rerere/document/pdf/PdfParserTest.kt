package me.rerere.document.pdf

import me.rerere.document.PdfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PdfParserTest {
    private fun fixture(name: String): File = File(javaClass.getResource("/pdf/$name")!!.toURI())

    private fun parse(name: String): String = PdfParser.parserPdf(fixture(name))

    private fun assertContains(text: String, vararg expected: String) {
        for (e in expected) assertTrue("missing \"$e\" in:\n$text", e in text)
    }

    @Test
    fun filtersAndSimpleFontEncodings() {
        val text = parse("filters_encodings.pdf")
        assertContains(
            text,
            "---Page 1:\n",
            // ASCII85 + Flate，WinAnsi 高位字符
            "ASCII85 filter: Café naïve résumé “smart quotes” — dash € euro",
            "LZW filter: the quick brown fox jumps over the lazy dog, the quick brown fox again",
            // Differences 中的 AGL 名、uniXXXX 名和连字
            "fiifferences: αβ中fi“”",
            // Symbol 字体内置编码
            "αβγδπ",
            // TJ 中的大间距产生空格，小的字距调整不产生空格
            "Kerned TJ array",
            "next line via quote",
            "double quote op",
            "T star line",
            "RunLength + ASCIIHex filters",
            "Text inside a Form XObject",
        )
    }

    @Test
    fun fileStructureVariants() {
        val expected = parse("filters_encodings.pdf")
        for (name in listOf("object_streams.pdf", "broken_xref.pdf", "no_xref.pdf")) {
            assertEquals(name, expected, parse(name))
        }
    }

    @Test
    fun incrementalUpdateReplacesObject() {
        val text = parse("incremental.pdf")
        assertContains(text, "UPDATED by incremental save", "LZW filter")
        assertFalse(text, "fiifferences" in text)
    }

    @Test
    fun truncatedFileIsBestEffort() {
        // Catalog 和内容流都被截掉了，仍能扫描到页面对象，不应抛异常
        assertEquals("---Page 1:\n\n", parse("truncated.pdf"))
    }

    @Test
    fun encryptedWithEmptyUserPassword() {
        val expected = parse("filters_encodings.pdf")
        for (name in listOf(
            "encrypted_rc4_40.pdf",
            "encrypted_rc4_128.pdf",
            "encrypted_aes_128.pdf",
            "encrypted_aes_256.pdf"
        )) {
            assertEquals(name, expected, parse(name))
        }
    }

    @Test
    fun encryptedWithUserPasswordFails() {
        try {
            parse("encrypted_user_password.pdf")
            fail("expected failure")
        } catch (e: PdfException) {
            assertContains(e.message.orEmpty(), "password")
        }
    }

    @Test
    fun predefinedCjkCMaps() {
        val text = parse("cjk_predefined_cmap.pdf")
        assertContains(
            text,
            "简体中文 GBK 编码测试：人工智能",
            "国标编码第二行",
            "UCS2 编码：大语言模型",
            "UTF16 编码：深度学习",
            "日本語のテキスト、カタカナ",
            "繁體中文 Big5 編碼",
            "한국어 텍스트입니다",
            "ユニコード日本語",
        )
    }

    @Test
    fun type3ActualTextAnnotationsAndVerticalText() {
        val text = parse("type3_annots.pdf")
        assertContains(
            text,
            // Type3 字体：/a66 这类按编码命名的字形
            "Hello B",
            // ActualText 替换其中的字形
            "final text after",
            "Rotated ninety degrees",
            // 竖排 CMap（UniGB-UCS2-V）
            "竖排中文\n第二列",
            // 表单字段与按 /AS 选择的外观
            "Filled field value",
            "Checkbox ON",
        )
        assertFalse(text, "XYZ" in text)
        assertFalse(text, "Hidden annotation" in text)
        assertFalse(text, "Checkbox OFF" in text)
    }

    @Test
    fun rightToLeftAndStackedVerticalText() {
        val text = parse("chrome_rtl_vertical.pdf")
        assertContains(
            text,
            "عربية هي إحدى أكثر اللغات",
            "עברית היא שפה שמית ממשפחת השפות האפרו-אסיאתיות.",
            "Mixed: English text with العربية inside and numbers 2024.",
            "竖排中文测试，第二列文字。",
        )
    }

    @Test
    fun clippingNestedFormsAndGlyphNames() {
        val text = parse("clipping_forms.pdf")
        assertContains(
            text,
            "Visible body",
            // re W n 矩形裁剪，Q 之后恢复
            "Inside clip",
            "After restore",
            // 表单 BBox 裁剪
            "In bbox",
            // ToUnicode 映射到 U+0000 时退回字形名
            "a − b",
            // TeX 字体字形名，cmex 尺寸后缀
            "∥ϵ′≜(∑",
            // UniJIS-UCS2-H 由 Unicode 反查 CID 取得字宽，否则后两个字会被挤出裁剪区域
            "AaAa",
            // 超过 12 层的嵌套表单
            "Level 19",
            // 页面内容遗留的裁剪区域不影响注释
            "Annotation text",
        )
        for (hidden in listOf("Outside clip", "Out of bbox", "TINYSECRET")) {
            assertFalse(text, hidden in text)
        }
    }

    @Test
    fun lineBuilderSpacingAndParagraphs() {
        val builder = LineBuilder()
        fun glyph(text: String, x: Float, y: Float, w: Float = 6f) = builder.add(text, x, y, x + w, y, 12f, 1f, 0f)
        glyph("A", 0f, 100f)
        glyph("B", 6.5f, 100f) // 小间隙不加空格
        glyph("C", 16f, 100f) // 大间隙加空格
        glyph("D", 0f, 86f) // 正常行距：换行
        glyph("E", 0f, 40f) // 行距很大：空一行
        glyph("E", 0.5f, 40.2f) // 伪粗体的重复绘制
        assertEquals("AB C\nD\n\nE", builder.finish())
    }

    @Test
    fun lineBuilderNormalizesCharacters() {
        val builder = LineBuilder()
        // 连字、康熙部首、CJK 部首补充、阿拉伯文表现形式、软连字符
        builder.add("ﬁ⼤⻅­", 0f, 0f, 10f, 0f, 12f, 1f, 0f)
        assertEquals("fi大见", builder.finish())
    }
}
