package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File

/** 把 DeepSeek 导出的 ZIP 导入到当前助手，备份页和快速入门页共用。 */
class DeepSeekMigrator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) {
    suspend fun import(uri: Uri): DeepSeekImportResult = withContext(Dispatchers.IO) {
        // ZipFile 需要随机读取，先把文档复制成临时文件
        val tempFile = File.createTempFile("temp_deepseek_", ".zip", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)) {
                context.getString(R.string.backup_page_import_deepseek_read_error)
            }.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            import(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun import(file: File): DeepSeekImportResult = withContext(Dispatchers.IO) {
        val currentSettings = settingsStore.awaitLoaded()
        DeepSeekImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            shouldImportConversation = { !conversationRepository.existsConversationById(it) },
            formatMissingAttachment = { name ->
                context.getString(R.string.backup_page_import_deepseek_missing_attachment, name)
            },
            saveAttachment = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation.bindConfig(currentSettings))
            },
        )
    }
}
