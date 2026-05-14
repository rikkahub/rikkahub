package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 25 — bridges the file-manager tools to `content://` URIs from any DocumentsProvider
 * (USB / SD / Downloads / Drive / Dropbox / OneDrive / share-intent MediaStore reads).
 *
 * Security model: the OS-level SAF tree-grant is the gate. [ContentUriSafetyGuard] does
 * structural validation ONLY — no authority allowlist. Access is attempted via the
 * tree-grant path first; on `SecurityException` it falls through to direct-URI access
 * (which is how MediaStore share-intent URIs and LLM-passed URIs with their own grant flag
 * work).
 */
object ContentUriSafetyGuard {

    data class Violation(val code: String, val detail: String)

    /** True when [raw] is a content:// URI (the routing trigger for file-manager tools). */
    fun isContentUri(raw: String?): Boolean = raw != null && raw.startsWith("content://")

    /**
     * Structural validation: non-empty `content://` scheme, non-empty authority, non-empty
     * path. Returns null when ok, a [Violation] otherwise. Deliberately NO authority
     * allowlist — the OS grant model is the security boundary.
     *
     * Pure string parsing (no `android.net.Uri`) so it is unit-testable on the JVM.
     */
    fun check(raw: String?): Violation? {
        if (raw.isNullOrBlank()) {
            return Violation("path_blocked", "content URI must not be empty.")
        }
        if (!raw.startsWith("content://")) {
            return Violation("path_blocked", "URI scheme must be content://.")
        }
        val afterScheme = raw.removePrefix("content://")
        val slash = afterScheme.indexOf('/')
        val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        if (authority.isBlank()) {
            return Violation("path_blocked", "content URI has no authority.")
        }
        val path = if (slash < 0) "" else afterScheme.substring(slash)
        if (path.isBlank() || path == "/") {
            return Violation("path_blocked", "content URI has no path.")
        }
        return null
    }
}

/**
 * Resolves a `content://` URI to a [DocumentFile] for read / write / list operations and
 * exposes stream-copy primitives the file tools + archive tools share.
 */
object ContentUriResolver {

    /** True if the OS holds a persisted tree-URI permission covering [uri]. */
    private fun hasTreeGrant(context: Context, uri: Uri): Boolean = runCatching {
        val target = uri.toString()
        context.contentResolver.persistedUriPermissions.any { perm ->
            val held = perm.uri.toString()
            target == held || target.startsWith(held)
        }
    }.getOrDefault(false)

    /**
     * Resolve [raw] to a [DocumentFile]. Tries the tree-grant path first (covers
     * SAF-granted USB / SD / Downloads / cloud trees), then falls through to
     * `DocumentFile.fromSingleUri` for MediaStore / share-intent / self-granted URIs.
     * Returns null when neither path can reach it.
     */
    fun resolve(context: Context, raw: String): DocumentFile? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        // Tree-document URIs (granted trees + their children).
        val asTree = runCatching {
            if (hasTreeGrant(context, uri)) DocumentFile.fromTreeUri(context, uri) else null
        }.getOrNull()
        if (asTree != null && asTree.exists()) return asTree
        // Single-document URIs (MediaStore share intents, LLM-passed self-granted URIs).
        return runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
    }

    /** Open an InputStream for a content:// URI. Throws SecurityException when ungranted. */
    fun openInput(context: Context, raw: String) =
        context.contentResolver.openInputStream(Uri.parse(raw))

    /** Open an OutputStream for a content:// URI. Throws SecurityException when ungranted. */
    fun openOutput(context: Context, raw: String) =
        context.contentResolver.openOutputStream(Uri.parse(raw))

    /**
     * Standard "directory not granted" error envelope JSON for a content:// URI the
     * resolver couldn't reach. [raw] is the offending URI.
     */
    fun notGrantedEnvelope(raw: String): String {
        val authority = authorityOf(raw) ?: "unknown"
        return buildJsonObject {
            put("error", "directory_not_granted")
            put("detail", "call grant_directory_access first")
            put("authority", authority)
        }.toString()
    }

    /** Extract the authority from a content:// URI by pure string parsing (JVM-safe). */
    internal fun authorityOf(raw: String): String? {
        if (!raw.startsWith("content://")) return null
        val afterScheme = raw.removePrefix("content://")
        val slash = afterScheme.indexOf('/')
        val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        return authority.ifBlank { null }
    }
}
