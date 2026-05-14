package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private fun esErr(msg: String) =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", msg) }.toString()))

@OptIn(ExperimentalUuidApi::class)
private fun isHeadless(ic: ToolInvocationContext): Boolean {
    if (ic.isHeadless) return true
    val convId = ic.callerConversationId ?: return false
    return runCatching { HeadlessConversations.isHeadless(Uuid.parse(convId)) }.getOrDefault(false)
}

/** Classify a DocumentsProvider authority into a coarse kind. Shared with the unit test. */
internal fun classifyAuthority(authority: String): String = when {
    authority == "com.android.externalstorage.documents" -> "volume_root"
    authority == "com.android.providers.downloads.documents" -> "downloads"
    authority.contains("docs.storage") || authority.contains("dropbox") ||
        authority.contains("skydrive") || authority.contains("drive") -> "cloud"
    else -> "other"
}

// ---------- list_storage_volumes ----------

fun listStorageVolumesTool(context: Context): Tool = Tool(
    name = "list_storage_volumes",
    description = """
        List physical storage volumes (internal, SD card, USB) via StorageManager.
        Returns {volumes: [{id, label, type, primary, removable, mounted, free_bytes,
        total_bytes, granted, content_uri?}]}. For cloud / Downloads, use
        list_granted_directories.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val sm = context.getSystemService(StorageManager::class.java)
            ?: return@Tool esErr("feature unavailable")
        val grantedAuthorities = runCatching {
            context.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        }.getOrDefault(emptyList())
        listOf(UIMessagePart.Text(buildJsonObject {
            put("volumes", buildJsonArray {
                sm.storageVolumes.forEach { vol ->
                    addJsonObject {
                        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else null
                        val id = vol.uuid ?: dir?.absolutePath ?: vol.toString()
                        put("id", id)
                        put("label", vol.getDescription(context) ?: "Storage")
                        put("type", when {
                            vol.isPrimary -> "internal"
                            vol.isRemovable -> "sd"
                            else -> "external"
                        })
                        put("primary", vol.isPrimary)
                        put("removable", vol.isRemovable)
                        put("mounted", vol.state == android.os.Environment.MEDIA_MOUNTED)
                        val statDir = dir
                        if (statDir != null && statDir.exists()) {
                            put("free_bytes", statDir.freeSpace)
                            put("total_bytes", statDir.totalSpace)
                        } else {
                            put("free_bytes", 0L)
                            put("total_bytes", 0L)
                        }
                        // A volume is "granted" if any persisted tree URI is rooted under it.
                        val uuid = vol.uuid
                        val granted = uuid != null && grantedAuthorities.any { it.contains(uuid) }
                        put("granted", granted)
                    }
                }
            })
        }.toString()))
    },
)

// ---------- list_granted_directories ----------

fun listGrantedDirectoriesTool(
    context: Context,
    grantStore: StorageVolumeGrantStore,
): Tool = Tool(
    name = "list_granted_directories",
    description = """
        List directory trees the user has granted persistent access to via
        grant_directory_access (USB / SD / Downloads / cloud). Returns {directories:
        [{content_uri, display_name, authority, kind}]}. Self-reconciles against the OS
        persisted-permission list, so revoked grants drop off automatically.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val grants = grantStore.reconcile()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("directories", buildJsonArray {
                grants.forEach { g ->
                    addJsonObject {
                        put("content_uri", g.contentUri)
                        put("display_name", g.displayName)
                        put("authority", g.authority)
                        put("kind", classifyAuthority(g.authority))
                    }
                }
            })
        }.toString()))
    },
)

// ---------- grant_directory_access ----------

private const val SAF_PICKER_TIMEOUT_MS = 300_000L

fun grantDirectoryAccessTool(
    context: Context,
    grantStore: StorageVolumeGrantStore,
    buffer: SafPickerResultBuffer,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "grant_directory_access",
    description = """
        Open the system directory picker so the user can grant persistent read+write
        access to a storage tree (USB / SD / Downloads / Pictures / Drive / Dropbox /
        OneDrive). The grant lets the file tools and archive tools work on that tree.
        Returns {granted, content_uri?, display_name?, authority?}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("initial_uri", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional content:// tree URI to open the picker at")
                })
                put("request_label", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional human label shown in the approval card")
                })
            },
        )
    },
    execute = { input ->
        if (isHeadless(invocationContext)) {
            return@Tool esErr("feature unavailable in headless mode")
        }
        val initialUri = input.jsonObject["initial_uri"]?.jsonPrimitive?.contentOrNull

        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)
        val intent = Intent(context, ToolHostActivity::class.java).apply {
            putExtra(ToolHostActivity.EXTRA_MODE, ToolHostActivity.MODE_SAF_PICKER)
            putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
            if (!initialUri.isNullOrBlank()) {
                putExtra(ToolHostActivity.EXTRA_SAF_INITIAL_URI, initialUri)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        val result = withTimeoutOrNull(SAF_PICKER_TIMEOUT_MS) { deferred.await() }
        when (result) {
            is SafPickerResult.Granted -> {
                val uri = android.net.Uri.parse(result.contentUri)
                val authority = uri.authority ?: "unknown"
                val displayName = runCatching {
                    DocumentFile.fromTreeUri(context, uri)?.name
                }.getOrNull() ?: runCatching {
                    DocumentsContract.getTreeDocumentId(uri)
                }.getOrNull() ?: result.contentUri
                grantStore.add(
                    StorageVolumeGrantStore.Grant(
                        contentUri = result.contentUri,
                        displayName = displayName,
                        authority = authority,
                    )
                )
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("granted", true)
                    put("content_uri", result.contentUri)
                    put("display_name", displayName)
                    put("authority", authority)
                }.toString()))
            }
            is SafPickerResult.Cancelled -> listOf(UIMessagePart.Text(buildJsonObject {
                put("granted", false)
            }.toString()))
            is SafPickerResult.Error -> esErr(result.message)
            null -> {
                buffer.complete(requestId, SafPickerResult.Cancelled)
                listOf(UIMessagePart.Text(buildJsonObject { put("granted", false) }.toString()))
            }
        }
    },
)
