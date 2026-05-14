package me.rerere.rikkahub.data.ai.tools.local

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.koin.android.ext.android.inject

class ToolHostActivity : AppCompatActivity() {

    private val cameraBuffer: CameraResultBuffer by inject()
    private val biometricBuffer: BiometricResultBuffer by inject()
    private val nfcBuffer: NfcResultBuffer by inject()
    private val safBuffer: SafPickerResultBuffer by inject()

    private var requestId: String = ""
    private var cameraOutputUri: Uri? = null
    private var nfcAdapter: NfcAdapter? = null
    private var nfcCompleted = false

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        cameraBuffer.complete(requestId, if (success) cameraOutputUri else null)
        finish()
    }

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            safBuffer.complete(requestId, SafPickerResult.Cancelled)
        } else {
            val result = try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                SafPickerResult.Granted(uri.toString())
            } catch (_: SecurityException) {
                SafPickerResult.Error("provider does not support persistent grants")
            } catch (e: Throwable) {
                SafPickerResult.Error("saf_picker_failed: ${e.message ?: e::class.simpleName}")
            }
            safBuffer.complete(requestId, result)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: run {
            finish(); return
        }
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_CAMERA -> launchCamera()
            MODE_BIOMETRIC -> launchBiometric()
            MODE_NFC_READ, MODE_NFC_WRITE -> launchNfc()
            MODE_SAF_PICKER -> launchSafPicker()
            else -> finish()
        }
    }

    private fun launchCamera() {
        cameraOutputUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI)
        val uri = cameraOutputUri
        if (uri == null) {
            cameraBuffer.complete(requestId, null)
            finish(); return
        }
        cameraLauncher.launch(uri)
    }

    private fun launchSafPicker() {
        val initialUri = intent.getStringExtra(EXTRA_SAF_INITIAL_URI)?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        runCatching {
            safLauncher.launch(initialUri)
        }.onFailure {
            safBuffer.complete(
                requestId,
                SafPickerResult.Error("saf_picker_failed: ${it.message ?: it::class.simpleName}"),
            )
            finish()
        }
    }

    // ---------- NFC reader mode ----------

    private fun launchNfc() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            nfcBuffer.complete(requestId, NfcResult.Error("feature unavailable"))
            finish(); return
        }
        if (!adapter.isEnabled) {
            nfcBuffer.complete(requestId, NfcResult.Error("NFC is turned off in system settings"))
            finish(); return
        }
        nfcAdapter = adapter
        val timeoutSeconds = intent.getIntExtra(EXTRA_NFC_TIMEOUT, 30)
        val isWrite = intent.getStringExtra(EXTRA_MODE) == MODE_NFC_WRITE
        val recordsJson = intent.getStringExtra(EXTRA_NFC_RECORDS)

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, { tag ->
            if (isWrite) handleNfcWrite(tag, recordsJson) else handleNfcRead(tag)
        }, flags, null)

        // Auto-finish after the timeout if no tag was tapped.
        window.decorView.postDelayed({
            if (!nfcCompleted) {
                nfcCompleted = true
                runCatching { nfcAdapter?.disableReaderMode(this) }
                nfcBuffer.complete(requestId, NfcResult.Timeout)
                finish()
            }
        }, timeoutSeconds * 1000L)
    }

    private fun tagIdHex(tag: Tag): String =
        tag.id.joinToString("") { "%02x".format(it) }

    private fun handleNfcRead(tag: Tag) {
        if (nfcCompleted) return
        nfcCompleted = true
        val ndef = Ndef.get(tag)
        val result = if (ndef == null) {
            // Not NDEF — return an empty record set with the tag id so the model can
            // still report what it saw.
            NfcResult.ReadOk("[]", tagIdHex(tag))
        } else {
            try {
                ndef.connect()
                val message: NdefMessage? = ndef.ndefMessage ?: ndef.cachedNdefMessage
                val json = if (message == null) "[]"
                    else NfcNdefCodec.decode(message).toString()
                NfcResult.ReadOk(json, tagIdHex(tag))
            } catch (e: Throwable) {
                NfcResult.Error("read_failed: ${e.message ?: e::class.simpleName}")
            } finally {
                runCatching { ndef.close() }
            }
        }
        runCatching { nfcAdapter?.disableReaderMode(this) }
        nfcBuffer.complete(requestId, result)
        runOnUiThread { finish() }
    }

    private fun handleNfcWrite(tag: Tag, recordsJson: String?) {
        if (nfcCompleted) return
        nfcCompleted = true
        val result = run {
            if (recordsJson == null) return@run NfcResult.Error("no records to write")
            val ndef = Ndef.get(tag)
                ?: return@run NfcResult.Error("tag does not support NDEF")
            try {
                ndef.connect()
                if (!ndef.isWritable) return@run NfcResult.Error("tag is read-only")
                val array = Json.parseToJsonElement(recordsJson).jsonArray
                val message = NfcNdefCodec.encode(array)
                ndef.writeNdefMessage(message)
                NfcResult.WriteOk(tagIdHex(tag))
            } catch (e: Throwable) {
                NfcResult.Error("write_failed: ${e.message ?: e::class.simpleName}")
            } finally {
                runCatching { ndef.close() }
            }
        }
        runCatching { nfcAdapter?.disableReaderMode(this) }
        nfcBuffer.complete(requestId, result)
        runOnUiThread { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun launchBiometric() {
        val title = intent.getStringExtra(EXTRA_BIO_TITLE) ?: "Authenticate"
        val subtitle = intent.getStringExtra(EXTRA_BIO_SUBTITLE)
        val allowDeviceCredential = intent.getBooleanExtra(EXTRA_BIO_ALLOW_CRED, false)

        val authenticators = if (allowDeviceCredential) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val method = when (result.authenticationType) {
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL -> "device_credential"
                        else -> "biometric"
                    }
                    biometricBuffer.complete(requestId, BiometricResult.Success(method))
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val mapped = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> "user_cancelled"
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "lockout"
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> "hardware_unavailable"
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> "no_biometrics_enrolled"
                        else -> errString.toString()
                    }
                    biometricBuffer.complete(requestId, BiometricResult.Error(mapped))
                    finish()
                }

                override fun onAuthenticationFailed() {
                    // Auth attempt failed but prompt still open; do not finish — user may retry.
                }
            })

        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
        if (subtitle != null) infoBuilder.setSubtitle(subtitle)
        if (!allowDeviceCredential) infoBuilder.setNegativeButtonText("Cancel")

        prompt.authenticate(infoBuilder.build())
    }

    companion object {
        const val EXTRA_MODE = "tool_host_mode"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_BIO_TITLE = "bio_title"
        const val EXTRA_BIO_SUBTITLE = "bio_subtitle"
        const val EXTRA_BIO_ALLOW_CRED = "bio_allow_cred"
        const val EXTRA_NFC_TIMEOUT = "nfc_timeout"
        const val EXTRA_NFC_RECORDS = "nfc_records"
        const val EXTRA_SAF_INITIAL_URI = "saf_initial_uri"

        const val MODE_CAMERA = "camera"
        const val MODE_BIOMETRIC = "biometric"
        const val MODE_NFC_READ = "nfc_read"
        const val MODE_NFC_WRITE = "nfc_write"
        const val MODE_SAF_PICKER = "saf_picker"
    }
}
