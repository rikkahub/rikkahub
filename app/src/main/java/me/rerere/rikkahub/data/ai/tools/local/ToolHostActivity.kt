package me.rerere.rikkahub.data.ai.tools.local

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.koin.android.ext.android.inject

class ToolHostActivity : AppCompatActivity() {

    private val cameraBuffer: CameraResultBuffer by inject()
    private val biometricBuffer: BiometricResultBuffer by inject()

    private var requestId: String = ""
    private var cameraOutputUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        cameraBuffer.complete(requestId, if (success) cameraOutputUri else null)
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

        const val MODE_CAMERA = "camera"
        const val MODE_BIOMETRIC = "biometric"
    }
}
