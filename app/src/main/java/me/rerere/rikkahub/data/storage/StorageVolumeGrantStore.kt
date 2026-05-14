package me.rerere.rikkahub.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.storageGrantDataStore by preferencesDataStore(name = "storage_volume_grants")

/**
 * Phase 25 — DataStore-backed registry of SAF tree-URI grants the user has approved via
 * the `grant_directory_access` tool. Each entry maps a persistable `content://` tree URI
 * to a display name + authority for the LLM-facing `list_granted_directories` tool and
 * the Doctor "granted directories" row.
 *
 * This store is a *cache* — the OS-side `ContentResolver.persistedUriPermissions` is the
 * actual security gate. [reconcile] drops any entry the OS no longer holds a persisted
 * permission for (the user can revoke a grant from system Settings at any time), so the
 * store self-heals on the next read.
 *
 * Serialized form: one line per grant, `content_uridisplay_nameauthority`,
 * lines joined by ``. Control characters chosen so they can't collide with URI /
 * display-name content.
 */
class StorageVolumeGrantStore(private val context: Context) {

    data class Grant(
        val contentUri: String,
        val displayName: String,
        val authority: String,
    )

    private val store = context.storageGrantDataStore
    private val K_GRANTS = stringPreferencesKey("grants")

    val flow = store.data.map { p -> deserialize(p[K_GRANTS].orEmpty()) }

    /** All currently-recorded grants (does NOT reconcile — call [reconcile] for that). */
    suspend fun loadAll(): List<Grant> = flow.first()

    /** Add or replace a grant by content URI. */
    suspend fun add(grant: Grant) {
        store.edit { p ->
            val current = deserialize(p[K_GRANTS].orEmpty())
                .filterNot { it.contentUri == grant.contentUri }
            p[K_GRANTS] = serialize(current + grant)
        }
    }

    /** Remove a grant by content URI. No-op if absent. */
    suspend fun remove(contentUri: String) {
        store.edit { p ->
            val current = deserialize(p[K_GRANTS].orEmpty())
                .filterNot { it.contentUri == contentUri }
            p[K_GRANTS] = serialize(current)
        }
    }

    /**
     * Drop any stored grant the OS no longer holds a persisted URI permission for, then
     * return the surviving list. The user revoking a grant in system Settings is the
     * canonical reason an entry goes stale.
     */
    suspend fun reconcile(): List<Grant> {
        val held = runCatching {
            context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        }.getOrDefault(emptySet())
        val current = loadAll()
        val survivors = current.filter { it.contentUri in held }
        if (survivors.size != current.size) {
            store.edit { p -> p[K_GRANTS] = serialize(survivors) }
        }
        return survivors
    }

    companion object {
        private const val FIELD_SEP = "\u001F"
        private const val LINE_SEP = "\u001E"

        internal fun serialize(grants: List<Grant>): String =
            grants.joinToString(LINE_SEP) {
                listOf(it.contentUri, it.displayName, it.authority).joinToString(FIELD_SEP)
            }

        internal fun deserialize(raw: String): List<Grant> {
            if (raw.isBlank()) return emptyList()
            return raw.split(LINE_SEP).mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size != 3) null
                else Grant(parts[0], parts[1], parts[2])
            }
        }
    }
}
