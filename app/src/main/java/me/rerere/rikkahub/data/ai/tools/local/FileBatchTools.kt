package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.io.File
import java.io.IOException

// ============================================================
//  Batch file operations (Phase enhancement item 5.5)
//
//  batch_copy / batch_move / batch_delete operate over either an explicit list of paths
//  or a glob pattern resolved under a root. Every resolved path still goes through
//  PathSafetyGuard — no batch op may bypass the safety check. Each tool returns a
//  structured {success, failed: [{path, error}]} summary; one bad path never aborts the
//  whole batch. file:// paths only (no content:// — the single-path tools cover that).
// ============================================================

private const val BATCH_PATH_CAP = 500

/**
 * Resolve the set of source paths for a batch op. Either [paths] (explicit list, expanded
 * via AgentWorkspace) or [root] + [pattern] (glob matched against the filename of each
 * direct child of root). Returns the resolved list or an error envelope string.
 */
private fun resolveBatchSources(obj: JsonObject): Result<List<String>> {
    val explicit = obj["paths"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    if (!explicit.isNullOrEmpty()) {
        if (explicit.size > BATCH_PATH_CAP) {
            return Result.failure(
                IllegalArgumentException("too many paths (${explicit.size}); cap is $BATCH_PATH_CAP")
            )
        }
        return Result.success(explicit.map(AgentWorkspace::expand))
    }
    val root = obj["root"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?.let(AgentWorkspace::expand)
    val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (root == null || pattern == null) {
        return Result.failure(
            IllegalArgumentException("provide either paths (list) or root + pattern (glob)")
        )
    }
    PathSafetyGuard.check(root)?.let { v ->
        return Result.failure(IllegalArgumentException(v.detail))
    }
    val rootDir = File(root)
    if (!rootDir.isDirectory) {
        return Result.failure(IllegalArgumentException("root is not a directory: $root"))
    }
    val regex = globToRegex(pattern)
    val matched = try {
        rootDir.listFiles()?.filter { regex.matches(it.name) }?.map { it.absolutePath } ?: emptyList()
    } catch (_: SecurityException) {
        return Result.failure(IllegalArgumentException("permission denied listing root: $root"))
    }
    if (matched.size > BATCH_PATH_CAP) {
        return Result.failure(
            IllegalArgumentException("pattern matched ${matched.size} files; cap is $BATCH_PATH_CAP")
        )
    }
    return Result.success(matched)
}

private fun batchSourcesSchema() = buildJsonObject {
    put("paths", buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", "Explicit list of file:// paths. Mutually exclusive with root+pattern.")
    })
    put("root", buildJsonObject {
        put("type", "string")
        put("description", "Directory to glob under (used with pattern)")
    })
    put("pattern", buildJsonObject {
        put("type", "string")
        put("description", "Glob matched against the filename of each direct child of root, e.g. *.log")
    })
}

private fun batchErr(detail: String) =
    fmTextPart(fmErrEnvelope("bad_request", detail))

// ---------- batch_copy ----------

fun batchCopyTool(): Tool = Tool(
    name = "batch_copy",
    description = """
        Copy many files to a destination directory in one call. Provide either paths (an
        explicit list) or root + pattern (a glob). dst_dir is the destination directory;
        each source is copied into it under its own filename. overwrite defaults false.
        Every path is checked by the path-safety guard. Returns {success: N, failed:
        [{path, error}]}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                batchSourcesSchema().forEach { (k, v) -> put(k, v) }
                put("dst_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Destination directory; each source is copied into it")
                })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("dst_dir"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val dstDir = obj["dst_dir"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let(AgentWorkspace::expand)
            ?: return@Tool batchErr("dst_dir is required")
        PathSafetyGuard.check(dstDir)?.let { v -> return@Tool batchErr(v.detail) }
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        val sources = resolveBatchSources(obj).getOrElse { return@Tool batchErr(it.message ?: "bad input") }

        val destination = File(dstDir)
        var success = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (path in sources) {
            val v = PathSafetyGuard.check(path)
            if (v != null) { failed += path to v.detail; continue }
            val src = File(path)
            if (!src.exists()) { failed += path to "not_found"; continue }
            val target = File(destination, src.name)
            if (target.exists() && !overwrite) { failed += path to "destination_exists"; continue }
            try {
                destination.mkdirs()
                if (src.isDirectory) {
                    src.copyRecursively(target, overwrite = overwrite)
                } else {
                    src.copyTo(target, overwrite = overwrite)
                }
                success++
            } catch (e: SecurityException) {
                failed += path to (e.message ?: "permission_denied")
            } catch (e: IOException) {
                failed += path to (e.message ?: "io_error")
            }
        }
        fmTextPart(batchSummary(success, failed))
    },
)

// ---------- batch_move ----------

fun batchMoveTool(): Tool = Tool(
    name = "batch_move",
    description = """
        Move many files into a destination directory in one call. Provide either paths (an
        explicit list) or root + pattern (a glob). dst_dir is the destination directory.
        overwrite defaults false. Every path is checked by the path-safety guard. Returns
        {success: N, failed: [{path, error}]}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                batchSourcesSchema().forEach { (k, v) -> put(k, v) }
                put("dst_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Destination directory; each source is moved into it")
                })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("dst_dir"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val dstDir = obj["dst_dir"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let(AgentWorkspace::expand)
            ?: return@Tool batchErr("dst_dir is required")
        PathSafetyGuard.check(dstDir)?.let { v -> return@Tool batchErr(v.detail) }
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        val sources = resolveBatchSources(obj).getOrElse { return@Tool batchErr(it.message ?: "bad input") }

        val destination = File(dstDir)
        var success = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (path in sources) {
            val v = PathSafetyGuard.check(path)
            if (v != null) { failed += path to v.detail; continue }
            val src = File(path)
            if (!src.exists()) { failed += path to "not_found"; continue }
            val target = File(destination, src.name)
            if (target.exists() && !overwrite) { failed += path to "destination_exists"; continue }
            try {
                destination.mkdirs()
                if (!target.exists()) {
                    // No destination to protect: rename, copy+delete across filesystems.
                    if (!src.renameTo(target)) {
                        src.copyRecursively(target, overwrite = false)
                        src.deleteRecursively()
                    }
                    success++
                } else {
                    // Overwriting: never delete the old target until the new content is in
                    // place, so a failed move can't lose it. Move the old target aside, swap,
                    // and only then drop the backup. Restore it if the swap fails.
                    val backup = File(destination, "${target.name}.rkmv-old-${System.nanoTime()}")
                    if (target.renameTo(backup)) {
                        if (src.renameTo(target)) {
                            backup.deleteRecursively()
                            success++
                        } else {
                            val tmp = File(destination, "${target.name}.rkmv-new-${System.nanoTime()}")
                            src.copyRecursively(tmp, overwrite = false)
                            if (tmp.renameTo(target)) {
                                src.deleteRecursively()
                                backup.deleteRecursively()
                                success++
                            } else {
                                tmp.deleteRecursively()
                                backup.renameTo(target)
                                failed += path to "io_error"
                            }
                        }
                    } else {
                        // Couldn't move the old target aside (e.g. cross-fs): copy src to a
                        // temp beside target first. The old target is dropped only AFTER the
                        // copy lands and is moved to a backup, so a failed swap restores the
                        // original target instead of losing it.
                        val tmp = File(destination, "${target.name}.rkmv-new-${System.nanoTime()}")
                        val backup = File(destination, "${target.name}.rkmv-old-${System.nanoTime()}")
                        src.copyRecursively(tmp, overwrite = false)
                        if (!target.renameTo(backup)) {
                            tmp.deleteRecursively()
                            failed += path to "io_error"
                        } else if (tmp.renameTo(target)) {
                            backup.deleteRecursively()
                            src.deleteRecursively()
                            success++
                        } else {
                            tmp.deleteRecursively()
                            backup.renameTo(target)
                            failed += path to "io_error"
                        }
                    }
                }
            } catch (e: SecurityException) {
                failed += path to (e.message ?: "permission_denied")
            } catch (e: IOException) {
                failed += path to (e.message ?: "io_error")
            }
        }
        fmTextPart(batchSummary(success, failed))
    },
)

// ---------- batch_delete ----------

fun batchDeleteTool(): Tool = Tool(
    name = "batch_delete",
    description = """
        Delete many files or directories in one call. Provide either paths (an explicit list)
        or root + pattern (a glob). recursive defaults false (a non-empty directory fails
        unless recursive=true). Every path is checked by the path-safety guard. Returns
        {success: N, failed: [{path, error}]}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                batchSourcesSchema().forEach { (k, v) -> put(k, v) }
                put("recursive", buildJsonObject { put("type", "boolean") })
            },
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val sources = resolveBatchSources(obj).getOrElse { return@Tool batchErr(it.message ?: "bad input") }

        var success = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (path in sources) {
            val v = PathSafetyGuard.check(path)
            if (v != null) { failed += path to v.detail; continue }
            val file = File(path)
            if (!file.exists()) { failed += path to "not_found"; continue }
            if (file.isDirectory && !file.listFiles().isNullOrEmpty() && !recursive) {
                failed += path to "not_empty"; continue
            }
            try {
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) success++ else failed += path to "delete_failed"
            } catch (e: SecurityException) {
                failed += path to (e.message ?: "permission_denied")
            }
        }
        fmTextPart(batchSummary(success, failed))
    },
)

private fun batchSummary(success: Int, failed: List<Pair<String, String>>): String =
    buildJsonObject {
        put("success", success)
        put("failed", buildJsonArray {
            failed.forEach { (path, error) ->
                addJsonObject {
                    put("path", path)
                    put("error", error)
                }
            }
        })
    }.toString()
