package me.rerere.rikkahub.data.ai.tools.termux

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.koin.android.ext.android.inject

class TermuxResultService : Service() {
    private val termuxCommandManager: TermuxCommandManager by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val executionId = intent.getStringExtra(EXTRA_EXECUTION_ID)
            ?: intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
                .takeIf { it != -1 }
                ?.toString()
        if (executionId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val resultBundle = intent.getBundleExtra(TermuxProtocol.RESULT_BUNDLE)
        val result = if (resultBundle == null) {
            TermuxResult(
                errMsg = "Missing result bundle from Termux callback",
            )
        } else {
            TermuxResult(
                stdout = resultBundle.getString(TermuxProtocol.RESULT_STDOUT, ""),
                stderr = resultBundle.getString(TermuxProtocol.RESULT_STDERR, ""),
                exitCode = if (resultBundle.containsKey(TermuxProtocol.RESULT_EXIT_CODE)) {
                    resultBundle.getInt(TermuxProtocol.RESULT_EXIT_CODE)
                } else {
                    null
                },
                errCode = if (resultBundle.containsKey(TermuxProtocol.RESULT_ERR)) {
                    resultBundle.getInt(TermuxProtocol.RESULT_ERR)
                } else {
                    null
                },
                errMsg = resultBundle.getString(TermuxProtocol.RESULT_ERRMSG),
                stdoutOriginalLength = resultBundle
                    .getString(TermuxProtocol.RESULT_STDOUT_ORIGINAL_LENGTH)
                    ?.toIntOrNull(),
                stderrOriginalLength = resultBundle
                    .getString(TermuxProtocol.RESULT_STDERR_ORIGINAL_LENGTH)
                    ?.toIntOrNull(),
            )
        }

        termuxCommandManager.complete(executionId, result)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "execution_id"
    }
}
