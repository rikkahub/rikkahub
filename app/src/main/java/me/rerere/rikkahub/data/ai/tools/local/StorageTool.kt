package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun statsFor(path: String): JsonObject {
    val stat = StatFs(path)
    val total = stat.blockSizeLong * stat.blockCountLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    return buildJsonObject {
        put("total_bytes", total)
        put("free_bytes", free)
        put("used_bytes", total - free)
    }
}

fun storageTool(@Suppress("UNUSED_PARAMETER") context: Context): Tool = Tool(
    name = "get_storage_info",
    description = """
        Get total, free, and used storage space on the device's internal and external storage.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val internal = statsFor(Environment.getDataDirectory().path)
        val external = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            statsFor(Environment.getExternalStorageDirectory().path)
        } else {
            null
        }
        val payload = buildJsonObject {
            put("internal", internal)
            put("external", external ?: JsonNull)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
