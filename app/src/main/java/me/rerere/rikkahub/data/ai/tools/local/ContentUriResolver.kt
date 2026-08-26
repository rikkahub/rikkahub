package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.koin.java.KoinJavaComponent.getKoin

object ContentUriSafetyGuard {
    fun check(raw: String?): String? {
        if (raw.isNullOrBlank() || !raw.startsWith("content://")) return "content URI is required"
        val authority = Uri.parse(raw).authority
        return if (authority.isNullOrBlank()) "content URI has no authority" else null
    }
}

internal fun fmContext(): Context = getKoin().get(Context::class)

object ContentUriResolver {
    fun isPersistedGrant(context: Context, raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        val candidateTreeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val candidateDocumentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        context.contentResolver.persistedUriPermissions.any { permission ->
            if (permission.uri.authority != uri.authority) return@any false
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                ?: return@any false
            candidateTreeId == treeId ||
                candidateDocumentId == treeId ||
                candidateDocumentId?.startsWith("$treeId/") == true
        }
    }.getOrDefault(false)

    fun resolve(context: Context, raw: String): DocumentFile? {
        val uri = Uri.parse(raw)
        if (!isPersistedGrant(context, raw)) return null
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    }

    fun notGranted(raw: String): String =
        "{\"error\":\"directory_not_granted\",\"path\":${json(raw)}," +
            "\"detail\":\"Call grant_directory_access first.\"}"

    fun json(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
