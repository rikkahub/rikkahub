package me.rerere.rikkahub.data.ai.tools.termux

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TermuxPendingExecutionRecord(
    val executionId: String,
    val commandPath: String,
    val arguments: List<String> = emptyList(),
    val workdir: String,
    val label: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

class TermuxExecutionStore(
    private val baseDir: File,
    private val json: Json,
) {
    private val lock = Any()
    private val pendingDir = File(baseDir, "pending")
    private val resultDir = File(baseDir, "results")

    fun recordPending(record: TermuxPendingExecutionRecord) {
        synchronized(lock) {
            writeJson(file = pendingFile(record.executionId), payload = record)
        }
    }

    fun markCompleted(executionId: String, result: TermuxResult) {
        synchronized(lock) {
            writeJson(file = resultFile(executionId), payload = result)
            pendingFile(executionId).delete()
        }
    }

    fun readPending(executionId: String): TermuxPendingExecutionRecord? {
        synchronized(lock) {
            return pendingFile(executionId).readJsonOrNull()
        }
    }

    fun takeCompletedResult(executionId: String): TermuxResult? {
        synchronized(lock) {
            val file = resultFile(executionId)
            val result = file.readJsonOrNull<TermuxResult>() ?: return null
            file.delete()
            return result
        }
    }

    fun clearPending(executionId: String) {
        synchronized(lock) {
            pendingFile(executionId).delete()
        }
    }

    fun clearAll(executionId: String) {
        synchronized(lock) {
            pendingFile(executionId).delete()
            resultFile(executionId).delete()
        }
    }

    private fun pendingFile(executionId: String): File {
        return File(pendingDir, "$executionId.json")
    }

    private fun resultFile(executionId: String): File {
        return File(resultDir, "$executionId.json")
    }

    private inline fun <reified T> File.readJsonOrNull(): T? {
        if (!exists()) return null
        return runCatching { json.decodeFromString<T>(readText()) }.getOrNull()
    }

    private inline fun <reified T> writeJson(
        file: File,
        payload: T,
    ) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(json.encodeToString(payload))
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(target = file, overwrite = true)
            tempFile.delete()
        }
    }
}
