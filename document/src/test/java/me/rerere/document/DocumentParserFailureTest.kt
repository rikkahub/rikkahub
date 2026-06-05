package me.rerere.document

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression for issue #83: parsers must report failure as a typed
 * [DocumentExtractionResult.ParseFailed], never as a content-shaped success string that RAG
 * ingestion would happily chunk and embed.
 *
 * The mid-parse cases (malformed slide/notes/XHTML) require a real `org.xmlpull` implementation:
 * Android's stubbed `android.jar` makes `XmlPullParserFactory.newInstance()` throw "not mocked"
 * before any XML is read, which would make the test pass for the wrong reason. The `document`
 * module's test runtime depends on kxml2 (see build.gradle.kts) so the factory returns a working
 * KXmlParser. The malformed-XML assertions explicitly reject the "not mocked" reason so that a
 * future drop of that dependency fails the test instead of silently re-introducing the hollow pass.
 */
class DocumentParserFailureTest {

    /** kxml2 absent (factory stubbed) yields this RuntimeException message; never a real parse. */
    private fun isNotMocked(result: DocumentExtractionResult): Boolean =
        result is DocumentExtractionResult.ParseFailed &&
            result.reason.contains("not mocked", ignoreCase = true)

    private fun tempFile(prefix: String): File =
        File.createTempFile(prefix, null).apply { deleteOnExit() }

    private fun zipWithEntry(prefix: String, entryName: String, bytes: ByteArray): File {
        val file = tempFile(prefix)
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(bytes)
            zos.closeEntry()
        }
        return file
    }

    @Test
    fun docxParseTyped_onNonZipBytes_isParseFailedNotSuccess() {
        val file = tempFile("not-a-docx").apply { writeBytes("this is not a zip".toByteArray()) }

        val result = DocxParser.parseTyped(file)

        assertTrue(
            "non-zip DOCX must be ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun pptxParseTyped_onZeroSlideZip_isParseFailedNotSuccess() {
        val file = zipWithEntry("no-slides-pptx", "docProps/app.xml", "<x/>".toByteArray())

        val result = PptxParser.parseTyped(file)

        assertTrue(
            "slide-less PPTX must be ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun epubParseTyped_onZipWithoutContainerXml_isParseFailedNotSuccess() {
        val file = zipWithEntry("no-opf-epub", "mimetype", "application/epub+zip".toByteArray())

        val result = EpubParser.parseTyped(file)

        assertTrue(
            "EPUB without META-INF/container.xml must be ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun pptxParseTyped_onMalformedSlideXml_isParseFailedNotSuccess() {
        // A zip whose slide enumeration succeeds but whose slide1.xml is not well-formed. The slide
        // XML parser used to swallow the exception and return the content-shaped string
        // "Error parsing slide XML: ..." which was then classified as Success and embedded — issue
        // #83 for the per-slide path.
        val file = zipWithEntry("bad-slide-pptx", "ppt/slides/slide1.xml", "<p:sld><unclosed".toByteArray())

        val result = PptxParser.parseTyped(file)

        assertFalse(
            "test ran without a real XML parser (kxml2 missing); pass is hollow, got $result",
            isNotMocked(result),
        )
        assertTrue(
            "malformed slide XML must be ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun pptxParseTyped_onMalformedNotesXml_isParseFailedNotSuccess() {
        // Slide is well-formed but its notes XML is not; notes parsing used to swallow into "",
        // silently dropping data. Now it propagates to ParseFailed.
        val file = tempFile("bad-notes-pptx")
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write("<p:sld><p:cSld><p:spTree></p:spTree></p:cSld></p:sld>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("ppt/notesSlides/notesSlide1.xml"))
            zos.write("<p:notes><unclosed".toByteArray())
            zos.closeEntry()
        }

        val result = PptxParser.parseTyped(file)

        assertFalse(
            "test ran without a real XML parser (kxml2 missing); pass is hollow, got $result",
            isNotMocked(result),
        )
        assertTrue(
            "malformed notes XML must be ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun epubParseTyped_onMalformedChapterXhtml_isParseFailedNotSuccess() {
        // A structurally valid EPUB (container.xml + OPF + one spine chapter) whose only chapter is
        // not well-formed XHTML. parseXhtml used to swallow the parse error (inner break + outer
        // catch returning ""), so the chapter silently dropped to Empty/partial Success and RAG
        // would embed corrupt content — issue #83 for the per-chapter EPUB path.
        val file = tempFile("bad-chapter-epub")
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf"/></rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("content.opf"))
            zos.write(
                """
                <package xmlns="http://www.idpf.org/2007/opf">
                  <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("ch1.xhtml"))
            zos.write("<html><body><p>broken<unclosed".toByteArray())
            zos.closeEntry()
        }

        val result = EpubParser.parseTyped(file)

        assertFalse(
            "test ran without a real XML parser (kxml2 missing); pass is hollow, got $result",
            isNotMocked(result),
        )
        assertTrue(
            "malformed chapter XHTML must be ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun parseFailures_neverSurfaceAsSuccessContent() {
        val docx = tempFile("not-a-docx2").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4)) }
        val pptx = zipWithEntry("no-slides-pptx2", "ppt/presentation.xml", "<p/>".toByteArray())
        val epub = zipWithEntry("no-opf-epub2", "OEBPS/dummy.txt", "x".toByteArray())

        for (result in listOf(
            DocxParser.parseTyped(docx),
            PptxParser.parseTyped(pptx),
            EpubParser.parseTyped(epub),
        )) {
            assertTrue(
                "parse failure must not be Success, got $result",
                result !is DocumentExtractionResult.Success,
            )
        }
    }
}
