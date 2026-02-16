package me.rerere.rikkahub.data.sync.backup

import android.content.Context
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "BackupLegacyUriRepair"
private const val PAGE_SIZE = 200

class BackupLegacyUriRepairManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val messageNodeDAO: MessageNodeDAO,
) {
    suspend fun runIfPendingRestoreRepair() {
        val settings = settingsStore.settingsFlow.value
        val rewrittenSettings = rewriteLegacyUploadUrisInSettings(settings, context.filesDir)
        val shouldRun = settings.pendingPostRestoreUriRepair || rewrittenSettings != settings
        if (!shouldRun) return

        if (rewrittenSettings != settings) {
            settingsStore.update(
                rewrittenSettings.copy(
                    pendingPostRestoreUriRepair = true
                )
            )
        }

        var scanned = 0
        var updated = 0
        var offset = 0

        while (true) {
            val page = messageNodeDAO.getNodeMessagesPaged(limit = PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            page.forEach { row ->
                scanned += 1
                runCatching {
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(row.messages)
                    val rewrittenMessages = rewriteLegacyUploadUrisInUiMessages(messages, context.filesDir)
                    if (rewrittenMessages != messages) {
                        messageNodeDAO.updateMessages(
                            nodeId = row.id,
                            messages = JsonInstant.encodeToString(rewrittenMessages)
                        )
                        updated += 1
                    }
                }.onFailure {
                    Log.w(TAG, "runIfPendingRestoreRepair: failed to repair node ${row.id}", it)
                }
            }

            offset += page.size
        }

        val now = System.currentTimeMillis()
        settingsStore.update {
            it.copy(
                pendingPostRestoreUriRepair = false,
                lastPostRestoreUriRepairAtEpochMillis = now
            )
        }
        Log.i(
            TAG,
            "runIfPendingRestoreRepair: completed, scanned=$scanned, updated=$updated"
        )
    }
}
