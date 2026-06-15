package me.rerere.rikkahub.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * 通过 Storage Access Framework 将 workspace 的 files 目录暴露给系统文件管理器。
 *
 * 目录结构：
 * ```
 * <root>
 * └── {workspace name}        <- 每个 workspace（虚拟目录，对应 files/ 目录）
 *     └── ...                 <- files/ 目录下的实际文件
 * ```
 *
 * documentId 设计：
 * - 顶层根：[ROOT_DOC_ID]
 * - workspace 根目录：`ws/{root}`（root 为 workspace 在磁盘上的目录名，即 UUID）
 * - workspace 内文件：`ws/{root}/{相对 files/ 的路径}`
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private fun manager(): WorkspaceManager = GlobalContext.get().get()

    private fun dao(): WorkspaceDAO = GlobalContext.get().get()

    private fun allWorkspaces(): List<WorkspaceEntity> = runBlocking { dao().getAll() }

    private fun workspaceName(root: String): String =
        allWorkspaces().firstOrNull { it.root == root }?.name ?: root

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val target = parseDocId(documentId)
        if (target.isRoot) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, context?.getString(R.string.app_name))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            addFileRow(cursor, target.root, resolveFile(target.root, target.relPath))
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = parseDocId(parentDocumentId)
        if (parent.isRoot) {
            // 顶层：列出所有 workspace
            for (ws in allWorkspaces()) {
                val dir = manager().filesDir(ws.root).also { it.mkdirs() }
                addFileRow(cursor, ws.root, dir)
            }
        } else {
            val dir = resolveFile(parent.root, parent.relPath)
            if (dir.isDirectory) {
                dir.listFiles()
                    .orEmpty()
                    .filter { !it.name.startsWith(".l2s.") }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .forEach { addFileRow(cursor, parent.root, it) }
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val target = parseDocId(documentId)
        require(!target.isRoot) { "Cannot open root as a document" }
        val file = resolveFile(target.root, target.relPath)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = parseDocId(parentDocumentId)
        require(!parent.isRoot) { "Cannot create document at root" }
        manager().ensureWorkspace(parent.root)
        val parentDir = resolveFile(parent.root, parent.relPath)
        require(parentDir.isDirectory) { "Parent is not a directory" }
        val target = uniqueChild(parentDir, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            require(target.mkdir()) { "Failed to create directory: $displayName" }
        } else {
            require(target.createNewFile()) { "Failed to create file: $displayName" }
        }
        notifyChange(parentDocumentId)
        return buildDocId(parent.root, relPathOf(parent.root, target))
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot delete this document" }
        val file = resolveFile(target.root, target.relPath)
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        require(ok) { "Failed to delete: $documentId" }
        notifyChange(buildDocId(target.root, target.relPath.substringBeforeLast('/', "")))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot rename this document" }
        val file = resolveFile(target.root, target.relPath)
        val dest = File(file.parentFile, displayName.replace('/', '_'))
        require(!dest.exists()) { "Target already exists: $displayName" }
        require(file.renameTo(dest)) { "Failed to rename: $documentId" }
        notifyChange(buildDocId(target.root, target.relPath.substringBeforeLast('/', "")))
        return buildDocId(target.root, relPathOf(target.root, dest))
    }

    override fun getDocumentType(documentId: String): String {
        val target = parseDocId(documentId)
        if (target.isRoot) return Document.MIME_TYPE_DIR
        return mimeOf(resolveFile(target.root, target.relPath))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parseDocId(parentDocumentId)
        val child = parseDocId(documentId)
        if (child.isRoot) return false
        if (parent.isRoot) return true
        if (parent.root != child.root) return false
        if (parent.relPath.isEmpty()) return true
        return child.relPath == parent.relPath || child.relPath.startsWith(parent.relPath + "/")
    }

    // --- helpers ---

    private fun addFileRow(cursor: MatrixCursor, root: String, file: File) {
        val relPath = relPathOf(root, file)
        val isDir = file.isDirectory
        val flags = when {
            // workspace 根目录：仅允许在其内部创建文件，不能删除/重命名 workspace 本身
            relPath.isEmpty() -> Document.FLAG_DIR_SUPPORTS_CREATE
            isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(root, relPath))
            add(Document.COLUMN_DISPLAY_NAME, if (relPath.isEmpty()) workspaceName(root) else file.name)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return ext.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun uniqueChild(parent: File, name: String): File {
        val safe = name.replace('/', '_').ifBlank { "untitled" }
        var candidate = File(parent, safe)
        if (!candidate.exists()) return candidate
        val stem = candidate.nameWithoutExtension
        val ext = candidate.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        do {
            candidate = File(parent, "$stem ($n)$ext")
            n++
        } while (candidate.exists())
        return candidate
    }

    /** 将 workspace files 目录下的文件解析为相对路径（root 自身返回空串） */
    private fun relPathOf(root: String, file: File): String {
        val base = manager().filesDir(root).canonicalFile
        return file.canonicalFile.relativeTo(base).path.replace(File.separatorChar, '/')
    }

    /** 解析 documentId 指向的实际文件，并校验路径不逃逸 workspace files 目录 */
    private fun resolveFile(root: String, relPath: String): File {
        val base = manager().filesDir(root).canonicalFile
        base.mkdirs()
        val normalized = relPath.trim().trimStart('/')
        require(!normalized.contains(' ')) { "Path contains invalid character" }
        if (normalized.isEmpty()) return base
        val target = File(base, normalized).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            "Path escapes workspace root: $relPath"
        }
        return target
    }

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId(isRoot = true, root = "", relPath = "")
        require(documentId.startsWith(DOC_PREFIX)) { "Invalid documentId: $documentId" }
        val rest = documentId.removePrefix(DOC_PREFIX)
        val idx = rest.indexOf('/')
        return if (idx < 0) {
            DocId(isRoot = false, root = rest, relPath = "")
        } else {
            DocId(isRoot = false, root = rest.substring(0, idx), relPath = rest.substring(idx + 1))
        }
    }

    private fun buildDocId(root: String, relPath: String): String =
        if (relPath.isEmpty()) "$DOC_PREFIX$root" else "$DOC_PREFIX$root/$relPath"

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = DocumentsContract.buildChildDocumentsUri(
            ctx.packageName + ".documents",
            parentDocumentId,
        )
        ctx.contentResolver.notifyChange(uri, null)
    }

    private data class DocId(
        val isRoot: Boolean,
        val root: String,
        val relPath: String,
    )

    companion object {
        private const val ROOT_ID = "rikkahub_workspaces"
        private const val ROOT_DOC_ID = "root"
        private const val DOC_PREFIX = "ws/"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
