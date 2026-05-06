package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

// Whisper model paths that whisper-cli checks by default, in preference order.
// The first path that contains a .bin file wins. The user only needs one model.
private val WHISPER_MODEL_SEARCH_PATHS = listOf(
    // Standard Termux whisper.cpp package default
    "/data/data/com.termux/files/home/.cache/whisper-models",
    // Common alternate location used by manual builds
    "/data/data/com.termux/files/home/whisper.cpp/models",
    // Our own suggested install path (documented in error hint)
    "/data/data/com.termux/files/home/.local/share/whisper",
)

// whisper-cli binary installed by Termux's whisper.cpp package
private const val WHISPER_CLI_BIN = "/data/data/com.termux/files/usr/bin/whisper-cli"

// The preferred model. tiny is the smallest (75 MB) and works offline.
private const val PREFERRED_MODEL_NAME = "ggml-tiny.bin"

/**
 * LLM-callable audio transcription tool. Delegates to whisper.cpp's `whisper-cli`
 * binary which must be installed in Termux (`pkg install whisper.cpp`).
 *
 * This tool registers under the *Termux* toggle in LocalTools — it has a hard
 * transitive dependency on Termux being available. It is NOT its own toggle.
 *
 * Future plan: replace the Termux shell-out with bundled whisper.cpp via NDK/JNI so
 * there is no Termux dependency. That work is tracked as a separate spec phase.
 */
fun transcribeAudioFileTool(context: Context): Tool = Tool(
    name = "transcribe_audio_file",
    description = """
        Transcribe the speech in an audio file to text using whisper.cpp (Termux).
        Accepts any format whisper.cpp can decode: OGG/Opus (Telegram voice notes),
        WAV, MP3, M4A, FLAC. Returns the transcribed text, detected language, audio
        duration, and transcription time. Requires whisper.cpp installed in Termux
        (`pkg install whisper.cpp`) and a model file (see `hint` in the error envelope
        if the model is missing). Path must be absolute and outside system directories.
        IMPORTANT: do NOT use `play_media` to "hear" a voice note and then guess its
        content — that is a hallucination. Use this tool to get the actual words.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to the audio file to transcribe (e.g. /sdcard/Download/telegram_inbox/12345/voice.ogg)")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "BCP-47 language code hint for whisper (e.g. 'en', 'ar', 'zh'). Omit or pass 'auto' to let whisper auto-detect (default).")
                })
            },
            required = listOf("path")
        )
    },
    needsApproval = true,
    execute = { input ->
        val params = input.jsonObject
        val path = params["path"]?.jsonPrimitive?.contentOrNull
        val language = params["language"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "auto" }

        // --- 1. Path required ---
        if (path.isNullOrBlank()) {
            return@Tool errEnv("missing_path", "path is required (absolute path to an audio file)")
        }

        // --- 2. Path safety ---
        PathSafetyGuard.check(path)?.let { v ->
            return@Tool errEnv(v.code, v.detail)
        }

        // --- 3. File existence + type checks ---
        val audioFile = File(path)
        if (!audioFile.exists()) {
            return@Tool errEnv("not_found", "File does not exist: $path")
        }
        if (audioFile.isDirectory) {
            return@Tool errEnv("is_directory", "Path is a directory, not an audio file: $path")
        }
        if (audioFile.length() == 0L) {
            return@Tool errEnv("empty_file", "Audio file is empty (0 bytes): $path")
        }

        // --- 4. Termux state check ---
        when (TermuxIntegration.state(context)) {
            TermuxIntegration.State.NOT_INSTALLED ->
                return@Tool errEnv(
                    "termux_not_installed",
                    "Termux is not installed. transcribe_audio_file requires Termux + whisper.cpp."
                )
            TermuxIntegration.State.NO_PERMISSION ->
                return@Tool errEnv(
                    "termux_permission_not_granted",
                    "Termux RUN_COMMAND permission is not granted. Enable the Termux toggle in Local tools first."
                )
            TermuxIntegration.State.READY -> Unit
        }

        // --- 5. Verify whisper-cli is installed ---
        val whichResult = runCommandCapture(
            ctx = context,
            executable = "$TERMUX_BIN_DIR/bash",
            arguments = arrayOf("-c", "which whisper-cli 2>/dev/null || echo MISSING"),
            workingDir = TERMUX_HOME_DIR,
            timeoutMs = 10_000L,
        )
        val whisperBin = when (whichResult) {
            is CaptureResult.Success -> {
                val out = whichResult.stdout.trim()
                if (out == "MISSING" || out.isBlank() || whichResult.exitCode != 0) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "whisper_not_installed")
                        put("detail", "whisper.cpp is not installed in Termux. Run: pkg install whisper.cpp")
                        put("hint", "After installation, also download a model: bash -c 'mkdir -p ~/.cache/whisper-models && cd ~/.cache/whisper-models && wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin'")
                    }.toString()))
                }
                // Prefer the well-known bin path; fall back to what `which` resolved
                if (File(WHISPER_CLI_BIN).exists()) WHISPER_CLI_BIN else out
            }
            is CaptureResult.Timeout ->
                return@Tool errEnv("termux_timeout", "Timed out checking for whisper-cli. Ensure Termux is running and allow-external-apps=true is set.")
            is CaptureResult.Denied ->
                return@Tool errEnv("termux_permission_denied", "Termux rejected the command. Ensure allow-external-apps=true in ~/.termux/termux.properties.")
            is CaptureResult.OtherError ->
                return@Tool errEnv("termux_error", whichResult.message)
        }

        // --- 6. Locate a whisper model ---
        val modelFile = findWhisperModel()
        if (modelFile == null) {
            val searchedPaths = WHISPER_MODEL_SEARCH_PATHS.joinToString(", ")
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "whisper_model_missing")
                put("detail", "No whisper model (.bin) found in: $searchedPaths")
                put("hint", "Download with: mkdir -p ~/.cache/whisper-models && cd ~/.cache/whisper-models && wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin")
            }.toString()))
        }

        // --- 7. Run whisper-cli ---
        // whisper-cli writes its text output to <output_file>.txt when -otxt and -of are used.
        // We derive a temp output path inside Termux home so whisper can write to it.
        val baseName = "rikkahub_transcribe_${System.currentTimeMillis()}"
        val outputBase = "/data/data/com.termux/files/home/$baseName"
        val outputTxt = "$outputBase.txt"

        val whisperArgs = buildList {
            add("-m"); add(modelFile.absolutePath)
            add("-f"); add(path)
            add("--no-timestamps")
            add("-otxt")
            add("-of"); add(outputBase)
            if (language != null) {
                add("-l"); add(language)
            }
        }

        val whisperCmd = (listOf(whisperBin) + whisperArgs).joinToString(" ") { arg ->
            // Shell-quote each argument to handle spaces in paths
            "'${arg.replace("'", "'\\''")}'"
        }

        val startMs = System.currentTimeMillis()
        val transcribeResult = runCommandCapture(
            ctx = context,
            executable = "$TERMUX_BIN_DIR/bash",
            arguments = arrayOf("-c", whisperCmd),
            workingDir = TERMUX_HOME_DIR,
            timeoutMs = 5L * 60 * 1000, // 5 min for long audio
        )
        val transcribeTimeMs = System.currentTimeMillis() - startMs

        return@Tool when (transcribeResult) {
            is CaptureResult.Success -> {
                if (transcribeResult.exitCode != 0) {
                    val stderr = transcribeResult.stderr.take(1000)
                    val stdout = transcribeResult.stdout.take(500)
                    return@Tool errEnv(
                        "whisper_failed",
                        "whisper-cli exited with code ${transcribeResult.exitCode}. stderr: $stderr. stdout: $stdout"
                    )
                }

                // Read the output .txt file whisper-cli wrote
                val outFile = File(outputTxt)
                if (!outFile.exists()) {
                    return@Tool errEnv(
                        "output_missing",
                        "whisper-cli succeeded (exit 0) but did not write output to $outputTxt. stderr: ${transcribeResult.stderr.take(500)}"
                    )
                }

                val transcript = try {
                    outFile.readText(Charsets.UTF_8).trim()
                } catch (e: Throwable) {
                    return@Tool errEnv("read_output_failed", "Could not read $outputTxt: ${e.message}")
                } finally {
                    // Clean up temp file
                    try { outFile.delete() } catch (_: Throwable) {}
                }

                // Parse detected language from stderr ("auto-detected language: en") if present
                val detectedLang = Regex("""auto-detected language[:\s]+([a-z]{2,8})""", RegexOption.IGNORE_CASE)
                    .find(transcribeResult.stderr + transcribeResult.stdout)
                    ?.groupValues?.getOrNull(1)
                    ?: language ?: "unknown"

                // Parse audio duration from stderr if whisper prints it ("whisper_full_with_state: ...")
                // whisper prints "whisper_full_with_state: processing <N> samples, <D.D> sec / ..."
                val durationSec = Regex("""(\d+(?:\.\d+)?)\s*sec\s*/""")
                    .find(transcribeResult.stderr + transcribeResult.stdout)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull()

                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("text", transcript)
                    put("language", detectedLang)
                    if (durationSec != null) put("audio_duration_sec", durationSec)
                    put("transcription_time_sec", transcribeTimeMs / 1000.0)
                    put("model", modelFile.name)
                }.toString()))
            }

            is CaptureResult.Timeout ->
                errEnv("timeout", "whisper-cli did not complete within 5 minutes. The audio may be very long, or the model is loading for the first time.")

            is CaptureResult.Denied ->
                errEnv("termux_permission_denied", "Termux rejected the whisper-cli command. Ensure allow-external-apps=true in ~/.termux/termux.properties.")

            is CaptureResult.OtherError ->
                errEnv("termux_error", transcribeResult.message)
        }
    }
)

/**
 * Walk [WHISPER_MODEL_SEARCH_PATHS] and return the first .bin file found, preferring
 * [PREFERRED_MODEL_NAME] (ggml-tiny.bin) when multiple models are present.
 */
private fun findWhisperModel(): File? {
    for (dirPath in WHISPER_MODEL_SEARCH_PATHS) {
        val dir = File(dirPath)
        if (!dir.isDirectory) continue
        val bins = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?: continue
        if (bins.isEmpty()) continue
        // Prefer tiny model
        return bins.firstOrNull { it.name == PREFERRED_MODEL_NAME } ?: bins.first()
    }
    return null
}

private fun errEnv(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()))

// Re-export Termux internals needed by this file (they are internal to the package,
// so no import is needed — they share the same package).
private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"
