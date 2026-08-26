package me.rerere.rikkahub.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.storageGrantDataStore by preferencesDataStore(name = "storage_volume_grants")

/** Persistent cache of user-approved SAF tree URIs. The OS permission remains authoritative. */
class StorageVolumeGrantStore(private val context: Context) {
    data class Grant(
        val contentUri: String,
        val displayName: String,
        val authority: String,
    )

    private val store = context.storageGrantDataStore
    private val grantsKey = stringPreferencesKey("grants")

    suspend fun loadAll(): List<Grant> = deserialize(store.data.first()[grantsKey].orEmpty())

    suspend fun add(grant: Grant) {
        store.edit { prefs ->
            prefs[grantsKey] = serialize(
                deserialize(prefs[grantsKey].orEmpty())
                    .filterNot { it.contentUri == grant.contentUri } + grant
            )
        }
    }

    suspend fun remove(contentUri: String) {
        store.edit { prefs ->
            prefs[grantsKey] = serialize(
                deserialize(prefs[grantsKey].orEmpty()).filterNot { it.contentUri == contentUri }
            )
        }
    }

    suspend fun reconcile(): List<Grant> {
        val persisted = context.contentResolver.persistedUriPermissions
            .map { it.uri.toString() }
            .toSet()
        val current = loadAll()
        val valid = current.filter { it.contentUri in persisted }
        if (valid.size != current.size) {
            store.edit { it[grantsKey] = serialize(valid) }
        }
        return valid
    }

    private fun serialize(grants: List<Grant>): String = grants.joinToString("\u001e") { grant ->
        listOf(grant.contentUri, grant.displayName, grant.authority).joinToString("\u001f")
    }

    private fun deserialize(raw: String): List<Grant> = raw
        .takeIf { it.isNotBlank() }
        ?.split("\u001e")
        ?.mapNotNull { line ->
            val parts = line.split("\u001f")
            if (parts.size == 3) Grant(parts[0], parts[1], parts[2]) else null
        }
        .orEmpty()
}
