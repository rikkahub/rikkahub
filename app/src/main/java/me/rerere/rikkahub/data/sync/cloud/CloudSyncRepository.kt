package me.rerere.rikkahub.data.sync.cloud

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.SyncOutboxDAO
import me.rerere.rikkahub.data.db.dao.SyncStateDAO
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import java.util.UUID

sealed class CloudSyncOutcome {
    data object Success : CloudSyncOutcome()
    data object Skipped : CloudSyncOutcome()
    data class Retryable(val message: String) : CloudSyncOutcome()
    data class Failed(val message: String) : CloudSyncOutcome()
}

/**
 * Local sync state + outbox. Network push/pull is wired when Perry client is configured.
 */
class CloudSyncRepository(
    private val outboxDao: SyncOutboxDAO,
    private val stateDao: SyncStateDAO,
) {
    fun observeOutboxCount(): Flow<Int> = outboxDao.observeCount()

    fun observeSyncMode(): Flow<SyncMode> =
        stateDao.observe().map { SyncMode.fromStorage(it?.syncMode) }

    suspend fun ensureState(): SyncStateEntity {
        val existing = stateDao.get()
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val created = SyncStateEntity(id = 1, updatedAt = now)
        stateDao.upsert(created)
        return created
    }

    suspend fun setSyncMode(mode: SyncMode) {
        val current = ensureState()
        stateDao.upsert(
            current.copy(
                syncMode = mode.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun enqueueMutation(
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String?,
        baseRevision: Long,
        payloadSchemaVersion: Int = 1,
    ): String? {
        val state = ensureState()
        if (SyncMode.fromStorage(state.syncMode) == SyncMode.LOCAL_ONLY) {
            return null
        }
        val now = System.currentTimeMillis()
        val mutationId = UUID.randomUUID().toString()
        outboxDao.insert(
            SyncOutboxEntity(
                mutationId = mutationId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payloadJson,
                baseRevision = baseRevision,
                payloadSchemaVersion = payloadSchemaVersion,
                retryCount = 0,
                nextRetryAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
        return mutationId
    }

    suspend fun runSyncCycle(): CloudSyncOutcome {
        val state = ensureState()
        val mode = SyncMode.fromStorage(state.syncMode)
        if (mode != SyncMode.AUTO) {
            return CloudSyncOutcome.Skipped
        }
        if (state.deviceId.isNullOrBlank()) {
            return CloudSyncOutcome.Skipped
        }
        // Network client is Phase 3+; for now mark success when there is nothing pending
        // or leave outbox for later push.
        val pending = outboxDao.listReady(System.currentTimeMillis(), limit = 1)
        if (pending.isEmpty()) {
            Log.d(TAG, "sync cycle: nothing to push")
            return CloudSyncOutcome.Success
        }
        Log.i(TAG, "sync cycle: ${pending.size}+ outbox items waiting for Perry client")
        return CloudSyncOutcome.Skipped
    }

    companion object {
        private const val TAG = "CloudSyncRepository"
    }
}
