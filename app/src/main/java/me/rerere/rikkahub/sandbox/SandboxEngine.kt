package me.rerere.rikkahub.sandbox

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.util.UUID

/**
 * RikkaHub 沙箱引擎
 * 管理每个助手的独立沙箱文件系统
 */
object SandboxEngine {
    private const val MODULE_NAME = "sandbox_tool"
    private const val FUNC_NAME = "execute"
    
    // 沙箱存储限制 (1GB)
    private const val MAX_SANDBOX_SIZE = 1024L * 1024 * 1024
    
    /**
     * 确保 Python 已初始化（Chaquopy 要求）
     */
    private fun ensurePythonStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }
    
    /**
     * 获取指定助手的沙箱目录
     */
    fun getSandboxDir(context: Context, assistantId: String): File {
        return File(context.filesDir, "sandboxes/$assistantId").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * 获取沙箱使用情况
     */
    fun getSandboxUsage(context: Context, assistantId: String): SandboxUsage {
        val sandboxDir = getSandboxDir(context, assistantId)
        val totalSize = calculateDirectorySize(sandboxDir)
        val fileCount = countFiles(sandboxDir)
        
        return SandboxUsage(
            usedBytes = totalSize,
            maxBytes = MAX_SANDBOX_SIZE,
            fileCount = fileCount,
            usagePercent = (totalSize * 100 / MAX_SANDBOX_SIZE).toInt()
        )
    }
    
    /**
     * 执行沙箱操作
     * 
     * @param context Android 上下文
     * @param assistantId 助手 ID
     * @param operation 操作类型: unzip, zip_create, exec, list, read, write, delete, etc.
     * @param params 操作参数
     * @return 操作结果
     */
    fun execute(
        context: Context,
        assistantId: String,
        operation: String,
        params: Map<String, Any>
    ): JsonObject {
        return try {
            // 确保 Python 已初始化
            ensurePythonStarted(context)
            
            val sandboxDir = getSandboxDir(context, assistantId)
            
            // 检查沙箱大小限制
            val usage = getSandboxUsage(context, assistantId)
            if (usage.usedBytes > MAX_SANDBOX_SIZE) {
                return buildJsonObject {
                    put("success", JsonPrimitive(false))
                    put("error", JsonPrimitive("Sandbox storage limit exceeded (1GB max). Please delete some files."))
                }
            }
            
            // 调用 Python 模块
            val py = Python.getInstance()
            val module = py.getModule(MODULE_NAME)
            
            // 将参数转换为 Python dict
            val pyParams = mapToPyObject(py, params)
            
            // 执行操作
            val result = module.callAttr(FUNC_NAME, operation, sandboxDir.absolutePath, pyParams)
            
            // 解析返回结果
            val parsedResult = parsePythonResult(result)
            
            // 修复：确保返回的结果包含 success 字段
            if (!parsedResult.containsKey("success")) {
                return buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("data", parsedResult)
                }
            }
            
            parsedResult
            
        } catch (e: Exception) {
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("[${e.javaClass.name}] ${e.message}"))
                put("traceback", JsonPrimitive(e.stackTraceToString()))
            }
        }
    }
    
    /**
     * 获取文件分享 URI
     * 
     * @param context Android 上下文
     * @param assistantId 助手 ID
     * @param filePath 沙箱内的文件路径
     * @return 分享 URI，如果文件不存在或路径不合法则返回 null
     */
    fun getFileShareUri(context: Context, assistantId: String, filePath: String): Uri? {
        return try {
            val sandboxDir = getSandboxDir(context, assistantId)
            val file = File(sandboxDir, filePath)
            
            // 安全检查：确保文件在沙箱内
            if (!file.exists() || !file.canonicalPath.startsWith(sandboxDir.canonicalPath)) {
                return null
            }
            
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 获取文件下载信息
     */
    fun getFileDownloadInfo(
        context: Context, 
        assistantId: String, 
        filePath: String
    ): FileDownloadInfo? {
        return try {
            val sandboxDir = getSandboxDir(context, assistantId)
            val file = File(sandboxDir, filePath)
            
            if (!file.exists() || !file.canonicalPath.startsWith(sandboxDir.canonicalPath)) {
                return null
            }
            
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            FileDownloadInfo(
                fileName = file.name,
                filePath = filePath,
                fileSize = file.length(),
                uri = uri.toString(),
                mimeType = getFileMimeType(file.name)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 列出沙箱中的所有文件
     */
    fun listAllFiles(context: Context, assistantId: String): List<SandboxFileInfo> {
        val sandboxDir = getSandboxDir(context, assistantId)
        val files = mutableListOf<SandboxFileInfo>()
        
        if (!sandboxDir.exists()) return files
        
        sandboxDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = file.relativeTo(sandboxDir).path
                files.add(
                    SandboxFileInfo(
                        name = file.name,
                        path = relativePath,
                        size = file.length(),
                        modified = file.lastModified(),
                        isDirectory = false
                    )
                )
            }
        
        return files.sortedBy { it.path }
    }
    
    /**
     * 删除沙箱中的文件或目录
     */
    fun deleteSandboxFile(context: Context, assistantId: String, filePath: String): Boolean {
        return try {
            val sandboxDir = getSandboxDir(context, assistantId)
            val file = File(sandboxDir, filePath)
            
            // 安全检查
            if (!file.canonicalPath.startsWith(sandboxDir.canonicalPath)) {
                return false
            }
            
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 清空沙箱
     */
    fun clearSandbox(context: Context, assistantId: String): Boolean {
        return try {
            val sandboxDir = getSandboxDir(context, assistantId)
            if (sandboxDir.exists()) {
                sandboxDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 删除助手的整个沙箱
     */
    fun deleteSandbox(context: Context, assistantId: String): Boolean {
        return try {
            val sandboxDir = getSandboxDir(context, assistantId)
            if (sandboxDir.exists()) {
                sandboxDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 将上传的文件复制到沙箱
     */
    fun importFileToSandbox(
        context: Context,
        assistantId: String,
        sourceUri: Uri,
        targetPath: String? = null
    ): String? {
        return try {
            val sandboxDir = getSandboxDir(context, assistantId)
            
            // 获取原始文件名
            val originalName = getFileNameFromUri(context, sourceUri) ?: "imported_${UUID.randomUUID()}"
            
            // 确定目标路径
            val finalTargetPath = targetPath ?: originalName
            val targetFile = File(sandboxDir, finalTargetPath)
            
            // 确保目录存在
            targetFile.parentFile?.mkdirs()
            
            // 复制文件
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            finalTargetPath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // ==================== Private Methods ====================
    
    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }
    
    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .count()
    }
    
    private fun mapToPyObject(py: Python, map: Map<String, Any>): PyObject {
        val pyDict = py.builtins.callAttr("dict")
        map.forEach { (key, value) ->
            val pyValue = when (value) {
                is String -> PyObject.fromJava(value)
                is Int -> PyObject.fromJava(value)
                is Long -> PyObject.fromJava(value)
                is Boolean -> PyObject.fromJava(value)
                is Double -> PyObject.fromJava(value)
                is List<*> -> listToPyObject(py, value)
                is Map<*, *> -> mapToPyObject(py, value as Map<String, Any>)
                else -> PyObject.fromJava(value.toString())
            }
            pyDict.callAttr("__setitem__", key, pyValue)
        }
        return pyDict
    }
    
    private fun listToPyObject(py: Python, list: List<*>): PyObject {
        val pyList = py.builtins.callAttr("list")
        list.forEach { item ->
            val pyItem = when (item) {
                is String -> PyObject.fromJava(item)
                is Int -> PyObject.fromJava(item)
                is Boolean -> PyObject.fromJava(item)
                else -> PyObject.fromJava(item.toString())
            }
            pyList.callAttr("append", pyItem)
        }
        return pyList
    }
    
    private fun parsePythonResult(result: PyObject): JsonObject {
        return try {
            // Python 函数现在直接返回 JSON 字符串
            val jsonStr = result.toString()
            // 使用 kotlinx.serialization 解析
            Json.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            // 如果解析失败，返回错误信息
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Failed to parse Python result: ${e.message}"))
                put("raw_result", JsonPrimitive(result.toString()))
            }
        }
    }
    
    /**
     * 根据文件名获取 MIME 类型 (公开方法)
     */
    fun getFileMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            fileName.endsWith(".json", ignoreCase = true) -> "application/json"
            fileName.endsWith(".md", ignoreCase = true) -> "text/markdown"
            fileName.endsWith(".py", ignoreCase = true) -> "text/x-python"
            fileName.endsWith(".jpg", ignoreCase = true) || 
            fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> "application/octet-stream"
        }
    }
    
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name
    }
    
    /**
     * 执行 Matplotlib 绘图
     * 
     * @param context Android 上下文
     * @param assistantId 助手 ID
     * @param code Python 绘图代码 (plt 和 np 已预导入)
     * @return 包含图片路径的结果
     */
    fun executeMatplotlibPlot(
        context: Context,
        assistantId: String,
        code: String
    ): JsonObject {
        return try {
            ensurePythonStarted(context)
            
            val sandboxDir = getSandboxDir(context, assistantId)
            val plotDir = File(sandboxDir, "plots").apply { mkdirs() }
            val outputFile = File(plotDir, "plot_${UUID.randomUUID()}.png")
            
            val py = Python.getInstance()
            val module = py.getModule("matplotlib_tool")
            val result = module.callAttr("run", code, outputFile.absolutePath, sandboxDir.absolutePath)
            
            // 解析 Python 返回的结果
            val ok = result.callAttr("get", "ok", false)?.toBoolean() == true
            
            if (ok) {
                val imagePath = outputFile.absolutePath
                val relativePath = imagePath.removePrefix(sandboxDir.absolutePath + "/")
                buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("image_path", JsonPrimitive(relativePath))
                    put("image_url", JsonPrimitive(Uri.fromFile(outputFile).toString()))
                    put("message", JsonPrimitive("Plot generated successfully"))
                }
            } else {
                val error = result.callAttr("get", "error", "")?.toString() ?: "Unknown error"
                val traceback = result.callAttr("get", "traceback", "")?.toString() ?: ""
                buildJsonObject {
                    put("success", JsonPrimitive(false))
                    put("error", JsonPrimitive(error))
                    put("traceback", JsonPrimitive(traceback))
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("[${e.javaClass.name}] ${e.message}"))
                put("traceback", JsonPrimitive(e.stackTraceToString()))
            }
        }
    }

    /**
     * 创建 Git Checkpoint（在 EXECUTE 模式下每个 assistant 回合开始前调用）
     *
     * @param context Android 上下文
     * @param assistantId 助手 ID（沙箱 ID）
     * @param boundMessageIndex 绑定的消息索引
     * @return checkpoint 的 git hash
     */
    fun createCheckpoint(
        context: Context,
        assistantId: String,
        boundMessageIndex: Int
    ): String? {
        return try {
            ensurePythonStarted(context)
            val sandboxDir = getSandboxDir(context, assistantId)

            // 初始化 git repo（如果不存在）
            val gitDir = File(sandboxDir, ".git")
            if (!gitDir.exists()) {
                execute(context, assistantId, "git_init", emptyMap())
            }

            // 执行 git commit 创建 checkpoint
            val result = execute(
                context,
                assistantId,
                "git_checkpoint",
                mapOf(
                    "message" to "Checkpoint at message index $boundMessageIndex",
                    "bound_message_index" to boundMessageIndex
                )
            )

            if (result["success"]?.jsonPrimitive?.boolean == true) {
                result["checkpoint_id"]?.jsonPrimitive?.content
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 恢复到指定的 Git Checkpoint
     *
     * @param context Android 上下文
     * @param assistantId 助手 ID（沙箱 ID）
     * @param checkpointId 要恢复的 checkpoint ID（git hash）
     * @return 是否成功
     */
    fun restoreCheckpoint(
        context: Context,
        assistantId: String,
        checkpointId: String
    ): Boolean {
        return try {
            ensurePythonStarted(context)

            val result = execute(
                context,
                assistantId,
                "git_restore",
                mapOf("checkpoint_id" to checkpointId)
            )

            result["success"]?.jsonPrimitive?.boolean == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 列出所有 Workflow Checkpoints
     *
     * @param context Android 上下文
     * @param assistantId 助手 ID（沙箱 ID）
     * @param limit 最多返回的 checkpoint 数量（默认 50）
     * @return checkpoint 列表
     */
    fun listCheckpoints(
        context: Context,
        assistantId: String,
        limit: Int = 50
    ): List<CheckpointInfo> {
        return try {
            ensurePythonStarted(context)

            val result = execute(
                context,
                assistantId,
                "git_list_checkpoints",
                mapOf("limit" to limit)
            )

            if (result["success"]?.jsonPrimitive?.boolean == true) {
                val checkpointsArray = result["checkpoints"]?.jsonArray
                checkpointsArray?.mapNotNull { element ->
                    val checkpointObj = element.jsonObject
                    CheckpointInfo(
                        checkpointId = checkpointObj["checkpoint_id"]?.jsonPrimitive?.content ?: "",
                        fullSha = checkpointObj["full_sha"]?.jsonPrimitive?.content ?: "",
                        message = checkpointObj["message"]?.jsonPrimitive?.content ?: "",
                        boundMessageIndex = checkpointObj["bound_message_index"]?.jsonPrimitive?.content?.toIntOrNull(),
                        timestamp = checkpointObj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

// ==================== Data Classes ====================

data class SandboxUsage(
    val usedBytes: Long,
    val maxBytes: Long,
    val fileCount: Int,
    val usagePercent: Int
) {
    fun formatUsedSize(): String = formatFileSize(usedBytes)
    fun formatMaxSize(): String = formatFileSize(maxBytes)
}

data class SandboxFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean
) {
    fun formatSize(): String = formatFileSize(size)
    fun formatModified(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(modified))
}

data class FileDownloadInfo(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val uri: String,
    val mimeType: String
) {
    fun formatSize(): String = formatFileSize(fileSize)
}

data class CheckpointInfo(
    val checkpointId: String,
    val fullSha: String,
    val message: String,
    val boundMessageIndex: Int?,
    val timestamp: Long
) {
    fun formatTimestamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
