package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The "hardline" tier — unconditional command blocklist.
 *
 * Patterns here are blocked BEFORE any approval prompt and BEFORE any allow-list check.
 * Even if the user has granted "Always Allow" for the parent tool (e.g. termux_run_command),
 * a hardline-matching command never runs. There is no way to override hardline through the
 * UI — that's the whole point. If the user genuinely needs to run something on this list,
 * they should run it themselves in a terminal, not through the agent.
 *
 * The list is deliberately tiny: only commands with no recovery path.
 *   - filesystem destruction rooted at /, $HOME, /etc, /usr, /var, etc.
 *   - raw block device overwrites (dd of=/dev/sd*, > /dev/sd*)
 *   - kernel shutdown / reboot / halt
 *   - fork bomb
 *   - kill -1 / pkill the world
 *
 * Recoverable-but-costly things (git reset --hard, rm -rf /tmp/x, chmod -R 777, curl|sh)
 * stay in the regular approval-required flow — the user can choose to allow them.
 *
 * Inspired by the same shape Hermes Agent uses for its hardline floor.
 */
object HardlineCommandGuard {

    /**
     * Match a regex anchored to "command position" — start of string, after `;` `&` `|`
     * newline or backtick, after `$(`, optionally after `sudo` / `env VAR=val` / wrappers
     * like `exec`, `nohup`, `setsid`, `time`. Without this, "echo reboot" would match the
     * shutdown rule.
     */
    private const val CMD_POS =
        "(?:^|[;&|\\n`]|\\\$\\()" +
        "\\s*" +
        "(?:sudo\\s+(?:-[^\\s]+\\s+)*)?" +
        "(?:env\\s+(?:\\w+=\\S*\\s+)*)?" +
        "(?:(?:exec|nohup|setsid|time)\\s+)*" +
        "\\s*" +
        // Optional absolute-path prefix so /usr/sbin/shutdown matches the same way bare
        // 'shutdown' does. The greedy `[\w./_-]*/` consumes everything up to the last
        // slash so the trailing command name is what's matched by the (cmd1|cmd2) group.
        "(?:[\\w./_-]*/)?"

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    /** (regex, human-readable reason) pairs. Reason is surfaced in the block envelope. */
    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // rm -rf the root filesystem, system dirs, or HOME
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(/|/\\*|/\\s*\\*)(\\s|\$)", IGNORE_CASE) to
            "recursive delete of root filesystem",
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(/home|/root|/etc|/usr|/var|/bin|/sbin|/boot|/lib)(/\\*?)?(\\s|\$)", IGNORE_CASE) to
            "recursive delete of system directory",
        // ~ OR \$HOME OR \${HOME} — IGNORE_CASE handles \$home etc.
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(~|\\\$HOME|\\\$\\{HOME\\})(/?|/\\*)?(\\s|\$)", IGNORE_CASE) to
            "recursive delete of home directory",
        // Format filesystem — anchored at command position so "grep mkfs /var/log" doesn't trip.
        Regex(CMD_POS + "mkfs(\\.[a-z0-9]+)?\\b", IGNORE_CASE) to "format filesystem (mkfs)",
        // Raw block device writes
        Regex("\\bdd\\b[^\\n]*\\bof=/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*", IGNORE_CASE) to
            "dd to raw block device",
        Regex(">\\s*/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*\\b", IGNORE_CASE) to
            "redirect to raw block device",
        // Fork bomb
        Regex(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:") to "fork bomb",
        // Kill every process
        Regex("\\bkill\\s+(-[^\\s]+\\s+)*-1\\b", IGNORE_CASE) to "kill all processes",
        // System shutdown / reboot — only at command position so "grep shutdown logs" doesn't trip
        Regex(CMD_POS + "(shutdown|reboot|halt|poweroff)\\b", IGNORE_CASE) to "system shutdown/reboot",
        Regex(CMD_POS + "init\\s+[06]\\b", IGNORE_CASE) to "init 0/6 (shutdown/reboot)",
        Regex(CMD_POS + "systemctl\\s+(poweroff|reboot|halt|kexec)\\b", IGNORE_CASE) to "systemctl poweroff/reboot",
        Regex(CMD_POS + "telinit\\s+[06]\\b", IGNORE_CASE) to "telinit 0/6 (shutdown/reboot)",
    )

    /**
     * Check whether a raw command string matches any hardline pattern.
     * Returns the human-readable reason if blocked, or null if safe.
     * Case-insensitive; the input is lowercased before matching.
     */
    fun checkCommand(command: String?): String? {
        if (command.isNullOrEmpty()) return null
        val normalised = command.lowercase()
        for ((pattern, reason) in PATTERNS) {
            if (pattern.containsMatchIn(normalised)) return reason
        }
        return null
    }

    /**
     * Tool-aware entrypoint: pull whichever arg of [toolName] carries shell content and
     * run it through [checkCommand]. Returns the block reason or null. Tools that don't
     * carry shell content return null trivially (caller's hardline check just no-ops).
     */
    fun checkTool(toolName: String, inputJson: String): String? {
        if (inputJson.isBlank()) return null
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(inputJson).jsonObject
        }.getOrNull() ?: return null

        return when (toolName) {
            "termux_run_command" -> {
                // Two arg shapes: a `command` string, OR an `executable` + `arguments` array.
                // Check both: a hardline rm in either form must be blocked.
                val cmd = obj["command"]?.jsonPrimitive?.contentOrNull
                checkCommand(cmd)?.let { return it }
                val exe = obj["executable"]?.jsonPrimitive?.contentOrNull
                val args = obj["arguments"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.joinToString(" ")
                if (exe != null || args != null) {
                    checkCommand("${exe.orEmpty()} ${args.orEmpty()}")
                } else null
            }
            "ssh_exec", "ssh_exec_saved" -> {
                checkCommand(obj["command"]?.jsonPrimitive?.contentOrNull)
            }
            "eval_javascript" -> {
                // No shell to match; fall through. (We could add JS hardline patterns here
                // later — wiping localStorage, requiring a sketchy URL, etc.)
                null
            }
            else -> null
        }
    }

    /**
     * Convenience for callers that already have a parsed JsonObject (e.g. tool execution
     * paths). Mirrors [checkTool] but skips the parse step.
     */
    fun checkToolParsed(toolName: String, input: JsonObject): String? {
        return when (toolName) {
            "termux_run_command" -> {
                val cmd = input["command"]?.jsonPrimitive?.contentOrNull
                checkCommand(cmd)?.let { return it }
                val exe = input["executable"]?.jsonPrimitive?.contentOrNull
                val args = input["arguments"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.joinToString(" ")
                if (exe != null || args != null) {
                    checkCommand("${exe.orEmpty()} ${args.orEmpty()}")
                } else null
            }
            "ssh_exec", "ssh_exec_saved" ->
                checkCommand(input["command"]?.jsonPrimitive?.contentOrNull)
            else -> null
        }
    }
}
