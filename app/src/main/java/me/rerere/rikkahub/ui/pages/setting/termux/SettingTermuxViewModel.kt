package me.rerere.rikkahub.ui.pages.setting.termux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.preferences.TermuxRuntimeConfig

class SettingTermuxViewModel(
    private val prefs: TermuxPreferences,
) : ViewModel() {

    /**
     * Combined settings state. Nested [combine] calls stay within the 5-argument typed
     * overloads to avoid the intersection-type warning from the vararg overload.
     */
    val config: StateFlow<TermuxRuntimeConfig> = combine(
        combine(
            prefs.commandTimeoutFlow(),
            prefs.turnBudgetFlow(),
            prefs.verifyTimeoutFlow(),
            prefs.defaultWorkingDirFlow(),
            prefs.maxStdoutFlow(),
        ) { commandTimeout, turnBudget, verifyTimeout, workingDir, maxStdout ->
            Partial(commandTimeout, turnBudget, verifyTimeout, workingDir, maxStdout)
        },
        prefs.maxStderrFlow(),
        prefs.aptWrapEnabledFlow(),
    ) { partial, maxStderr, aptWrap ->
        TermuxRuntimeConfig(
            commandTimeoutMs  = partial.commandTimeoutMs,
            turnBudgetMs      = partial.turnBudgetMs,
            verifyTimeoutMs   = partial.verifyTimeoutMs,
            defaultWorkingDir = partial.defaultWorkingDir,
            maxStdoutBytes    = partial.maxStdoutBytes,
            maxStderrBytes    = maxStderr,
            aptWrapEnabled    = aptWrap,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TermuxRuntimeConfig(
            commandTimeoutMs  = TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
            turnBudgetMs      = TermuxDefaults.DEFAULT_TURN_BUDGET_MS,
            verifyTimeoutMs   = TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
            defaultWorkingDir = TermuxDefaults.DEFAULT_WORKING_DIR,
            maxStdoutBytes    = TermuxDefaults.DEFAULT_MAX_STDOUT,
            maxStderrBytes    = TermuxDefaults.DEFAULT_MAX_STDERR,
            aptWrapEnabled    = TermuxDefaults.DEFAULT_APT_WRAP_ENABLED,
        ),
    )

    // --- Write helpers. Unit conversion happens here so [TermuxPreferences] always receives ms/bytes. ---

    /** [seconds] is the UI display unit for command timeout. Clamping in [TermuxPreferences]. */
    fun setCommandTimeoutSeconds(seconds: Long) {
        viewModelScope.launch { prefs.setCommandTimeoutMs(seconds * 1_000L) }
    }

    /** [minutes] is the UI display unit for turn budget. Clamping in [TermuxPreferences]. */
    fun setTurnBudgetMinutes(minutes: Long) {
        viewModelScope.launch { prefs.setTurnBudgetMs(minutes * 60_000L) }
    }

    /** [seconds] is the UI display unit for verify timeout. */
    fun setVerifyTimeoutSeconds(seconds: Long) {
        viewModelScope.launch { prefs.setVerifyTimeoutMs(seconds * 1_000L) }
    }

    fun setDefaultWorkingDir(dir: String) {
        viewModelScope.launch { prefs.setDefaultWorkingDir(dir) }
    }

    fun setMaxStdoutBytes(bytes: Int) {
        viewModelScope.launch { prefs.setMaxStdoutBytes(bytes) }
    }

    fun setMaxStderrBytes(bytes: Int) {
        viewModelScope.launch { prefs.setMaxStderrBytes(bytes) }
    }

    fun setAptWrapEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAptWrapEnabled(enabled) }
    }

    // Private intermediate holder to avoid 7-flow combine vararg.
    private data class Partial(
        val commandTimeoutMs: Long,
        val turnBudgetMs: Long,
        val verifyTimeoutMs: Long,
        val defaultWorkingDir: String,
        val maxStdoutBytes: Int,
    )
}
