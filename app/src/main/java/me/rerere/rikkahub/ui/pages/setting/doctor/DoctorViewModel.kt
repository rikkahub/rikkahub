package me.rerere.rikkahub.ui.pages.setting.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DoctorViewModel(
    private val checks: DoctorChecks,
) : ViewModel() {

    data class State(
        val results: List<DoctorCheck> = emptyList(),
        val running: Boolean = false,
        val lastRunAtMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { runAll() }

    fun runAll() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true)
            val results = runCatching { checks.runAll() }
                .getOrElse { t ->
                    listOf(
                        DoctorCheck(
                            id = "doctor.error",
                            category = DoctorCategory.Diagnostics,
                            label = "Doctor itself errored",
                            detail = "${t::class.simpleName}: ${t.message ?: "(no message)"}",
                            severity = Severity.FAIL,
                        )
                    )
                }
            _state.value = State(
                results = results,
                running = false,
                lastRunAtMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun applyAutoFix(fix: FixAction.AutoFix): AutoFixResult =
        runCatching { fix.run() }.getOrElse {
            AutoFixResult(ok = false, message = "${it::class.simpleName}: ${it.message.orEmpty()}")
        }.also {
            // Re-run all checks so the fix's effect shows up.
            runAll()
        }

    /** Plain-text dump suitable for copying to a support chat. Shared formatter. */
    fun buildReport(): String = DoctorReport.format(_state.value.results)
}
