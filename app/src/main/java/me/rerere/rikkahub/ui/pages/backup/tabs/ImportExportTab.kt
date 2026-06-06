package me.rerere.rikkahub.ui.pages.backup.tabs

import android.util.Log
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupOperationState
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "ImportExportTab"

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val operationState by vm.operationState.collectAsStateWithLifecycle()

    // Local backup export + restore are real operations owned by the VM (issue #105). Chatbox /
    // Cherry imports have no dedicated state Kind by design (architecture-design:105), so they keep a
    // local in-progress flag and inline handling.
    var isImportingOther by remember { mutableStateOf(false) }
    val isExporting = operationState ==
        BackupOperationState.Running(BackupOperationState.Kind.LocalExport)
    val isLocalRestoring = operationState ==
        BackupOperationState.Running(BackupOperationState.Kind.LocalRestore)
    // Every import button shares one "busy" gate to match prior behavior (any import disabled all).
    val isRestoring = isLocalRestoring || isImportingOther

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // Resolved in composition so the import coroutine lambdas don't read resource values off
    // LocalContext (lint LocalContextGetResourceValueCall). The failure template is formatted with
    // the throwable message at call time.
    val restoreSuccessText = stringResource(R.string.backup_page_restore_success)
    val restoreFailedTemplate = stringResource(R.string.backup_page_restore_failed, "%s")

    // Local export/restore success+error toasts are owned by the VM operation state; this tab only
    // renders them. Restart prompt fires on a successful local restore (parity with the old handler).
    LaunchedEffect(operationState) {
        when (val state = operationState) {
            is BackupOperationState.Success -> when (state.kind) {
                BackupOperationState.Kind.LocalExport -> {
                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                    vm.acknowledgeOperation()
                }

                BackupOperationState.Kind.LocalRestore -> {
                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                    onShowRestartDialog()
                    vm.acknowledgeOperation()
                }

                else -> Unit
            }

            is BackupOperationState.Error -> when (state.kind) {
                BackupOperationState.Kind.LocalExport,
                BackupOperationState.Kind.LocalRestore -> {
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, state.message),
                        type = ToastType.Error
                    )
                    vm.acknowledgeOperation()
                }

                else -> Unit
            }

            is BackupOperationState.Running, BackupOperationState.Idle -> Unit
        }
    }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            // The VM owns the operation lifecycle + state; this tab only supplies the URI sink.
            vm.exportToFile { exportFile ->
                context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    FileInputStream(exportFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
    }

    // 创建文件选择的launcher
    fun copyUriToTemp(sourceUri: android.net.Uri, suffix: String): File {
        val tempFile = File(context.cacheDir, "temp_${suffix}_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return tempFile
    }

    // Chatbox / Cherry imports have no dedicated state Kind by design (architecture-design:105), so
    // they run inline here; identical control flow, only the import call and temp suffix differ.
    fun importInline(sourceUri: android.net.Uri, suffix: String, restore: suspend (File) -> Unit) {
        scope.launch {
            isImportingOther = true
            val tempFile = copyUriToTemp(sourceUri, suffix)
            runCatching {
                restore(tempFile)
                toaster.show(restoreSuccessText, type = ToastType.Success)
                onShowRestartDialog()
            }.onFailure { e ->
                Log.e(TAG, "$suffix import failed", e)
                toaster.show(
                    restoreFailedTemplate.format(e.message ?: ""),
                    type = ToastType.Error
                )
            }
            tempFile.delete()
            isImportingOther = false
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            when (importType) {
                // Local restore is a real operation owned by the VM; toast + restart fire from the
                // operationState LaunchedEffect above.
                "local" -> vm.restoreFromLocalFile(
                    prepare = { copyUriToTemp(sourceUri, "restore") },
                )

                "chatbox" -> importInline(sourceUri, "chatbox") { vm.restoreFromChatBox(it) }
                "cherry" -> importInline(sourceUri, "cherry") { vm.restoreFromCherryStudio(it) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "local"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/json"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }
}
