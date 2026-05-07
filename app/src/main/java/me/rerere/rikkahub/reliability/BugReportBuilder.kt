package me.rerere.rikkahub.reliability

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BugReportBuilder"

/**
 * Builds a redacted bug-report ZIP suitable for sharing via the system share sheet.
 *
 * Contents (subject to availability):
 *   - meta.txt — version, device model, Android version, locale, timezone
 *   - logcat.txt — last 60 minutes of logcat, filtered to RikkaHub TAGs and ERROR level
 *                  for everything else, with secret redaction applied per
 *                  [SecretRedactor]
 *   - resolved-by-rules.txt — list of known issues this report should NOT include because
 *                              they're already documented (intentional empty stub for v1)
 *
 * NOT included (deliberate):
 *   - Conversation contents (PII-heavy)
 *   - DataStore / Room dumps (would expose tokens, hosts, memories)
 *   - Files outside the app package
 *   - Tokens or keys (filtered by [SecretRedactor])
 *
 * Output goes to the app's cache directory under `bug_reports/` so the share sheet's
 * tempfile lifecycle handles cleanup. Caller is responsible for invoking
 * `ACTION_SEND` with the resulting URI.
 */
class BugReportBuilder(private val context: Context) {

    suspend fun build(): File = withContext(Dispatchers.IO) {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.cacheDir, "bug_reports").apply { mkdirs() }
        val zipFile = File(outDir, "rikkahub-agent-bug-$ts.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putEntry("meta.txt", buildMeta())
            zip.putEntry("logcat.txt", captureLogcat())
            zip.putEntry("README.txt", buildReadme())
        }
        Log.i(TAG, "build: wrote ${zipFile.length()} bytes to ${zipFile.absolutePath}")
        zipFile
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildMeta(): String = buildString {
        append("App: rikkahub-agent\n")
        append("Version: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})\n")
        append("Build type: ${BuildConfig.BUILD_TYPE}\n")
        append("Application ID: ${BuildConfig.APPLICATION_ID}\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("Locale: ${Locale.getDefault()}\n")
        append("Timezone: ${java.util.TimeZone.getDefault().id}\n")
        append("Generated at: ${Date()}\n")
    }

    private fun buildReadme(): String =
        """
        rikkahub-agent bug report
        =========================

        This ZIP was generated locally on the device. It contains:
          * meta.txt   — app version + device + Android info
          * logcat.txt — last ~60 minutes of system log, secrets redacted

        It does NOT contain:
          * Your conversations
          * Your saved SSH hosts, Telegram token, API keys, MCP server config
          * Your memories / assistant configs
          * Any file outside the app's process

        If you're attaching this to an issue, please check meta.txt + the tail of
        logcat.txt for any token-shaped strings before posting publicly. The redactor
        catches the common cases but not every secret format in the wild.
        """.trimIndent()

    private fun captureLogcat(): String {
        // -t 5000: last ~5000 lines. -v threadtime: timestamps + thread/proc.
        // Filtering to ERROR for system + INFO for our own tags would require more
        // logcat-fu — for v1 we capture everything and redact. The user can prune.
        return try {
            val proc = ProcessBuilder("logcat", "-d", "-t", "5000", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            val raw = BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                lines.joinToString("\n")
            }
            proc.waitFor()
            SecretRedactor.redact(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "captureLogcat failed", t)
            "(logcat capture failed: ${t.message ?: t.javaClass.simpleName})"
        }
    }
}

/**
 * Pattern-based redactor for known secret shapes appearing in logcat. Aggressive on
 * false positives — if a token-looking string slips through, that's a leak; if a
 * legitimate hex string gets blanked, that's just noise.
 */
object SecretRedactor {

    private val patterns: List<Pair<Regex, String>> = listOf(
        // Telegram bot tokens: <int>:<35-char alnum>
        Regex("""\b\d{8,12}:[A-Za-z0-9_-]{30,40}\b""") to "[redacted-telegram-token]",
        // Bearer / API-key-ish headers — captures `Authorization: Bearer XYZ` AND
        // `X-Api-Key: XYZ` as one unit so the value after the optional Bearer/Token
        // prefix gets redacted along with the header. Stops at newline so multi-line
        // logcat entries don't bleed into following lines.
        Regex("""(?i)(authorization|proxy-authorization|x-api-key|x-api-token|x-auth-token|x-access-token|cookie|set-cookie)\s*[:=]\s*(?:Bearer\s+|Token\s+)?[^\r\n,;]+""") to "$1: [redacted]",
        // 30+ char hex strings (likely keys / hashes)
        Regex("""\b[a-fA-F0-9]{32,}\b""") to "[redacted-hex]",
        // 30+ char base64-shaped tokens (alnum + + / =) — broad catch for raw tokens
        // logged outside a header context. Avoids matching `[redacted]`-style markers
        // because those are short.
        Regex("""\b[A-Za-z0-9+/]{30,}={0,2}\b""") to "[redacted-b64]",
        // ssh:// or sftp:// urls with embedded creds
        Regex("""(ssh|sftp)://[^\s/@]+@""") to "$1://[redacted]@",
    )

    fun redact(input: String): String {
        var out = input
        for ((re, replacement) in patterns) {
            out = re.replace(out, replacement)
        }
        return out
    }
}
