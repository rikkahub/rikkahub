package me.rerere.rikkahub.data.ai.tools.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"

/**
 * Run a shell command inside Termux without typing into its terminal. Termux exposes a
 * RunCommandService that accepts a structured intent and executes the command in either a
 * new session (visible in Termux's UI) or in the background.
 *
 * **Setup the user must do once:**
 * 1. Open Termux.
 * 2. Edit `~/.termux/termux.properties` (create if missing).
 * 3. Add the line `allow-external-apps=true` and save.
 * 4. Restart Termux (kill + reopen) so the property reloads.
 *
 * Without that flag Termux silently drops the intent. We surface a `recovery` hint when the
 * dispatch returns nothing, since a successful intent from Termux's side does not return any
 * response to us via the broadcast.
 */
fun termuxRunCommandTool(context: Context): Tool = Tool(
    name = "termux_run_command",
    description = """
        Execute a shell command in Termux without typing. Provide either a single command
        string or argv-style args. By default runs in a new visible Termux session so the
        user sees output; pass background=true for fire-and-forget. Termux must have
        allow-external-apps=true set in ~/.termux/termux.properties (one-time setup).
        Returns success on intent dispatch; the actual command output is shown inside
        Termux. Cannot read the command's stdout back.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command line, e.g. 'pkg update && pkg upgrade -y'. Mutually exclusive with executable+arguments.")
                })
                put("executable", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to executable, e.g. /data/data/com.termux/files/usr/bin/bash. Pairs with arguments[].")
                })
                put("arguments", buildJsonObject {
                    put("type", "array")
                    put("description", "Argument list when using executable mode")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("working_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory. Defaults to Termux home.")
                })
                put("background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, run silently with no Termux UI session; if false (default), open a new visible session.")
                })
                put("session_action", buildJsonObject {
                    put("type", "integer")
                    put("description", "0 (default) opens a new session and brings Termux to foreground; 1 opens session without focus; 2 reuses an existing session.")
                })
            }
        )
    },
    execute = { input ->
        val rawCommand = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        val executable = input.jsonObject["executable"]?.jsonPrimitive?.contentOrNull
        val argumentsArr = input.jsonObject["arguments"]?.jsonArray
        val workingDir = input.jsonObject["working_dir"]?.jsonPrimitive?.contentOrNull
            ?: TERMUX_HOME_DIR
        val background = input.jsonObject["background"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val sessionAction = input.jsonObject["session_action"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull() ?: 0

        if (rawCommand.isNullOrBlank() && executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "either 'command' or 'executable' is required")
                    }.toString()
                )
            )
        }
        if (!rawCommand.isNullOrBlank() && !executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "command and executable are mutually exclusive")
                    }.toString()
                )
            )
        }

        // Resolve to executable + arguments.
        val (resolvedExe, resolvedArgs) = if (rawCommand != null) {
            "$TERMUX_BIN_DIR/bash" to arrayOf("-c", rawCommand)
        } else {
            val args = argumentsArr?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toTypedArray()
                ?: emptyArray()
            executable!! to args
        }

        // Confirm Termux is installed before bothering to dispatch the intent.
        val pm = context.packageManager
        val termuxInstalled = try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Throwable) { false }
        if (!termuxInstalled) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "termux_not_installed")
                        put("recovery", "Install Termux from F-Droid (https://f-droid.org/packages/com.termux). Play Store version is unmaintained.")
                    }.toString()
                )
            )
        }

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            action = TERMUX_RUN_COMMAND_ACTION
            putExtra("com.termux.RUN_COMMAND_PATH", resolvedExe)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", resolvedArgs)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", sessionAction.toString())
        }

        try {
            context.startService(intent)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("dispatched", true)
                        put("note", "If nothing happens in Termux, ensure ~/.termux/termux.properties has allow-external-apps=true and Termux has been restarted since the change.")
                        put("executable", resolvedExe)
                        if (resolvedArgs.isNotEmpty()) {
                            put("arguments", buildJsonArray { resolvedArgs.forEach { add(it) } })
                        }
                        put("working_dir", workingDir)
                        put("background", background)
                    }.toString()
                )
            )
        } catch (t: SecurityException) {
            // Two distinct failure modes both surface as SecurityException:
            //   1. Our app's manifest is missing com.termux.permission.RUN_COMMAND (rare;
            //      shipped manifest declares it now).
            //   2. The user has not enabled allow-external-apps in Termux's properties
            //      (most common; user-side fix).
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "termux_permission_denied")
                        put("recovery", "Open Termux, then run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux from app info and reopen it. Then retry.")
                        put("reason", t.message ?: "SecurityException")
                    }.toString()
                )
            )
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "dispatch_failed")
                        put("reason", t.message ?: t::class.java.simpleName)
                    }.toString()
                )
            )
        }
    }
)
