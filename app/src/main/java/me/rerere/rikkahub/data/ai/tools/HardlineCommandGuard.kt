package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *   - filesystem destruction rooted at /, $HOME, /etc, /usr, /var, etc. (incl. descendants)
 *   - raw block device overwrites (dd of=/dev/sd*, > /dev/sd*)
 *   - kernel shutdown / reboot / halt
 *   - fork bomb
 *   - kill -1 / pkill the world
 *   - encoded-payload-piped-to-shell (base64 -d | sh, printf '\xNN' | sh, eval $(…))
 *
 * Threat model: this is the lazy-mistake floor, NOT an adversarial-payload floor.
 * Variable indirection (`c=rm; \$c -rf /`), heredocs, parameter expansion tricks, and
 * obfuscation chains aren't catchable with regex and are out of scope. If the model is
 * actively trying to circumvent the user, the protection is per-tool approval and the
 * user noticing the prompt — not a regex floor.
 *
 * Recoverable-but-costly things (git reset --hard, rm -rf /tmp/x, chmod -R 777)
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
     *
     * Includes the SHELL_EVAL alternation so commands smuggled inside `bash -c "…"` /
     * `sh -c '…'` are also caught. Without this, the model could trivially escape the
     * shutdown / reboot / mkfs rules by wrapping them in a shell-eval invocation.
     */
    private const val CMD_POS_BARE = "(?:^|[;&|\\n`]|\\\$\\()"
    // `busybox sh -c "…"` is a common Android invocation form, hence the optional
    // busybox prefix. `ash` is BusyBox's default shell.
    private const val SHELL_EVAL_OPEN =
        "\\b(?:busybox\\s+)?(?:bash|sh|zsh|dash|ksh|csh|tcsh|ash)\\s+(?:-[a-zA-Z]+\\s+)*-c\\s+[\"']"

    private const val CMD_POS =
        "(?:(?:" + CMD_POS_BARE + ")|(?:" + SHELL_EVAL_OPEN + "))" +
        "\\s*" +
        "(?:sudo\\s+(?:-[^\\s]+\\s+)*)?" +
        "(?:env\\s+(?:\\w+=\\S*\\s+)*)?" +
        "(?:(?:exec|nohup|setsid|time)\\s+)*" +
        "\\s*" +
        // Optional absolute-path prefix so /usr/sbin/shutdown matches the same way bare
        // 'shutdown' does. The greedy `[\w./_-]*/` consumes everything up to the last
        // slash so the trailing command name is what's matched by the (cmd1|cmd2) group.
        "(?:[\\w./_-]*/)?"

    /** End-of-path anchor — whitespace, end-of-string, or shell-eval closing quote. */
    private const val PATH_END = "(?:\\s|[\"']|\$)"

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    /** (regex, human-readable reason) pairs. Reason is surfaced in the block envelope. */
    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // rm -rf the root filesystem, system dirs (incl. descendants), or HOME
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(/|/\\*|/\\s*\\*)" + PATH_END, IGNORE_CASE) to
            "recursive delete of root filesystem",
        // /home and /root: only the dir itself, not descendants — users have legitimate
        // reasons to nuke a folder under /home/user, but never to nuke /home as a whole.
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(/home|/root)(/\\*?)?" + PATH_END, IGNORE_CASE) to
            "recursive delete of home root",
        // /etc, /usr, /var, /bin, /sbin, /boot, /lib: descendants too. `rm -rf /etc/passwd`
        // is just as fatal as `rm -rf /etc` and there's no legitimate reason to drive
        // either through an LLM agent — if you actually need to do this, use a terminal.
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(/etc|/usr|/var|/bin|/sbin|/boot|/lib)(/\\S*)?" + PATH_END, IGNORE_CASE) to
            "recursive delete of system directory",
        // ~ OR \$HOME OR \${HOME} — IGNORE_CASE handles \$home etc.
        Regex("\\brm\\s+(-[^\\s]*\\s+)*(~|\\\$HOME|\\\$\\{HOME\\})(/?|/\\*)?" + PATH_END, IGNORE_CASE) to
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
        // System shutdown / reboot — at command position OR inside a shell-eval `-c "…"`
        Regex(CMD_POS + "(shutdown|reboot|halt|poweroff)\\b", IGNORE_CASE) to "system shutdown/reboot",
        Regex(CMD_POS + "init\\s+[06]\\b", IGNORE_CASE) to "init 0/6 (shutdown/reboot)",
        Regex(CMD_POS + "systemctl\\s+(poweroff|reboot|halt|kexec)\\b", IGNORE_CASE) to "systemctl poweroff/reboot",
        Regex(CMD_POS + "telinit\\s+[06]\\b", IGNORE_CASE) to "telinit 0/6 (shutdown/reboot)",
        // Encoded-payload pipe-to-shell — `base64 -d` / `xxd -r` / hex `printf` whose output
        // is piped straight into a shell. This is how regex-floor circumvention is actually
        // attempted in practice ("echo cm0gLXJmIC8= | base64 -d | sh"). We can't decode the
        // payload, but we can refuse to evaluate the result of a decoder.
        Regex("\\b(base64|xxd)\\s+(?:-[^\\s]*\\s+)*-[dr]\\b[^|]*\\|\\s*(?:eval\\b|(?:bash|sh|zsh|dash|ksh|csh)\\b)", IGNORE_CASE) to
            "encoded payload piped to shell",
        Regex("\\bprintf\\s+[\"'][^\"']*\\\\x[^\"']*[\"'][^|]*\\|\\s*(?:eval\\b|(?:bash|sh|zsh|dash|ksh|csh)\\b)", IGNORE_CASE) to
            "hex-encoded payload piped to shell",
        // `eval \$(…)` — running the output of an arbitrary subshell as code. The subshell
        // body itself can be anything, by definition opaque to a regex floor.
        Regex("\\beval\\s+\\\$\\(", IGNORE_CASE) to
            "eval of subshell command substitution",
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
     *
     * MCP tools (any name starting with `mcp__`) are checked by walking every string
     * value in the input JSON — we don't know which MCP server's tool we're dealing
     * with or what its arg shape is, but if any string arg matches a hardline pattern
     * the call is refused. This is the floor; a clueful MCP server should also gate
     * its own destructive tools with its own approval.
     */
    fun checkTool(toolName: String, inputJson: String): String? {
        if (inputJson.isBlank()) return null
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(inputJson).jsonObject
        }.getOrNull() ?: return null
        return checkToolParsed(toolName, obj)
    }

    /**
     * Convenience for callers that already have a parsed JsonObject (e.g. tool execution
     * paths). Mirrors [checkTool] but skips the parse step.
     */
    fun checkToolParsed(toolName: String, input: JsonObject): String? {
        return when {
            toolName == "termux_run_command" -> {
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
            toolName == "ssh_exec" || toolName == "ssh_exec_saved" ->
                checkCommand(input["command"]?.jsonPrimitive?.contentOrNull)
            // Sub-agent dispatch — the spawned LLM gets the parent's full tool surface
            // headlessly, so a `task` / `prompt` containing a literal hardline-blocked
            // command (e.g. `rm -rf /`) shouldn't be authorised even if the parent
            // workflow_create approval was granted. Walk all string args.
            toolName == "subagent_dispatch" -> walkAndCheck(input)
            // MCP-relayed tools: we don't know which server or what arg shape carries shell
            // content, so we recursively scan every string value in the input. False
            // positives are fine — an MCP tool whose legitimate-purpose arg happens to
            // contain a literal `rm -rf /` is too edge-case to design around.
            toolName.startsWith("mcp__") -> walkAndCheck(input)
            // browser_eval_js: runs arbitrary JS in a real WebView with cookies, localStorage,
            // and fetch — completely unlike eval_javascript (sandboxed QuickJS). This is the
            // single highest-trust tool we ship, hence the dedicated HARDLINE arm + the
            // NO_ALWAYS_ALLOW flag in ToolApprovalDefaults. Two checks:
            //   (a) the model could try to chain an OS shell via fetch('http://localhost') /
            //       sendBeacon — defence in depth, reuse the existing shell deny list.
            //   (b) JS-specific exfiltration / privilege patterns (cookie writes, eval,
            //       Function constructor, string-form setTimeout, data: script injection).
            toolName == "browser_eval_js" -> {
                val code = input["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                checkCommand(code)?.let { return it }
                val matched = JS_HARDLINE_PATTERNS.firstOrNull { (rx, _) -> rx.containsMatchIn(code) }
                matched?.second
            }
            // eval_javascript: no shell to match. JS-side hardline patterns are out of
            // scope because QuickJS in this repo has no DOM / Node / fetch — realistic
            // blast radius is bounded to local CPU. Add JS rules here when the surface
            // grows.
            else -> null
        }
    }

    /**
     * JS-specific hardline patterns for [BrowserToolDefaults.EVAL_JS]. Defence-in-depth
     * against the most common exfiltration / RCE shapes. Conservative — false positives
     * are fine; a model that legitimately needs to write document.cookie can use
     * browser_type on a form field instead.
     */
    private val JS_HARDLINE_PATTERNS: List<Pair<Regex, String>> = listOf(
        // Direct cookie writes. Reads (`document.cookie` on the right of an expression) are
        // not blocked because they're useful for pages that pass auth via cookies.
        Regex("""document\s*\.\s*cookie\s*=""", IGNORE_CASE) to "hardline:js_cookie_write",
        // Dynamic-eval surfaces. eval("...") and new Function("...") let the model construct
        // a payload at fire time that the static guard can't see ahead of time.
        Regex("""\beval\s*\(""", IGNORE_CASE) to "hardline:js_eval",
        Regex("""\bnew\s+Function\s*\(""", IGNORE_CASE) to "hardline:js_function_constructor",
        // setTimeout / setInterval with a STRING first argument is a stealth-eval — the
        // browser parses the string as code at trigger time. Function-literal callbacks
        // (`setTimeout(()=>foo(), 1000)`) are fine and not matched here.
        Regex("""\bsetTimeout\s*\(\s*['"`]""", IGNORE_CASE) to "hardline:js_settimeout_string",
        Regex("""\bsetInterval\s*\(\s*['"`]""", IGNORE_CASE) to "hardline:js_setinterval_string",
        // <script src="data:..."> — data-URI script injection, the canonical SOP-bypass
        // payload. Same pattern works inside template strings inserted via innerHTML.
        Regex("""<script\s+src\s*=\s*['"]?data:""", IGNORE_CASE) to "hardline:js_script_data_uri",
    )

    /**
     * Walk a JSON element recursively. Run [checkCommand] on every string primitive we
     * find. Return the first match's reason, or null if everything is clean.
     */
    private fun walkAndCheck(element: JsonElement): String? {
        when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    return checkCommand(element.contentOrNull)
                }
            }
            is JsonObject -> {
                for ((_, v) in element) {
                    val r = walkAndCheck(v)
                    if (r != null) return r
                }
            }
            is JsonArray -> {
                for (v in element) {
                    val r = walkAndCheck(v)
                    if (r != null) return r
                }
            }
        }
        return null
    }
}
