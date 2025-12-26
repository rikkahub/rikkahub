package me.rerere.rikkahub.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Stable
class ExporterState<T>(
    private val data: T,
    private val serializer: ExportSerializer<T>,
    private val context: Context,
    private val scope: CoroutineScope,
    private val createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
) {
    val value: String
        get() = serializer.exportToJson(data)

    val fileName: String
        get() = serializer.getExportFileName(data)

    fun exportToFile(fileName: String = this.fileName) {
        createDocumentLauncher.launch(fileName)
    }

    fun exportAndShare(fileName: String = this.fileName) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val cacheDir = File(context.cacheDir, "export")
                cacheDir.mkdirs()
                val file = File(cacheDir, fileName)
                file.writeText(value)
                file
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    internal fun writeToUri(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(value.toByteArray())
            }
        }
    }
}

@Composable
fun <T> rememberExporter(
    data: T,
    serializer: ExportSerializer<T>,
): ExporterState<T> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingState by remember { mutableStateOf<ExporterState<T>?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { pendingState?.writeToUri(it) }
    }

    val state = remember(data, serializer) {
        ExporterState(
            data = data,
            serializer = serializer,
            context = context,
            scope = scope,
            createDocumentLauncher = createDocumentLauncher,
        ).also { pendingState = it }
    }

    return state
}

@Stable
class ImporterState<T>(
    private val serializer: ExportSerializer<T>,
    private val context: Context,
    private val scope: CoroutineScope,
    private val openDocumentLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    private val onResult: (Result<T>) -> Unit,
) {
    fun importFromFile() {
        openDocumentLauncher.launch(arrayOf("application/json"))
    }

    internal fun handleUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                serializer.import(context, uri)
            }
            onResult(result)
        }
    }
}

@Composable
fun <T> rememberImporter(
    serializer: ExportSerializer<T>,
    onResult: (Result<T>) -> Unit,
): ImporterState<T> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingState by remember { mutableStateOf<ImporterState<T>?>(null) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pendingState?.handleUri(it) }
    }

    val state = remember(serializer) {
        ImporterState(
            serializer = serializer,
            context = context,
            scope = scope,
            openDocumentLauncher = openDocumentLauncher,
            onResult = onResult,
        ).also { pendingState = it }
    }

    return state
}
