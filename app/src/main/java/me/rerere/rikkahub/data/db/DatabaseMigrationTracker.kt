package me.rerere.rikkahub.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class MigrationState {
    data object Idle : MigrationState()
    data class Migrating(val from: Int, val to: Int) : MigrationState()
}

object DatabaseMigrationTracker {
    private val _state = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val state: StateFlow<MigrationState> = _state.asStateFlow()

    fun onMigrationStart(from: Int, to: Int) {
        _state.value = MigrationState.Migrating(from, to)
    }

    fun onMigrationEnd() {
        _state.value = MigrationState.Idle
    }
}
