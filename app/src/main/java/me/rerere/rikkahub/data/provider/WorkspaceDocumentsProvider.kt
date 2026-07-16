package me.rerere.rikkahub.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.core.context.GlobalContext

/** Exposes cloud workspace files through Android's Storage Access Framework. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private lateinit var closeHandlerThread: HandlerThread
    private lateinit var closeHandler: Handler

    private fun repository(): WorkspaceRepository = GlobalContext.get().get()

    private fun allWorkspaces(): List<WorkspaceEntity> = runBlocking {
        runCatching { repository().refreshRemote() }.getOrElse { emptyList() }
    }

    private fun workspaceName(id: String): String =
        allWorkspaces().firstOrNull { it.id == id }?.name ?: id

    override fun onCreate(): Boolean {
        closeHandlerThread = HandlerThread("workspace-document-close").apply { start() }
        closeHandler = Handler(closeHandlerThread.looper)
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val target = parseDocId(documentId)
        when {
            target.isRoot -> addRootRow(cursor)
            target.relPath.isEmpty() -> addWorkspaceRow(cursor, target.workspaceId)
            else -> runBlocking {
                repository().listFiles(
                    target.workspaceId,
                    WorkspaceStorageArea.FILES,
                    target.relPath.substringBeforeLast('/', ""),
                ).firstOrNull { it.path == target.relPath }?.let {
                    addEntryRow(cursor, target.workspaceId, it)
                }
            }
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
            allWorkspaces().forEach { addWorkspaceRow(cursor, it.id, it.name) }
        } else {
            runBlocking {
                repository().listFiles(
                    parent.workspaceId,
                    WorkspaceStorageArea.FILES,
                    parent.relPath,
                ).filterNot { it.name.startsWith(".l2s.") }
                    .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .forEach { addEntryRow(cursor, parent.workspaceId, it) }
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
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot open a directory" }
        val cacheDir = File(requireNotNull(context).cacheDir, "workspace-documents").apply { mkdirs() }
        val cacheFile = File(cacheDir, UUID.randomUUID().toString())
        val writes = mode.contains('w') || mode.contains('+')
        val truncates = mode.contains('t')
        if (!truncates) {
            val bytes = runBlocking {
                repository().readBytes(target.workspaceId, WorkspaceStorageArea.FILES, target.relPath)
            }
            cacheFile.writeBytes(bytes)
        } else {
            cacheFile.createNewFile()
        }
        return ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.parseMode(mode),
            closeHandler,
        ) {
            try {
                if (writes) {
                    runBlocking {
                        repository().writeBytes(
                            target.workspaceId,
                            WorkspaceStorageArea.FILES,
                            target.relPath,
                            cacheFile.readBytes(),
                            overwrite = true,
                        )
                    }
                    notifyChange(buildDocId(target.workspaceId, target.relPath.substringBeforeLast('/', "")))
                }
            } finally {
                cacheFile.delete()
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = parseDocId(parentDocumentId)
        require(!parent.isRoot) { "Cannot create document at root" }
        val name = runBlocking { uniqueName(parent.workspaceId, parent.relPath, displayName) }
        val path = joinPath(parent.relPath, name)
        runBlocking {
            if (mimeType == Document.MIME_TYPE_DIR) {
                repository().createDirectory(parent.workspaceId, WorkspaceStorageArea.FILES, path)
            } else {
                repository().writeBytes(
                    parent.workspaceId,
                    WorkspaceStorageArea.FILES,
                    path,
                    ByteArray(0),
                    overwrite = false,
                )
            }
        }
        notifyChange(parentDocumentId)
        return buildDocId(parent.workspaceId, path)
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot delete this document" }
        val entry = runBlocking { findEntry(target.workspaceId, target.relPath) }
        runBlocking {
            repository().deleteFile(
                target.workspaceId,
                WorkspaceStorageArea.FILES,
                target.relPath,
                recursive = entry?.isDirectory == true,
            )
        }
        notifyChange(buildDocId(target.workspaceId, target.relPath.substringBeforeLast('/', "")))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot rename this document" }
        val parent = target.relPath.substringBeforeLast('/', "")
        val safeName = displayName.replace('/', '_').ifBlank { "untitled" }
        val destination = joinPath(parent, safeName)
        runBlocking {
            repository().moveFile(target.workspaceId, target.relPath, destination, overwrite = false)
        }
        notifyChange(buildDocId(target.workspaceId, parent))
        return buildDocId(target.workspaceId, destination)
    }

    override fun getDocumentType(documentId: String): String {
        val target = parseDocId(documentId)
        if (target.isRoot || target.relPath.isEmpty()) return Document.MIME_TYPE_DIR
        val entry = runBlocking { findEntry(target.workspaceId, target.relPath) }
        return mimeOf(entry?.name ?: target.relPath.substringAfterLast('/'), entry?.isDirectory == true)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parseDocId(parentDocumentId)
        val child = parseDocId(documentId)
        if (child.isRoot) return false
        if (parent.isRoot) return true
        if (parent.workspaceId != child.workspaceId) return false
        if (parent.relPath.isEmpty()) return true
        return child.relPath == parent.relPath || child.relPath.startsWith(parent.relPath + "/")
    }

    private fun addRootRow(cursor: MatrixCursor) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Document.COLUMN_DISPLAY_NAME, context?.getString(R.string.app_name))
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun addWorkspaceRow(cursor: MatrixCursor, workspaceId: String, name: String = workspaceName(workspaceId)) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(workspaceId, ""))
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
        }
    }

    private fun addEntryRow(cursor: MatrixCursor, workspaceId: String, entry: WorkspaceFileEntry) {
        val flags = if (entry.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(workspaceId, entry.path))
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(entry.name, entry.isDirectory))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (entry.isDirectory) null else entry.sizeBytes)
            add(Document.COLUMN_LAST_MODIFIED, entry.updatedAt)
        }
    }

    private suspend fun findEntry(workspaceId: String, path: String): WorkspaceFileEntry? =
        repository().listFiles(
            workspaceId,
            WorkspaceStorageArea.FILES,
            path.substringBeforeLast('/', ""),
        ).firstOrNull { it.path == path }

    private suspend fun uniqueName(workspaceId: String, parent: String, displayName: String): String {
        val safe = displayName.replace('/', '_').ifBlank { "untitled" }
        val existing = repository().listFiles(workspaceId, WorkspaceStorageArea.FILES, parent)
            .mapTo(hashSetOf()) { it.name }
        if (safe !in existing) return safe
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val stem = safe.substring(0, dot)
        val extension = safe.substring(dot)
        var index = 1
        while ("$stem ($index)$extension" in existing) index++
        return "$stem ($index)$extension"
    }

    private fun mimeOf(name: String, isDirectory: Boolean): String {
        if (isDirectory) return Document.MIME_TYPE_DIR
        return name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId(true, "", "")
        require(documentId.startsWith(DOC_PREFIX)) { "Invalid documentId: $documentId" }
        val rest = documentId.removePrefix(DOC_PREFIX)
        val separator = rest.indexOf('/')
        return if (separator < 0) {
            DocId(false, rest, "")
        } else {
            DocId(false, rest.substring(0, separator), normalizeRelativePath(rest.substring(separator + 1)))
        }
    }

    private fun normalizeRelativePath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        require(parts.none { it == "." || it == ".." || '\u0000' in it }) { "Invalid path" }
        return parts.joinToString("/")
    }

    private fun joinPath(parent: String, child: String): String =
        listOf(parent.trim('/'), normalizeRelativePath(child)).filter { it.isNotEmpty() }.joinToString("/")

    private fun buildDocId(workspaceId: String, path: String): String =
        if (path.isEmpty()) "$DOC_PREFIX$workspaceId" else "$DOC_PREFIX$workspaceId/$path"

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = DocumentsContract.buildChildDocumentsUri(ctx.packageName + ".documents", parentDocumentId)
        ctx.contentResolver.notifyChange(uri, null)
    }

    private data class DocId(val isRoot: Boolean, val workspaceId: String, val relPath: String)

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
