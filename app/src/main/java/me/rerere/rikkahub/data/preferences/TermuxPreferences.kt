package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits

private val Context.termuxDataStore by preferencesDataStore(name = "termux_prefs")

/**
 * DataStore-backed settings store for Termux-specific knobs, plus the app-wide per-turn
 * wall-clock budget (surfaced here because GitHub issue #5 requested it alongside the
 * Termux timeouts). Mirrors [me.rerere.rikkahub.browser.BrowserPreferences] in shape.
 *
 * The [init] block pushes persisted values into the runtime holders ([TermuxRuntime] and
 * [ToolRuntimeLimits]) immediately on construction so all non-suspend callers (TermuxTool,
 * GenerationHandler) read live values without needing a coroutine context.
 *
 * All read paths clamp on read; all write paths clamp on write. A value stored from an
 * older build that exceeds a tightened ceiling is silently clamped on the next read.
 */
class TermuxPreferences(private val context: Context) {

    private val store = context.termuxDataStore

    private val commandTimeoutKey = longPreferencesKey("command_timeout_ms")
    private val turnBudgetKey     = longPreferencesKey("turn_budget_ms")
    private val verifyTimeoutKey  = longPreferencesKey("verify_timeout_ms")
    private val workingDirKey     = stringPreferencesKey("working_dir")
    private val maxStdoutKey      = intPreferencesKey("max_stdout_bytes")
    private val maxStderrKey      = intPreferencesKey("max_stderr_bytes")
    private val aptWrapKey        = booleanPreferencesKey("apt_wrap_enabled")

    init {
        // Seed the runtime holders SYNCHRONOUSLY from DataStore before starting the async
        // collectors. Without this, a Termux tool call in a session that never opened
        // Settings -> Termux would use compile-time defaults rather than saved values,
        // because Koin singles are lazy and the collectors below wouldn't have emitted yet.
        // Matches the approach BrowserPreferences uses (snapshotBlocking at the LocalTools
        // registration site). The one-time blocking read is acceptable — DataStore caches the
        // latest Preferences instance after its first decode, so steady-state cost is
        // a flow .first() against an in-memory replay.
        val initial = snapshotBlocking()
        TermuxRuntime.commandTimeoutMs   = initial.commandTimeoutMs
        TermuxRuntime.verifyTimeoutMs    = initial.verifyTimeoutMs
        TermuxRuntime.defaultWorkingDir  = initial.defaultWorkingDir
        TermuxRuntime.maxStdoutBytes     = initial.maxStdoutBytes
        TermuxRuntime.maxStderrBytes     = initial.maxStderrBytes
        TermuxRuntime.aptWrapEnabled     = initial.aptWrapEnabled
        ToolRuntimeLimits.turnBudgetMs   = initial.turnBudgetMs

        // Async collectors keep the holders live on subsequent user edits. This scope is
        // intentionally NOT stored as a field — it is process-lived and we want it to stay
        // alive as long as the singleton itself. SupervisorJob means one failing collector
        // doesn't kill the others.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            commandTimeoutFlow()
                .distinctUntilChanged()
                .onEach { TermuxRuntime.commandTimeoutMs = it }
                .collect {}
        }
        scope.launch {
            turnBudgetFlow()
                .distinctUntilChanged()
                .onEach { ToolRuntimeLimits.turnBudgetMs = it }
                .collect {}
        }
        scope.launch {
            verifyTimeoutFlow()
                .distinctUntilChanged()
                .onEach { TermuxRuntime.verifyTimeoutMs = it }
                .collect {}
        }
        scope.launch {
            defaultWorkingDirFlow()
                .distinctUntilChanged()
                .onEach { TermuxRuntime.defaultWorkingDir = it }
                .collect {}
        }
        scope.launch {
            maxStdoutFlow()
                .distinctUntilChanged()
                .onEach { TermuxRuntime.maxStdoutBytes = it }
                .collect {}
        }
        scope.launch {
            maxStderrFlow()
                .distinctUntilChanged()
                .onEach { TermuxRuntime.maxStderrBytes = it }
                .collect {}
        }
        scope.launch {
            aptWrapEnabledFlow()
                .distinctUntilChanged()
                .onEach { TermuxRuntime.aptWrapEnabled = it }
                .collect {}
        }
    }

    // --- Flow accessors -------------------------------------------------------------------

    fun commandTimeoutFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampCommandTimeoutMs(
            prefs[commandTimeoutKey] ?: TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS
        )
    }

    fun turnBudgetFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampTurnBudgetMs(
            prefs[turnBudgetKey] ?: TermuxDefaults.DEFAULT_TURN_BUDGET_MS
        )
    }

    fun verifyTimeoutFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampVerifyTimeoutMs(
            prefs[verifyTimeoutKey] ?: TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS
        )
    }

    fun defaultWorkingDirFlow(): Flow<String> = store.data.map { prefs ->
        TermuxDefaults.clampWorkingDir(
            prefs[workingDirKey] ?: TermuxDefaults.DEFAULT_WORKING_DIR
        )
    }

    fun maxStdoutFlow(): Flow<Int> = store.data.map { prefs ->
        TermuxDefaults.clampMaxStdout(
            prefs[maxStdoutKey] ?: TermuxDefaults.DEFAULT_MAX_STDOUT
        )
    }

    fun maxStderrFlow(): Flow<Int> = store.data.map { prefs ->
        TermuxDefaults.clampMaxStderr(
            prefs[maxStderrKey] ?: TermuxDefaults.DEFAULT_MAX_STDERR
        )
    }

    fun aptWrapEnabledFlow(): Flow<Boolean> = store.data.map { prefs ->
        prefs[aptWrapKey] ?: TermuxDefaults.DEFAULT_APT_WRAP_ENABLED
    }

    // --- Suspend writers (clamped before persist) -----------------------------------------

    suspend fun setCommandTimeoutMs(ms: Long) {
        store.edit { it[commandTimeoutKey] = TermuxDefaults.clampCommandTimeoutMs(ms) }
    }

    suspend fun setTurnBudgetMs(ms: Long) {
        store.edit { it[turnBudgetKey] = TermuxDefaults.clampTurnBudgetMs(ms) }
    }

    suspend fun setVerifyTimeoutMs(ms: Long) {
        store.edit { it[verifyTimeoutKey] = TermuxDefaults.clampVerifyTimeoutMs(ms) }
    }

    suspend fun setDefaultWorkingDir(dir: String) {
        store.edit { it[workingDirKey] = TermuxDefaults.clampWorkingDir(dir) }
    }

    suspend fun setMaxStdoutBytes(bytes: Int) {
        store.edit { it[maxStdoutKey] = TermuxDefaults.clampMaxStdout(bytes) }
    }

    suspend fun setMaxStderrBytes(bytes: Int) {
        store.edit { it[maxStderrKey] = TermuxDefaults.clampMaxStderr(bytes) }
    }

    suspend fun setAptWrapEnabled(enabled: Boolean) {
        store.edit { it[aptWrapKey] = enabled }
    }

    /**
     * One-shot suspend snapshot for callers that need all fields at once (e.g. the VM's
     * combined state flow). Fields are clamped on read, same as the individual flow accessors.
     */
    suspend fun snapshot(): TermuxRuntimeConfig {
        val prefs = store.data.first()
        return TermuxRuntimeConfig(
            commandTimeoutMs   = TermuxDefaults.clampCommandTimeoutMs(prefs[commandTimeoutKey] ?: TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS),
            turnBudgetMs       = TermuxDefaults.clampTurnBudgetMs(prefs[turnBudgetKey]         ?: TermuxDefaults.DEFAULT_TURN_BUDGET_MS),
            verifyTimeoutMs    = TermuxDefaults.clampVerifyTimeoutMs(prefs[verifyTimeoutKey]    ?: TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS),
            defaultWorkingDir  = TermuxDefaults.clampWorkingDir(prefs[workingDirKey]            ?: TermuxDefaults.DEFAULT_WORKING_DIR),
            maxStdoutBytes     = TermuxDefaults.clampMaxStdout(prefs[maxStdoutKey]              ?: TermuxDefaults.DEFAULT_MAX_STDOUT),
            maxStderrBytes     = TermuxDefaults.clampMaxStderr(prefs[maxStderrKey]              ?: TermuxDefaults.DEFAULT_MAX_STDERR),
            aptWrapEnabled     = prefs[aptWrapKey]                                              ?: TermuxDefaults.DEFAULT_APT_WRAP_ENABLED,
        )
    }

    /** Blocking variant used by non-suspend callers. See [me.rerere.rikkahub.browser.BrowserPreferences.snapshotBlocking]. */
    fun snapshotBlocking(): TermuxRuntimeConfig = kotlinx.coroutines.runBlocking { snapshot() }
}

/**
 * Immutable snapshot of all Termux preferences, used by the ViewModel to expose a single
 * combined state flow instead of seven separate ones.
 */
data class TermuxRuntimeConfig(
    val commandTimeoutMs: Long,
    val turnBudgetMs: Long,
    val verifyTimeoutMs: Long,
    val defaultWorkingDir: String,
    val maxStdoutBytes: Int,
    val maxStderrBytes: Int,
    val aptWrapEnabled: Boolean,
)
