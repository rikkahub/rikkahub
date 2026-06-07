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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.rerere.rikkahub.AppScope
import org.koin.compose.koinInject
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
        val content = value
        launchOwnedWrite(scope) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray())
            }
        }
    }
}

/**
 * Scope-ownership seam for [ExporterState.writeToUri] (#88). The fix is that [scope] here is the
 * application-wide AppScope, not `rememberCoroutineScope()`: the IO must outlive the screen that
 * started it. This helper is the exact production launch path, extracted so the ownership invariant
 * can be unit-tested on the JVM without a ContentResolver — pass an AppScope-like owner and a
 * caller-child owner to prove the write survives caller cancellation only on the former.
 *
 * The [yield] surrenders the dispatcher before the write runs, so a caller that cancels right after
 * triggering the action loses the write iff the work is bound to its (cancelled) Job — which is the
 * data-loss the AppScope move prevents. [dispatcher] defaults to IO for production; tests inject a
 * confined dispatcher to make the cancel-before-resume ordering deterministic.
 */
internal fun launchOwnedWrite(
    owner: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    write: suspend () -> Unit,
): Job =
    owner.launch(dispatcher) {
        yield()
        write()
    }

@Composable
fun <T> rememberExporter(
    data: T,
    serializer: ExportSerializer<T>,
): ExporterState<T> {
    val context = LocalContext.current
    val scope = koinInject<AppScope>()

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
        openDocumentLauncher.launch(arrayOf("*/*"))
    }

    internal fun handleUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val fileName = serializer.getUriFileName(context, uri)
                if (fileName != null && !fileName.endsWith(".json", ignoreCase = true)) {
                    return@withContext Result.failure<T>(IllegalArgumentException("Not a JSON file"))
                }
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
    val scope = koinInject<AppScope>()

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
