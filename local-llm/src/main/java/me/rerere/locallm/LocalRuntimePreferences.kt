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
import kotlinx.serialization.builtins.SetSerializer
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

    /** Per-runtime set of model filenames whose GPU vision encoder failed to initialise on
     *  this device. The provider seeds this from [LiteRtRuntime.LoadOutcome.visionFellBackToTextOnly]
     *  the first time a model loads. Stored as a JSON-encoded set so a future fourth field
     *  doesn't need a schema migration. The user can clear an entry via the settings row
     *  ("re-try vision") if they install a driver update or move the file to a capable device. */
    private fun visionUnavailableKey(runtime: LocalRuntime) =
        stringPreferencesKey("vision_unavailable_${runtime.displayName}")

    /** The LiteRT-LM SDK version the persisted [accelerator], [visionUnavailable], and
     *  [crashRecovery] decisions were made under. When the compiled-in SDK version differs
     *  (an app update bumped the dependency), [maybeInvalidateOnSdkUpgrade] clears those
     *  stale decisions so the new SDK gets a fresh probe — picks up upstream fixes that
     *  would otherwise be masked by the previous fallback. */
    private fun sdkVersionKey(runtime: LocalRuntime) =
        stringPreferencesKey("sdk_version_${runtime.displayName}")

    /** Compiled-in SDK identifier. Bumped in lockstep with the dependency line in
     *  `local-llm/build.gradle.kts`. Mismatch with the persisted [sdkVersionKey] triggers
     *  the one-shot invalidation. */
    @Suppress("MemberVisibilityCanBePrivate")
    val currentSdkVersion: String = LITERTLM_SDK_VERSION

    /**
     * Compares the persisted SDK version for [runtime] against [currentSdkVersion]; on
     * mismatch (including first-ever boot when no version was persisted), clears stale
     * accelerator + vision-unavailable + crash-recovery flags so the new SDK is allowed
     * a fresh probe. Then writes [currentSdkVersion] back. Idempotent — second call in
     * the same app session is a no-op. Safe to call from app startup.
     */
    /** Per-modelId last-known telemetry sample: prefill tok/s and decode tok/s. Stored as
     *  a JSON map: { modelId -> "prefillTps|decodeTps|specDecodingEngaged|sampledAtMs" }. */
    private fun perfTelemetryKey(runtime: LocalRuntime) =
        stringPreferencesKey("perf_telemetry_${runtime.displayName}")

    data class PerfSample(
        val modelId: String,
        val prefillTps: Double,
        val decodeTps: Double,
        val specDecodingEngaged: Boolean,
        val sampledAtMs: Long,
    )

    private fun encodePerfMap(map: Map<String, PerfSample>): String =
        json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            map.mapValues { (_, s) ->
                "${s.prefillTps}|${s.decodeTps}|${s.specDecodingEngaged}|${s.sampledAtMs}"
            },
        )

    private fun decodePerfMap(raw: String?): Map<String, PerfSample> {
        if (raw.isNullOrBlank()) return emptyMap()
        val rawMap = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
        return rawMap.mapNotNull { (modelId, packed) ->
            val parts = packed.split("|")
            if (parts.size < 4) return@mapNotNull null
            val prefill = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val decode = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val spec = parts[2].toBooleanStrictOrNull() ?: false
            val ts = parts[3].toLongOrNull() ?: return@mapNotNull null
            modelId to PerfSample(modelId, prefill, decode, spec, ts)
        }.toMap()
    }

    /** Observe the last-known perf sample for every model under [runtime]. */
    fun perfTelemetryFlow(runtime: LocalRuntime): Flow<Map<String, PerfSample>> =
        context.localRuntimeDataStore.data.map { decodePerfMap(it[perfTelemetryKey(runtime)]) }

    /** One-shot read of the perf sample for [modelId], if any. */
    suspend fun perfTelemetry(runtime: LocalRuntime, modelId: String): PerfSample? =
        perfTelemetryFlow(runtime).first()[modelId]

    /** Record the latest perf sample for [modelId]. Overwrites the prior sample for the
     *  same modelId; other models' samples are preserved. */
    suspend fun setPerfTelemetry(runtime: LocalRuntime, sample: PerfSample) {
        context.localRuntimeDataStore.edit { prefs ->
            val current = decodePerfMap(prefs[perfTelemetryKey(runtime)]).toMutableMap()
            current[sample.modelId] = sample
            prefs[perfTelemetryKey(runtime)] = encodePerfMap(current)
        }
    }

    suspend fun maybeInvalidateOnSdkUpgrade(runtime: LocalRuntime): Boolean {
        var invalidated = false
        context.localRuntimeDataStore.edit { prefs ->
            val stored = prefs[sdkVersionKey(runtime)]
            if (stored != currentSdkVersion) {
                // Clear the three keys whose meaning is SDK-coupled. We DON'T clear
                // forceCpuKey (it's an explicit user preference, not an inferred one),
                // maxNumTokensOverrideKey (user-set override), or the installed-models
                // map (the model files themselves don't care which SDK installed them).
                prefs.remove(acceleratorKey(runtime))
                prefs.remove(visionUnavailableKey(runtime))
                prefs.remove(crashRecoveryKey(runtime))
                prefs[sdkVersionKey(runtime)] = currentSdkVersion
                invalidated = true
            }
        }
        return invalidated
    }

    companion object {
        /** Must mirror the SDK version pinned in `local-llm/build.gradle.kts`. Bumping
         *  the dep without bumping this constant will leave stale CPU/vision-unavailable
         *  decisions live; bumping this constant without bumping the dep is harmless
         *  (just causes a one-time unnecessary re-probe). */
        const val LITERTLM_SDK_VERSION: String = "0.11.0"
    }

    private fun decodeInstalledMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    private fun decodeStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            json.decodeFromString(SetSerializer(String.serializer()), raw)
        }.getOrDefault(emptySet())
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

    /** Observe the set of model filenames marked vision-unavailable for [runtime]. */
    fun visionUnavailableFlow(runtime: LocalRuntime): Flow<Set<String>> =
        context.localRuntimeDataStore.data.map { prefs ->
            decodeStringSet(prefs[visionUnavailableKey(runtime)])
        }

    /** One-shot read. */
    suspend fun visionUnavailable(runtime: LocalRuntime): Set<String> =
        visionUnavailableFlow(runtime).first()

    /** True when [fileName] previously failed its vision-encoder init for [runtime]. */
    suspend fun isVisionUnavailable(runtime: LocalRuntime, fileName: String): Boolean =
        fileName in visionUnavailable(runtime)

    /** Mark [fileName] as vision-unavailable. Idempotent. */
    suspend fun setVisionUnavailable(runtime: LocalRuntime, fileName: String) {
        context.localRuntimeDataStore.edit { prefs ->
            val current = decodeStringSet(prefs[visionUnavailableKey(runtime)]).toMutableSet()
            if (current.add(fileName)) {
                prefs[visionUnavailableKey(runtime)] = json.encodeToString(
                    SetSerializer(String.serializer()),
                    current,
                )
            }
        }
    }

    /** Clear the vision-unavailable flag for [fileName]. Used by the settings "Re-try vision"
     *  affordance — after the user updates GPU drivers / changes ROM / moves the file. */
    suspend fun clearVisionUnavailable(runtime: LocalRuntime, fileName: String) {
        context.localRuntimeDataStore.edit { prefs ->
            val current = decodeStringSet(prefs[visionUnavailableKey(runtime)]).toMutableSet()
            if (current.remove(fileName)) {
                if (current.isEmpty()) {
                    prefs.remove(visionUnavailableKey(runtime))
                } else {
                    prefs[visionUnavailableKey(runtime)] = json.encodeToString(
                        SetSerializer(String.serializer()),
                        current,
                    )
                }
            }
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
            // Also drop any vision-unavailable flag for this file. Reinstalling the same
            // filename on a new SDK / new ROM should get a fresh chance to load with vision.
            val visionRaw = prefs[visionUnavailableKey(runtime)]
            val visionSet = decodeStringSet(visionRaw).toMutableSet()
            if (visionSet.remove(fileName)) {
                if (visionSet.isEmpty()) {
                    prefs.remove(visionUnavailableKey(runtime))
                } else {
                    prefs[visionUnavailableKey(runtime)] = json.encodeToString(
                        SetSerializer(String.serializer()),
                        visionSet,
                    )
                }
            }
        }
    }
}
