package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
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

// Candidate locations of the whisper-cli binary, in priority order.
// Built-from-source installs (`git clone whisper.cpp && cmake -B build && cmake --build build`)
// typically place the binary in `~/whisper.cpp/build/bin/`. Older builds named the binary
// `main` instead of `whisper-cli`. Some manual installs symlink into `~/.local/bin`.
// We check explicit candidates BEFORE falling back to PATH so manual builds work even
// without symlinking into Termux's $PATH.
private val WHISPER_CLI_CANDIDATES = listOf(
    // 1. Termux pkg install (when/if it lands)
    "/data/data/com.termux/files/usr/bin/whisper-cli",
    // 2. Built-from-source — current upstream binary name (post-rename)
    "/data/data/com.termux/files/home/whisper.cpp/build/bin/whisper-cli",
    // 3. Built-from-source — legacy binary name, still used by older checkouts
    "/data/data/com.termux/files/home/whisper.cpp/build/bin/main",
    "/data/data/com.termux/files/home/whisper.cpp/main",
    // 4. User's local-bin symlink convention
    "/data/data/com.termux/files/home/.local/bin/whisper-cli",
)

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
        // Check every candidate path explicitly first, then fall back to PATH lookup.
        // We use a single shell command that prints the first executable hit so we save
        // round-trips. Format: "FOUND:<path>" or "MISSING".
        val candidatesShell = WHISPER_CLI_CANDIDATES.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val discoverScript = """
            for c in $candidatesShell ; do
                if [ -x "${'$'}c" ] ; then echo "FOUND:${'$'}c" ; exit 0 ; fi
            done
            p=${'$'}(which whisper-cli 2>/dev/null) ; if [ -n "${'$'}p" ] && [ -x "${'$'}p" ] ; then echo "FOUND:${'$'}p" ; exit 0 ; fi
            p=${'$'}(which main 2>/dev/null) ; if [ -n "${'$'}p" ] && [ -x "${'$'}p" ] ; then echo "FOUND:${'$'}p" ; exit 0 ; fi
            echo MISSING
        """.trimIndent()

        val whichResult = runCommandCapture(
            ctx = context,
            executable = "$TERMUX_BIN_DIR/bash",
            arguments = arrayOf("-c", discoverScript),
            workingDir = TERMUX_HOME_DIR,
            timeoutMs = 10_000L,
        )
        val whisperBin = when (whichResult) {
            is CaptureResult.Success -> {
                val out = whichResult.stdout.trim()
                if (out.startsWith("FOUND:")) {
                    out.removePrefix("FOUND:").trim()
                } else {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "whisper_not_installed")
                        put("detail", "whisper.cpp's whisper-cli (or 'main') was not found in PATH or in any known build location. Checked: ${WHISPER_CLI_CANDIDATES.joinToString(", ")}")
                        put("recovery", "STOP. Do NOT call termux_run_command, search_web, or any apt/pkg command from this turn. Call `whisper_status()` first — it returns the structured `install_commands` you need plus the user-facing missing_steps list. Then ask the user 'May I install whisper.cpp? It will take ~5 minutes and download ~75 MB' and wait for explicit confirmation BEFORE running anything. Never silently install.")
                    }.toString()))
                }
            }
            is CaptureResult.Timeout ->
                return@Tool errEnv("termux_timeout", "Timed out checking for whisper-cli. Ensure Termux is running and allow-external-apps=true is set.")
            is CaptureResult.Denied ->
                return@Tool errEnv("termux_permission_denied", "Termux rejected the command. Ensure allow-external-apps=true in ~/.termux/termux.properties.")
            is CaptureResult.OtherError ->
                return@Tool errEnv("termux_error", whichResult.message)
        }

        // --- 6. Locate a whisper model via Termux shell ---
        // We cannot use java.io.File for Termux paths — Termux's sandbox is owned by the
        // Termux uid, not our app uid. File.isDirectory / listFiles always return false/null
        // because the OS blocks cross-uid reads. Use a shell script instead.
        val modelPath = findWhisperModelViaShell(context)
        if (modelPath == null) {
            val searchedPaths = WHISPER_MODEL_SEARCH_PATHS.joinToString(", ")
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "whisper_model_missing")
                put("detail", "No whisper model (.bin) found in: $searchedPaths")
                put("recovery", "STOP. Call `whisper_status()` first to confirm what's missing — it returns the exact download command in `install_commands.download_tiny_model`. Then ask the user 'May I download the ~75 MB tiny model?' BEFORE running anything. Do not retry transcribe_audio_file with the same path; you'll just get this same error again.")
            }.toString()))
        }

        // --- 7. Run whisper-cli ---
        // whisper-cli writes its text output to <output_file>.txt when -otxt and -of are used.
        // We derive a temp output path inside Termux home so whisper can write to it.
        val baseName = "rikkahub_transcribe_${System.currentTimeMillis()}"
        val outputBase = "/data/data/com.termux/files/home/$baseName"
        val outputTxt = "$outputBase.txt"

        val whisperArgs = buildList {
            add("-m"); add(modelPath)
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
                    put("model", modelPath.substringAfterLast('/'))
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
 * Locate the first whisper model (.bin) across [WHISPER_MODEL_SEARCH_PATHS] by running
 * a shell script inside Termux.
 *
 * This MUST be a shell-based probe — java.io.File reads on Termux paths silently fail
 * because Termux's sandbox is owned by the Termux uid, not our app uid. The OS rejects
 * cross-uid stat() calls so File.isDirectory / listFiles always return false / null even
 * when the path exists and the file is there.
 *
 * Returns the absolute path string of the first suitable model, or null if none found
 * (or if Termux is unreachable).
 */
private suspend fun findWhisperModelViaShell(context: Context): String? {
    val pathsShell = WHISPER_MODEL_SEARCH_PATHS.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
    val script = """
        for d in $pathsShell ; do
            if [ -d "${'$'}d" ]; then
                preferred="${'$'}d/$PREFERRED_MODEL_NAME"
                if [ -f "${'$'}preferred" ]; then echo "${'$'}preferred"; exit 0; fi
                first=${'$'}(ls "${'$'}d"/*.bin 2>/dev/null | head -1)
                if [ -n "${'$'}first" ]; then echo "${'$'}first"; exit 0; fi
            fi
        done
        echo MISSING
    """.trimIndent()
    val r = runCommandCapture(
        ctx = context,
        executable = "$TERMUX_BIN_DIR/bash",
        arguments = arrayOf("-c", script),
        workingDir = TERMUX_HOME_DIR,
        timeoutMs = 5_000L,
    )
    return when (r) {
        is CaptureResult.Success -> {
            val out = r.stdout.trim()
            if (out == "MISSING" || out.isEmpty()) null else out
        }
        else -> null
    }
}

/**
 * Diagnostic pre-flight tool that tells the LLM exactly what's set up for whisper
 * transcription BEFORE it tries to transcribe anything. No approval needed — pure
 * read-only probe.
 *
 * The LLM should call this FIRST when the user sends an audio file or asks for
 * transcription.
 */
fun whisperStatusTool(context: Context, settingsStore: SettingsStore): Tool = Tool(
    name = "whisper_status",
    description = """
        Check whether whisper.cpp transcription is ready to use. Returns a structured
        report: whether Termux is enabled in this assistant, whether the Termux app is
        installed and reachable, whether whisper-cli is on disk, and whether a model
        (.bin) file is present. Also returns install commands for anything missing.
        Call this BEFORE calling transcribe_audio_file, especially when the user sends
        an audio or voice note. Takes no parameters.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    needsApproval = false,
    execute = { _ ->
        val missingSteps = mutableListOf<String>()

        // 1. Is Termux enabled in this assistant's local tools?
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        val termuxEnabledInAssistant = assistant.localTools.contains(LocalToolOption.Termux)
        if (!termuxEnabledInAssistant) missingSteps.add("enable_termux_toggle")

        // 2. Is Termux app installed and permission granted?
        val termuxState = TermuxIntegration.state(context)
        val termuxAppInstalled = termuxState != TermuxIntegration.State.NOT_INSTALLED
        if (!termuxAppInstalled) missingSteps.add("install_termux")

        // 3. Probe whisper-cli via shell (only if Termux is reachable)
        var whisperCliInstalled = false
        var whisperCliPath: String? = null
        var modelPresent = false
        var modelPath: String? = null

        if (termuxState == TermuxIntegration.State.READY) {
            // Probe whisper-cli
            val candidatesShell = WHISPER_CLI_CANDIDATES.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
            val cliScript = """
                for c in $candidatesShell ; do
                    if [ -x "${'$'}c" ] ; then echo "FOUND:${'$'}c" ; exit 0 ; fi
                done
                p=${'$'}(which whisper-cli 2>/dev/null) ; if [ -n "${'$'}p" ] && [ -x "${'$'}p" ] ; then echo "FOUND:${'$'}p" ; exit 0 ; fi
                p=${'$'}(which main 2>/dev/null) ; if [ -n "${'$'}p" ] && [ -x "${'$'}p" ] ; then echo "FOUND:${'$'}p" ; exit 0 ; fi
                echo MISSING
            """.trimIndent()
            val cliResult = runCommandCapture(
                ctx = context,
                executable = "$TERMUX_BIN_DIR/bash",
                arguments = arrayOf("-c", cliScript),
                workingDir = TERMUX_HOME_DIR,
                timeoutMs = 8_000L,
            )
            if (cliResult is CaptureResult.Success) {
                val out = cliResult.stdout.trim()
                if (out.startsWith("FOUND:")) {
                    whisperCliInstalled = true
                    whisperCliPath = out.removePrefix("FOUND:").trim()
                }
            }
            if (!whisperCliInstalled) missingSteps.add("install_whisper")

            // Probe model
            val foundModel = findWhisperModelViaShell(context)
            if (foundModel != null) {
                modelPresent = true
                modelPath = foundModel
            } else {
                missingSteps.add("download_model")
            }
        } else {
            // Termux not reachable — add whisper + model steps too so the list is complete
            missingSteps.add("install_whisper")
            missingSteps.add("download_model")
        }

        val readyToTranscribe = termuxEnabledInAssistant && termuxAppInstalled && whisperCliInstalled && modelPresent

        listOf(UIMessagePart.Text(buildJsonObject {
            put("termux_enabled_in_assistant", termuxEnabledInAssistant)
            put("termux_app_installed", termuxAppInstalled)
            put("whisper_cli_installed", whisperCliInstalled)
            if (whisperCliPath != null) put("whisper_cli_path", whisperCliPath) else put("whisper_cli_path", kotlinx.serialization.json.JsonNull)
            put("model_present", modelPresent)
            if (modelPath != null) put("model_path", modelPath) else put("model_path", kotlinx.serialization.json.JsonNull)
            put("ready_to_transcribe", readyToTranscribe)
            put("missing_steps", buildJsonArray { missingSteps.forEach { add(it) } })
            put("install_commands", buildJsonObject {
                put("build_whisper_from_source", "cd ~ && pkg install -y git cmake clang make && git clone https://github.com/ggerganov/whisper.cpp && cd whisper.cpp && cmake -B build && cmake --build build -j --config Release")
                put("download_tiny_model", "mkdir -p ~/.cache/whisper-models && cd ~/.cache/whisper-models && wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin")
            })
        }.toString()))
    }
)

private fun errEnv(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()))

// Re-export Termux internals needed by this file (they are internal to the package,
// so no import is needed — they share the same package).
private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"
