package me.rerere.locallm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val Context.localRuntimeDataStore by preferencesDataStore(name = "local_runtime_prefs")

/**
 * Persistent state for the on-device runtimes:
 *  - The cached accelerator decision per runtime (so we don't re-probe on every app start).
 *  - The installed-models index per runtime (filename -> absolute path on disk).
 *
 * One DataStore instance, two key sets, both keyed on the [LocalRuntime] enum's
 * displayName so a future third runtime doesn't need a schema migration.
 */
class LocalRuntimePreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun acceleratorKey(runtime: LocalRuntime) =
        stringPreferencesKey("accel_${runtime.displayName}")

    private fun installedModelsKey(runtime: LocalRuntime) =
        stringPreferencesKey("installed_${runtime.displayName}")

    private fun decodeInstalledMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    fun acceleratorFlow(runtime: LocalRuntime): Flow<String?> =
        context.localRuntimeDataStore.data.map { it[acceleratorKey(runtime)] }

    suspend fun setAccelerator(runtime: LocalRuntime, accel: String) {
        context.localRuntimeDataStore.edit { it[acceleratorKey(runtime)] = accel }
    }

    suspend fun clearAccelerator(runtime: LocalRuntime) {
        context.localRuntimeDataStore.edit { it.remove(acceleratorKey(runtime)) }
    }

    fun installedModelsFlow(runtime: LocalRuntime): Flow<Map<String, String>> =
        context.localRuntimeDataStore.data.map { prefs ->
            val raw = prefs[installedModelsKey(runtime)]
            decodeInstalledMap(raw)
        }

    suspend fun installedModels(runtime: LocalRuntime): Map<String, String> =
        installedModelsFlow(runtime).first()

    suspend fun addInstalledModel(runtime: LocalRuntime, fileName: String, absolutePath: String) {
        context.localRuntimeDataStore.edit { prefs ->
            val raw = prefs[installedModelsKey(runtime)]
            val current = decodeInstalledMap(raw).toMutableMap()
            current[fileName] = absolutePath
            prefs[installedModelsKey(runtime)] = json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                current,
            )
        }
    }

    suspend fun removeInstalledModel(runtime: LocalRuntime, fileName: String) {
        context.localRuntimeDataStore.edit { prefs ->
            val raw = prefs[installedModelsKey(runtime)]
            val current = decodeInstalledMap(raw).toMutableMap()
            current.remove(fileName)
            prefs[installedModelsKey(runtime)] = json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                current,
            )
        }
    }
}
