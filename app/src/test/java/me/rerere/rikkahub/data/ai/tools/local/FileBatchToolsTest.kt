package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBatchToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun invoke(tool: Tool, args: String): JsonObject {
        val text = runBlocking {
            (tool.execute(Json.parseToJsonElement(args)) as List<*>)
                .filterIsInstance<UIMessagePart.Text>()
                .first().text
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.success() = this["success"]?.jsonPrimitive?.intOrNull
    private fun JsonObject.failedPaths() =
        this["failed"]?.jsonArray?.map { it.jsonObject["path"]?.jsonPrimitive?.content }

    // ---------- batch_copy ----------

    @Test fun `batch_copy copies an explicit list`() {
        val a = tmp.newFile("a.txt").apply { writeText("A") }
        val b = tmp.newFile("b.txt").apply { writeText("B") }
        val dst = tmp.newFolder("out")
        val res = invoke(
            batchCopyTool(),
            """{"paths":["${a.absolutePath}","${b.absolutePath}"],"dst_dir":"${dst.absolutePath}"}""",
        )
        assertEquals(2, res.success())
        assertTrue(res.failedPaths()!!.isEmpty())
        assertTrue(File(dst, "a.txt").exists())
        assertTrue(File(dst, "b.txt").exists())
    }

    @Test fun `batch_copy resolves a glob pattern`() {
        tmp.newFile("one.log").writeText("1")
        tmp.newFile("two.log").writeText("2")
        tmp.newFile("keep.txt").writeText("k")
        val dst = tmp.newFolder("logs")
        val res = invoke(
            batchCopyTool(),
            """{"root":"${tmp.root.absolutePath}","pattern":"*.log","dst_dir":"${dst.absolutePath}"}""",
        )
        assertEquals(2, res.success())
        assertTrue(File(dst, "one.log").exists())
        assertFalse(File(dst, "keep.txt").exists())
    }

    @Test fun `batch_copy reports a missing source as failed without aborting`() {
        val a = tmp.newFile("real.txt").apply { writeText("R") }
        val dst = tmp.newFolder("out2")
        val res = invoke(
            batchCopyTool(),
            """{"paths":["${a.absolutePath}","${tmp.root.absolutePath}/ghost.txt"],"dst_dir":"${dst.absolutePath}"}""",
        )
        assertEquals(1, res.success())
        assertEquals(listOf("${tmp.root.absolutePath}/ghost.txt"), res.failedPaths())
    }

    @Test fun `batch_copy blocks a system path inside the batch`() {
        val a = tmp.newFile("ok.txt").apply { writeText("O") }
        val dst = tmp.newFolder("out3")
        val res = invoke(
            batchCopyTool(),
            """{"paths":["${a.absolutePath}","/system/build.prop"],"dst_dir":"${dst.absolutePath}"}""",
        )
        assertEquals(1, res.success())
        assertEquals(listOf("/system/build.prop"), res.failedPaths())
    }

    @Test fun `batch_copy rejects a system dst_dir`() {
        val res = invoke(batchCopyTool(), """{"paths":["/x"],"dst_dir":"/system/out"}""")
        assertEquals("bad_request", res["error"]?.jsonPrimitive?.content)
    }

    @Test fun `batch_copy requires paths or root+pattern`() {
        val dst = tmp.newFolder("out4")
        val res = invoke(batchCopyTool(), """{"dst_dir":"${dst.absolutePath}"}""")
        assertEquals("bad_request", res["error"]?.jsonPrimitive?.content)
    }

    // ---------- batch_move ----------

    @Test fun `batch_move moves files and leaves none behind`() {
        val a = tmp.newFile("m1.txt").apply { writeText("1") }
        val b = tmp.newFile("m2.txt").apply { writeText("2") }
        val dst = tmp.newFolder("moved")
        val res = invoke(
            batchMoveTool(),
            """{"paths":["${a.absolutePath}","${b.absolutePath}"],"dst_dir":"${dst.absolutePath}"}""",
        )
        assertEquals(2, res.success())
        assertFalse(a.exists())
        assertTrue(File(dst, "m1.txt").exists())
    }

    @Test fun `batch_move respects overwrite=false`() {
        val a = tmp.newFile("dup.txt").apply { writeText("new") }
        val dst = tmp.newFolder("dest")
        File(dst, "dup.txt").writeText("old")
        val res = invoke(
            batchMoveTool(),
            """{"paths":["${a.absolutePath}"],"dst_dir":"${dst.absolutePath}"}""",
        )
        assertEquals(0, res.success())
        assertEquals(listOf(a.absolutePath), res.failedPaths())
        assertEquals("old", File(dst, "dup.txt").readText())
    }

    @Test fun `batch_move overwrite=true swaps in new content without temp leftovers`() {
        // Regression: the old path deleted the target up front, risking loss on a failed
        // move. The swap must replace content, remove src, and leave no temp siblings.
        val a = tmp.newFile("ow.txt").apply { writeText("new") }
        val dst = tmp.newFolder("owdest")
        File(dst, "ow.txt").writeText("old")
        val res = invoke(
            batchMoveTool(),
            """{"paths":["${a.absolutePath}"],"dst_dir":"${dst.absolutePath}","overwrite":true}""",
        )
        assertEquals(1, res.success())
        assertFalse(a.exists())
        assertEquals("new", File(dst, "ow.txt").readText())
        assertFalse(dst.listFiles()!!.any { it.name.contains(".rkmv-") })
    }

    // ---------- batch_delete ----------

    @Test fun `batch_delete removes an explicit list`() {
        val a = tmp.newFile("d1.txt")
        val b = tmp.newFile("d2.txt")
        val res = invoke(
            batchDeleteTool(),
            """{"paths":["${a.absolutePath}","${b.absolutePath}"]}""",
        )
        assertEquals(2, res.success())
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test fun `batch_delete fails a non-empty directory without recursive`() {
        val dir = tmp.newFolder("full")
        File(dir, "child.txt").writeText("c")
        val res = invoke(batchDeleteTool(), """{"paths":["${dir.absolutePath}"]}""")
        assertEquals(0, res.success())
        assertEquals(listOf(dir.absolutePath), res.failedPaths())
        assertTrue(dir.exists())
    }

    @Test fun `batch_delete clears a non-empty directory with recursive`() {
        val dir = tmp.newFolder("full2")
        File(dir, "child.txt").writeText("c")
        val res = invoke(
            batchDeleteTool(),
            """{"paths":["${dir.absolutePath}"],"recursive":true}""",
        )
        assertEquals(1, res.success())
        assertFalse(dir.exists())
    }

    @Test fun `batch_delete blocks a system path`() {
        val res = invoke(batchDeleteTool(), """{"paths":["/system/x","/proc/1"]}""")
        assertEquals(0, res.success())
        assertEquals(2, res.failedPaths()!!.size)
    }
}
