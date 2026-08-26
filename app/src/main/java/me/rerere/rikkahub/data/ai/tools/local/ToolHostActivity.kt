package me.rerere.rikkahub.data.ai.tools.local

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.android.ext.android.inject

/** Invisible activity used only to host Android's SAF picker for an AI tool call. */
class ToolHostActivity : ComponentActivity() {
    private val buffer: SafPickerResultBuffer by inject()
    private var requestId: String = ""

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            buffer.complete(requestId, SafPickerResult.Cancelled)
        } else {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val result = runCatching {
                contentResolver.takePersistableUriPermission(uri, flags)
                SafPickerResult.Granted(uri.toString())
            }.getOrElse { SafPickerResult.Error(it.message ?: "SAF grant failed") }
            buffer.complete(requestId, result)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) { finish(); return }
        val initial = intent.getStringExtra(EXTRA_INITIAL_URI)?.let(Uri::parse)
        runCatching { picker.launch(initial) }
            .onFailure {
                buffer.complete(requestId, SafPickerResult.Error(it.message ?: "SAF picker failed"))
                finish()
            }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "storage_request_id"
        const val EXTRA_INITIAL_URI = "storage_initial_uri"
    }
}
