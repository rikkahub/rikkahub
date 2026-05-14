package me.rerere.rikkahub.data.ai.tools.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.Tool

private fun invokeTool(tool: Tool, args: String): JsonObject {
    val text = runBlocking {
        (tool.execute(Json.parseToJsonElement(args)) as? List<*>)
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.first()?.text ?: "{}"
    }
    return Json.parseToJsonElement(text).jsonObject
}

class FileManagerToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ========== PathSafetyGuard integration via tools ==========

    @Test fun `list_files blocks system path`() {
        val result = invokeTool(listFilesTool(), """{"path":"/system"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `read_file blocks proc path`() {
        val result = invokeTool(readFileTool(), """{"path":"/proc/1/status"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `delete_file blocks vendor path`() {
        val result = invokeTool(deleteFileTool(), """{"path":"/vendor/lib"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `write_binary_file blocks other app sandbox`() {
        val result = invokeTool(writeBinaryFileTool(),
            """{"path":"/data/data/com.evil.app/evil.db","base64_content":"dGVzdA=="}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `move_file blocks traversal in src`() {
        val result = invokeTool(moveFileTool(),
            """{"src":"/sdcard/../../system/lib","dst":"/sdcard/out.so"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `copy_file blocks traversal in dst`() {
        val result = invokeTool(copyFileTool(),
            """{"src":"/sdcard/good.txt","dst":"../../../system/evil.txt"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `create_directory blocks sys path`() {
        val result = invokeTool(createDirectoryTool(), """{"path":"/sys/newdir"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `file_info blocks dev path`() {
        val result = invokeTool(fileInfoTool(), """{"path":"/dev/zero"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `find_files blocks apex path`() {
        val result = invokeTool(findFilesTool(), """{"root":"/apex","query":"lib"}""")
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    // ========== list_files ==========

    @Test fun `list_files returns files in temp dir`() {
        val dir = tmp.newFolder("music")
        File(dir, "song.mp3").writeText("dummy")
        File(dir, "notes.txt").writeText("hello")
        val result = invokeTool(listFilesTool(), """{"path":"${dir.absolutePath}"}""")
        assertNull(result["error"])
        val files = result["files"]?.let { Json.parseToJsonElement(it.toString()) }
        assertNotNull(files)
        assertFalse(result["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun `list_files returns not_found for missing dir`() {
        val result = invokeTool(listFilesTool(), """{"path":"/nonexistent/path/xyz"}""")
        assertEquals("not_found", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `list_files returns not_a_directory for a file`() {
        val f = tmp.newFile("test.txt")
        val result = invokeTool(listFilesTool(), """{"path":"${f.absolutePath}"}""")
        assertEquals("not_a_directory", result["error"]?.jsonPrimitive?.content)
    }

    // ========== file_info ==========

    @Test fun `file_info exists=false for missing path`() {
        val result = invokeTool(fileInfoTool(), """{"path":"${tmp.root.absolutePath}/nofile.bin"}""")
        assertFalse(result["exists"]!!.jsonPrimitive.boolean)
    }

    @Test fun `file_info returns correct size`() {
        val f = tmp.newFile("data.bin")
        f.writeBytes(ByteArray(1234))
        val result = invokeTool(fileInfoTool(), """{"path":"${f.absolutePath}"}""")
        assertTrue(result["exists"]!!.jsonPrimitive.boolean)
        assertEquals(1234L, result["size_bytes"]!!.jsonPrimitive.content.toLong())
    }

    // ========== create_directory ==========

    @Test fun `create_directory creates new dir`() {
        val path = "${tmp.root.absolutePath}/newdir/sub"
        val result = invokeTool(createDirectoryTool(), """{"path":"$path"}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(result["created"]!!.jsonPrimitive.boolean)
        assertTrue(File(path).isDirectory)
    }

    @Test fun `create_directory created=false if already exists`() {
        val dir = tmp.newFolder("existing")
        val result = invokeTool(createDirectoryTool(), """{"path":"${dir.absolutePath}"}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertFalse(result["created"]!!.jsonPrimitive.boolean)
    }

    // ========== write_binary_file ==========

    @Test fun `write_binary_file writes bytes`() {
        val path = "${tmp.root.absolutePath}/out.bin"
        // base64 of "hello"
        val result = invokeTool(writeBinaryFileTool(),
            """{"path":"$path","base64_content":"aGVsbG8="}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("hello", File(path).readText())
    }

    @Test fun `write_binary_file refuses overwrite by default`() {
        val f = tmp.newFile("exists.bin")
        f.writeText("original")
        val result = invokeTool(writeBinaryFileTool(),
            """{"path":"${f.absolutePath}","base64_content":"aGVsbG8="}""")
        assertEquals("file_exists", result["error"]?.jsonPrimitive?.content)
        assertEquals("original", f.readText()) // unchanged
    }

    @Test fun `write_binary_file overwrites when overwrite=true`() {
        val f = tmp.newFile("ow.bin")
        f.writeText("old")
        val result = invokeTool(writeBinaryFileTool(),
            """{"path":"${f.absolutePath}","base64_content":"bmV3","overwrite":true}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("new", f.readText())
    }

    @Test fun `write_binary_file rejects invalid base64`() {
        val path = "${tmp.root.absolutePath}/bad.bin"
        val result = invokeTool(writeBinaryFileTool(),
            """{"path":"$path","base64_content":"!!!not-base64!!!"}""")
        assertEquals("bad_base64", result["error"]?.jsonPrimitive?.content)
    }

    // ========== write_text_file ==========

    @Test fun `write_text_file auto creates RikkaHub shared-storage parents`() {
        assertTrue(
            shouldAutoCreateParent(
                "/sdcard/Documents/RikkaHub/nested/note.txt",
                "/sdcard/Documents/RikkaHub/nested/note.txt"
            )
        )
        assertTrue(
            shouldAutoCreateParent(
                "/storage/emulated/0/Download/RikkaHub/nested/note.txt",
                "/storage/emulated/0/Download/RikkaHub/nested/note.txt"
            )
        )
        assertFalse(
            shouldAutoCreateParent(
                "/sdcard/Documents/OtherApp/note.txt",
                "/sdcard/Documents/OtherApp/note.txt"
            )
        )
    }

    // ========== delete_file ==========

    @Test fun `delete_file removes a file`() {
        val f = tmp.newFile("del.txt")
        f.writeText("bye")
        val result = invokeTool(deleteFileTool(), """{"path":"${f.absolutePath}"}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertFalse(f.exists())
    }

    @Test fun `delete_file refuses non-empty dir without recursive`() {
        val dir = tmp.newFolder("nonempty")
        File(dir, "child.txt").writeText("x")
        val result = invokeTool(deleteFileTool(), """{"path":"${dir.absolutePath}"}""")
        assertEquals("not_empty", result["error"]?.jsonPrimitive?.content)
        assertTrue(dir.exists()) // unchanged
    }

    @Test fun `delete_file recursively deletes dir`() {
        val dir = tmp.newFolder("rdel")
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
        val result = invokeTool(deleteFileTool(),
            """{"path":"${dir.absolutePath}","recursive":true}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertFalse(dir.exists())
    }

    @Test fun `delete_file returns not_found for missing path`() {
        val result = invokeTool(deleteFileTool(),
            """{"path":"${tmp.root.absolutePath}/ghost.txt"}""")
        assertEquals("not_found", result["error"]?.jsonPrimitive?.content)
    }

    // ========== move_file ==========

    @Test fun `move_file renames a file`() {
        val src = tmp.newFile("src.txt")
        src.writeText("content")
        val dst = "${tmp.root.absolutePath}/dst.txt"
        val result = invokeTool(moveFileTool(),
            """{"src":"${src.absolutePath}","dst":"$dst"}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertFalse(src.exists())
        assertEquals("content", File(dst).readText())
    }

    @Test fun `move_file refuses overwrite by default`() {
        val src = tmp.newFile("msrc.txt")
        val dst = tmp.newFile("mdst.txt")
        src.writeText("source"); dst.writeText("original")
        val result = invokeTool(moveFileTool(),
            """{"src":"${src.absolutePath}","dst":"${dst.absolutePath}"}""")
        assertEquals("destination_exists", result["error"]?.jsonPrimitive?.content)
    }

    // ========== copy_file ==========

    @Test fun `copy_file copies content`() {
        val src = tmp.newFile("csrc.txt")
        src.writeText("hello world")
        val dst = "${tmp.root.absolutePath}/cdst.txt"
        val result = invokeTool(copyFileTool(),
            """{"src":"${src.absolutePath}","dst":"$dst"}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(src.exists()) // original preserved
        assertEquals("hello world", File(dst).readText())
    }

    @Test fun `copy_file refuses overwrite by default`() {
        val src = tmp.newFile("csrc2.txt")
        val dst = tmp.newFile("cdst2.txt")
        src.writeText("new"); dst.writeText("old")
        val result = invokeTool(copyFileTool(),
            """{"src":"${src.absolutePath}","dst":"${dst.absolutePath}"}""")
        assertEquals("destination_exists", result["error"]?.jsonPrimitive?.content)
        assertEquals("old", dst.readText()) // unchanged
    }

    // ========== read_file ==========

    @Test fun `read_file returns text content`() {
        val f = tmp.newFile("read.txt")
        f.writeText("the quick brown fox")
        val result = invokeTool(readFileTool(), """{"path":"${f.absolutePath}"}""")
        assertEquals("the quick brown fox", result["content"]?.jsonPrimitive?.content)
        assertFalse(result["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun `read_file returns truncated=true when file larger than max_bytes`() {
        val f = tmp.newFile("big.txt")
        f.writeBytes(ByteArray(200) { 'A'.code.toByte() })
        val result = invokeTool(readFileTool(),
            """{"path":"${f.absolutePath}","max_bytes":100}""")
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(100, result["bytes_read"]?.jsonPrimitive?.content?.toInt())
    }

    @Test fun `read_file returns not_found for missing file`() {
        val result = invokeTool(readFileTool(),
            """{"path":"${tmp.root.absolutePath}/missing.txt"}""")
        assertEquals("not_found", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `read_file returns is_directory for dir`() {
        val d = tmp.newFolder("rdir")
        val result = invokeTool(readFileTool(), """{"path":"${d.absolutePath}"}""")
        assertEquals("is_directory", result["error"]?.jsonPrimitive?.content)
    }

    // ========== find_files ==========

    @Test fun `find_files finds by substring`() {
        val dir = tmp.newFolder("find")
        File(dir, "alpha.txt").writeText("a")
        File(dir, "beta.txt").writeText("b")
        File(dir, "gamma.mp3").writeText("c")
        val result = invokeTool(findFilesTool(),
            """{"root":"${dir.absolutePath}","query":"alpha"}""")
        assertNull(result["error"])
        val files = result["files"].toString()
        assertTrue(files.contains("alpha.txt"))
        assertFalse(files.contains("beta.txt"))
    }

    @Test fun `find_files finds by glob`() {
        val dir = tmp.newFolder("findglob")
        File(dir, "song1.mp3").writeText("s")
        File(dir, "song2.mp3").writeText("s")
        File(dir, "doc.pdf").writeText("d")
        val result = invokeTool(findFilesTool(),
            """{"root":"${dir.absolutePath}","query":"*.mp3"}""")
        assertNull(result["error"])
        val files = result["files"].toString()
        assertTrue(files.contains("song1.mp3"))
        assertTrue(files.contains("song2.mp3"))
        assertFalse(files.contains("doc.pdf"))
    }

    @Test fun `find_files returns not_found for missing root`() {
        val result = invokeTool(findFilesTool(),
            """{"root":"/nonexistent/xyz","query":"foo"}""")
        assertEquals("not_found", result["error"]?.jsonPrimitive?.content)
    }

    // ========== Phase 25: content:// support ==========

    @Test fun `content uri detection routes content scheme`() {
        assertTrue(ContentUriSafetyGuard.isContentUri("content://com.android.externalstorage.documents/tree/x"))
        assertFalse(ContentUriSafetyGuard.isContentUri("/sdcard/file.txt"))
        assertFalse(ContentUriSafetyGuard.isContentUri("file:///sdcard/file.txt"))
    }

    @Test fun `content uri safety guard does structural validation only`() {
        // Well-formed content URI from any DocumentsProvider passes — no authority allowlist.
        assertNull(ContentUriSafetyGuard.check("content://com.dropbox.android.documents/tree/abc"))
        assertNull(ContentUriSafetyGuard.check("content://media/external/images/media/123"))
        // Malformed ones are blocked.
        assertNotNull(ContentUriSafetyGuard.check("content:///tree/missing-authority"))
        assertNotNull(ContentUriSafetyGuard.check("notcontent://x/y"))
    }

    @Test fun `not-granted envelope reports the authority`() {
        val json = ContentUriResolver.notGrantedEnvelope(
            "content://com.android.externalstorage.documents/tree/usb"
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("directory_not_granted", obj["error"]?.jsonPrimitive?.content)
        assertEquals(
            "com.android.externalstorage.documents",
            obj["authority"]?.jsonPrimitive?.content,
        )
    }

    @Test fun `file-manager tool descriptions advertise content uri support`() {
        // Every extended file tool's description must tell the LLM it accepts content://.
        listOf(
            listFilesTool(), readFileTool(), writeBinaryFileTool(), deleteFileTool(),
            moveFileTool(), copyFileTool(), createDirectoryTool(), fileInfoTool(),
            findFilesTool(),
        ).forEach { tool ->
            assertTrue(
                "${tool.name} description must mention content://",
                tool.description.contains("content://"),
            )
        }
    }
}
