package me.rerere.locallm

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    /** Per-runtime force-CPU override. The default is device-dependent (see
     *  [defaultForceCpu]): CPU for the Google Tensor crash class, GPU everywhere else.
     *  Users can flip it either way via the "Try GPU acceleration" toggle. */
    private fun forceCpuKey(runtime: LocalRuntime) =
        booleanPreferencesKey("force_cpu_${runtime.displayName}")

    /** The default value for [forceCpuFlow] when the user has not set a preference.
     *  Computed once from the SoC: GPU for capable devices, CPU for Google Tensor (where
     *  LiteRT-LM 0.11.0's GPU path has a native SIGSEGV). [AcceleratorProbe.defaultForceCpu]
     *  carries the rationale. The RikkaHubApp crash sweep + the runtime's GPU->CPU
     *  fallback both still backstop a wrong guess. */
    private val defaultForceCpu: Boolean by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            AcceleratorProbe.defaultForceCpu(
                socManufacturer = android.os.Build.SOC_MANUFACTURER,
                socModel = android.os.Build.SOC_MODEL,
            )
        } else {
            // SOC_* is API 31+. No Google Tensor device runs an OS this old, so we
            // cannot be on the crash class - default to the GPU fast path.
            false
        }
    }

    /** Stamped by [RikkaHubApp]'s ApplicationExitInfo sweep when the previous process
     *  exited with REASON_CRASH_NATIVE inside liblitertlm_jni.so. The settings UI
     *  reads this, shows a friendly recovery banner, and clears it on dismiss. Value
     *  is the accelerator label that crashed (so the banner can name it). */
    private fun crashRecoveryKey(runtime: LocalRuntime) =
        stringPreferencesKey("crash_recovery_${runtime.displayName}")

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

    /** True means the probe ALWAYS returns "CPU" regardless of device capabilities;
     *  false lets the probe pick GPU/NNAPI/QNN. When the user has set no preference,
     *  falls back to the device-dependent [defaultForceCpu]. */
    fun forceCpuFlow(runtime: LocalRuntime): Flow<Boolean> =
        context.localRuntimeDataStore.data.map { it[forceCpuKey(runtime)] ?: defaultForceCpu }

    /** Per-runtime override for `EngineConfig.maxNumTokens`. Null (default) means use
     *  the per-model curated value from `LiteRtModelDefaults`. Setting it lets users
     *  unlock the headroom on capable models (e.g. Gemma 4 E2B's 32k context where
     *  Gallery's default is 4000). The model's underlying KV cache size is still a
     *  hard ceiling — Qwen's `ekv4096` files cannot exceed 4096 regardless of this. */
    fun maxNumTokensOverrideFlow(runtime: LocalRuntime): Flow<Int?> =
        context.localRuntimeDataStore.data.map { it[maxNumTokensOverrideKey(runtime)] }

    suspend fun maxNumTokensOverride(runtime: LocalRuntime): Int? =
        maxNumTokensOverrideFlow(runtime).first()

    suspend fun setMaxNumTokensOverride(runtime: LocalRuntime, value: Int?) {
        context.localRuntimeDataStore.edit { prefs ->
            if (value == null) prefs.remove(maxNumTokensOverrideKey(runtime))
            else prefs[maxNumTokensOverrideKey(runtime)] = value
        }
    }

    private fun maxNumTokensOverrideKey(runtime: LocalRuntime) =
        androidx.datastore.preferences.core.intPreferencesKey("max_tokens_${runtime.displayName}")

    suspend fun forceCpu(runtime: LocalRuntime): Boolean = forceCpuFlow(runtime).first()

    suspend fun setForceCpu(runtime: LocalRuntime, force: Boolean) {
        context.localRuntimeDataStore.edit { it[forceCpuKey(runtime)] = force }
    }

    /** Non-null when the prior process exited with a native crash inside the runtime's
     *  JNI lib. Holds the accelerator label that crashed so the recovery banner can
     *  name it ("crashed on GPU last session — switched to CPU"). */
    fun crashRecoveryFlow(runtime: LocalRuntime): Flow<String?> =
        context.localRuntimeDataStore.data.map { it[crashRecoveryKey(runtime)] }

    suspend fun setCrashRecovery(runtime: LocalRuntime, crashedAccelerator: String) {
        context.localRuntimeDataStore.edit { it[crashRecoveryKey(runtime)] = crashedAccelerator }
    }

    suspend fun clearCrashRecovery(runtime: LocalRuntime) {
        context.localRuntimeDataStore.edit { it.remove(crashRecoveryKey(runtime)) }
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
            if (current.isEmpty()) {
                // Remove the key entirely rather than writing an empty JSON object; this keeps the
                // DataStore clean and makes the "no models installed" path symmetric with the
                // initial state (key absent == empty map via decodeInstalledMap).
                prefs.remove(installedModelsKey(runtime))
            } else {
                prefs[installedModelsKey(runtime)] = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    current,
                )
            }
        }
    }
}
