package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import me.rerere.rikkahub.data.datastore.SettingsStore

class TermuxPtySessionManager(
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    private val termuxCommandManager: TermuxCommandManager,
    private val settingsStore: SettingsStore,
) {
    private val ensureServerMutex = Mutex()
    @Volatile
    private var serverToken: String? = null
    @Volatile
    private var serverPort: Int? = null

    suspend fun startSession(
        command: String,
        workdir: String,
        yieldTimeMs: Long,
        maxOutputChars: Int,
        cols: Int = TERMUX_PTY_DEFAULT_COLUMNS,
        rows: Int = TERMUX_PTY_DEFAULT_ROWS,
    ): TermuxPtyServerResponse {
        val port = ensureServerRunning()
        val response = postJson(
            port = port,
            path = "/sessions",
            payload = TermuxPtyStartRequest(
                command = command,
                workdir = workdir,
                yieldTimeMs = yieldTimeMs.coerceAtLeast(0L),
                maxOutputChars = maxOutputChars.coerceAtLeast(256),
                cols = cols.coerceAtLeast(20),
                rows = rows.coerceAtLeast(5),
            )
        )
        return response
    }

    suspend fun writeStdin(
        sessionId: String,
        chars: String,
        yieldTimeMs: Long,
        maxOutputChars: Int,
    ): TermuxPtyServerResponse {
        val port = ensureServerRunning()
        val response = postJson(
            port = port,
            path = "/sessions/$sessionId/stdin",
            payload = TermuxPtyWriteRequest(
                chars = chars,
                yieldTimeMs = yieldTimeMs.coerceAtLeast(0L),
                maxOutputChars = maxOutputChars.coerceAtLeast(256),
            )
        )
        return response
    }

    suspend fun listSessions(): TermuxPtySessionListResponse {
        val port = currentPort()
        val token = resolveServerToken(port)
        if (token == null) {
            val running = isServerProcessRunning(port)
            return TermuxPtySessionListResponse(
                running = running,
                error = if (running) {
                    "PTY session server is running, but its auth token could not be recovered yet."
                } else {
                    null
                },
            )
        }
        return runCatching {
            getJson<TermuxPtySessionListResponse>(
                port = port,
                token = token,
                path = "/sessions",
            )
        }.getOrElse { e ->
            val running = isServerProcessRunning(port)
            TermuxPtySessionListResponse(
                running = running,
                error = e.message ?: e.javaClass.name,
            )
        }
    }

    suspend fun closeSession(sessionId: String): TermuxPtyActionResponse {
        val port = currentPort()
        val token = resolveServerToken(port) ?: return TermuxPtyActionResponse(
            success = false,
            running = false,
            error = "PTY session server is not running.",
        )
        val response = runCatching {
            deleteJson<TermuxPtyActionResponse>(
                port = port,
                token = token,
                path = "/sessions/$sessionId",
            )
        }.getOrElse { e ->
            TermuxPtyActionResponse(
                success = false,
                running = false,
                error = e.message ?: e.javaClass.name,
            )
        }
        return response
    }

    suspend fun closeSessions(sessionIds: Collection<String>) {
        sessionIds.toSet().forEach { sessionId ->
            runCatching { closeSession(sessionId) }
        }
    }

    suspend fun closeAllSessions(): TermuxPtyActionResponse {
        val port = currentPort()
        val token = resolveServerToken(port) ?: return TermuxPtyActionResponse(
            success = false,
            running = false,
            error = "PTY session server is not running.",
        )
        val response = runCatching {
            deleteJson<TermuxPtyActionResponse>(
                port = port,
                token = token,
                path = "/sessions",
            )
        }.getOrElse { e ->
            TermuxPtyActionResponse(
                success = false,
                running = false,
                error = e.message ?: e.javaClass.name,
            )
        }
        return response
    }

    suspend fun stopServer() {
        runCatching {
            termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf("-lc", buildStopServerScript()),
                    workdir = TERMUX_HOME_PATH,
                    background = false,
                    timeoutMs = 10_000L,
                    label = "RikkaHub stop PTY session server",
                    description = "Stop interactive shell session server",
                    trackLifecycle = false,
                )
            )
        }
        serverToken = null
        serverPort = null
    }

    private suspend fun ensureServerRunning(): Int {
        ensureServerMutex.withLock {
            val port = currentPort()
            val existingToken = resolveServerToken(port)
            if (existingToken != null) return port

            val token = UUID.randomUUID().toString()
            val result = termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    workdir = TERMUX_HOME_PATH,
                    stdin = buildBootstrapScript(port = port, token = token),
                    background = true,
                    timeoutMs = 20_000L,
                    label = "RikkaHub PTY session server",
                    description = "Start interactive shell session server",
                    trackLifecycle = false,
                )
            )

            if (result.exitCode != 0 || result.hasInternalError() || result.timedOut) {
                val message = TermuxOutputFormatter.merge(
                    stdout = result.stdout,
                    stderr = result.stderr,
                    errMsg = result.errMsg,
                ).ifBlank {
                    buildString {
                        if (result.timedOut) append("Timed out while starting PTY session server.")
                        result.exitCode?.let {
                            if (isNotBlank()) append('\n')
                            append("Exit code: $it")
                        }
                        result.errCode?.takeIf { result.hasInternalError() }?.let {
                            if (isNotBlank()) append('\n')
                            append("Err code: $it")
                        }
                    }
                }
                error(message.ifBlank { "Failed to start PTY session server in Termux." })
            }

            if (!ping(port = port, token = token)) {
                val output = TermuxOutputFormatter.merge(
                    stdout = result.stdout,
                    stderr = result.stderr,
                    errMsg = result.errMsg,
                )
                error(
                    listOf(
                        "PTY session server did not become ready.",
                        output.takeIf { it.isNotBlank() },
                    ).joinToString(separator = "\n")
                )
            }
            serverToken = token
            serverPort = port
            return port
        }
    }

    private suspend fun resolveServerToken(port: Int): String? {
        val existingToken = serverToken
        if (existingToken != null && serverPort == port && ping(port = port, token = existingToken)) {
            return existingToken
        }

        val recoveredToken = recoverPersistedToken()?.takeIf { ping(port = port, token = it) } ?: return null
        serverToken = recoveredToken
        serverPort = port
        return recoveredToken
    }

    private suspend fun recoverPersistedToken(): String? {
        val result = runCatching {
            termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf("-lc", buildRecoverTokenScript()),
                    workdir = TERMUX_HOME_PATH,
                    background = false,
                    timeoutMs = 5_000L,
                    label = "RikkaHub inspect PTY session server",
                    description = "Read interactive shell session server token",
                    trackLifecycle = false,
                )
            )
        }.getOrNull() ?: return null
        if (!result.isSuccessful()) return null
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private suspend fun isServerProcessRunning(port: Int): Boolean {
        val result = runCatching {
            termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf("-lc", buildProbeServerScript(port)),
                    workdir = TERMUX_HOME_PATH,
                    background = false,
                    timeoutMs = 5_000L,
                    label = "RikkaHub inspect PTY session server process",
                    description = "Check interactive shell session server process",
                    trackLifecycle = false,
                )
            )
        }.getOrNull() ?: return false
        if (!result.isSuccessful()) return false
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } == "running"
    }

    private suspend fun ping(
        port: Int,
        token: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(baseUrl(port = port, path = "/health"))
                    .header(TOKEN_HEADER, token)
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            }.getOrDefault(false)
        }
    }

    private suspend inline fun <reified T : Any> postJson(
        port: Int,
        path: String,
        payload: T,
    ): TermuxPtyServerResponse {
        val token = serverToken ?: error("PTY session server token is missing")
        return postJson(
            port = port,
            token = token,
            path = path,
            payload = payload,
        )
    }

    private suspend inline fun <reified T : Any> postJson(
        port: Int,
        token: String,
        path: String,
        payload: T,
    ): TermuxPtyServerResponse {
        return withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(payload)
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(baseUrl(port = port, path = path))
                .header(TOKEN_HEADER, token)
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                decodeResponse(response = response, path = path)
            }
        }
    }

    private suspend inline fun <reified T : Any> getJson(
        port: Int,
        token: String,
        path: String,
    ): T {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl(port = port, path = path))
                .header(TOKEN_HEADER, token)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                decodeResponse(response = response, path = path)
            }
        }
    }

    private suspend inline fun <reified T : Any> deleteJson(
        port: Int,
        token: String,
        path: String,
    ): T {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl(port = port, path = path))
                .header(TOKEN_HEADER, token)
                .delete()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                decodeResponse(response = response, path = path)
            }
        }
    }

    private inline fun <reified T : Any> decodeResponse(
        response: okhttp3.Response,
        path: String,
    ): T {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val parsed = body.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<TermuxPtyServerResponse>(it) }.getOrNull()
            }
            throw IOException(
                parsed?.error ?: "PTY server request failed for $path with HTTP ${response.code}"
            )
        }
        return json.decodeFromString(body)
    }

    private fun currentPort(): Int {
        return settingsStore.settingsFlow.value.termuxPtyServerPort
    }

    private fun baseUrl(
        port: Int,
        path: String,
    ): String {
        return "http://127.0.0.1:$port$path"
    }

    private fun buildBootstrapScript(
        port: Int,
        token: String,
    ): String {
        val safeToken = token.replace("'", "'\"'\"'")
        return buildString {
            appendLine("set -eu")
            appendLine()
            appendLine("STATE_DIR=\"${'$'}HOME/.rikkahub\"")
            appendLine("SCRIPT_PATH=\"${'$'}STATE_DIR/pty_session_server.py\"")
            appendLine("PID_FILE=\"${'$'}STATE_DIR/pty_session_server.pid\"")
            appendLine("TOKEN_FILE=\"${'$'}STATE_DIR/pty_session_server.token\"")
            appendLine("LOG_FILE=\"${'$'}STATE_DIR/pty_session_server.log\"")
            appendLine("PORT=$port")
            appendLine("TOKEN='$safeToken'")
            appendLine()
            appendLine("mkdir -p \"${'$'}STATE_DIR\"")
            appendLine()
            appendLine("if ! command -v python3 >/dev/null 2>&1; then")
            appendLine("  echo \"python3 not found. In Termux: pkg install python\"")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine()
            appendLine("is_pty_server_cmdline() {")
            appendLine("  _cmdline=\"${'$'}1\"")
            appendLine("  _script=\"${'$'}2\"")
            appendLine("  _port=\"${'$'}3\"")
            appendLine("  _padded=\" ${'$'}{_cmdline} \"")
            appendLine("  case \"${'$'}_padded\" in")
            appendLine("    *\" python3 -u ${'$'}_script --port ${'$'}_port --token \"*|*\" python3 -u ${'$'}_script --port ${'$'}_port \"*\" --token \"*)")
            appendLine("      return 0")
            appendLine("      ;;")
            appendLine("    *)")
            appendLine("      return 1")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("}")
            appendLine()
            appendLine("is_pty_server_pid() {")
            appendLine("  _pid=\"${'$'}1\"")
            appendLine("  _token=\"${'$'}2\"")
            appendLine("  [ -n \"${'$'}_pid\" ] || return 1")
            appendLine("  [ -r \"/proc/${'$'}_pid/cmdline\" ] || return 1")
            appendLine("  _cmdline=\"${'$'}(cat \"/proc/${'$'}_pid/cmdline\" 2>/dev/null | tr '\\0' ' ')\"")
            appendLine("  [ -n \"${'$'}_cmdline\" ] || return 1")
            appendLine("  is_pty_server_cmdline \"${'$'}_cmdline\" \"${'$'}SCRIPT_PATH\" \"${'$'}PORT\" || return 1")
            appendLine("  if [ -z \"${'$'}_token\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  if [ ! -r \"/proc/${'$'}_pid/environ\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  if tr '\\0' '\\n' < \"/proc/${'$'}_pid/environ\" 2>/dev/null | grep -Fxq \"RIKKAHUB_PTY_SERVER_TOKEN=${'$'}_token\"; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  return 1")
            appendLine("}")
            appendLine()
            appendLine("find_running_pty_server_pid_by_proc() {")
            appendLine("  _script=\"${'$'}1\"")
            appendLine("  _port=\"${'$'}2\"")
            appendLine("  [ -n \"${'$'}_script\" ] || return 1")
            appendLine("  [ -n \"${'$'}_port\" ] || return 1")
            appendLine("  for _p in /proc/[0-9]*; do")
            appendLine("    _pid=\"${'$'}{_p#/proc/}\"")
            appendLine("    [ -r \"${'$'}_p/cmdline\" ] || continue")
            appendLine("    _cmdline=\"${'$'}(cat \"${'$'}_p/cmdline\" 2>/dev/null | tr '\\0' ' ')\"")
            appendLine("    [ -n \"${'$'}_cmdline\" ] || continue")
            appendLine("    if is_pty_server_cmdline \"${'$'}_cmdline\" \"${'$'}_script\" \"${'$'}_port\"; then")
            appendLine("      echo \"${'$'}_pid\"")
            appendLine("      return 0")
            appendLine("    fi")
            appendLine("  done")
            appendLine("  return 1")
            appendLine("}")
            appendLine()
            appendLine("find_running_pty_server_pid_by_ps() {")
            appendLine("  _script=\"${'$'}1\"")
            appendLine("  _port=\"${'$'}2\"")
            appendLine("  [ -n \"${'$'}_script\" ] || return 1")
            appendLine("  [ -n \"${'$'}_port\" ] || return 1")
            appendLine("  while read -r _user _pid _ppid _c _stime _tty _time _cmdline; do")
            appendLine("    if [ \"${'$'}_pid\" = \"PID\" ]; then")
            appendLine("      continue")
            appendLine("    fi")
            appendLine("    [ -n \"${'$'}_cmdline\" ] || continue")
            appendLine("    if is_pty_server_cmdline \" ${'$'}{_cmdline} \" \"${'$'}_script\" \"${'$'}_port\"; then")
            appendLine("      echo \"${'$'}_pid\"")
            appendLine("      return 0")
            appendLine("    fi")
            appendLine("  done < <(ps -ef 2>/dev/null)")
            appendLine("  return 1")
            appendLine("}")
            appendLine()
            appendLine("kill_pid() {")
            appendLine("  _pid=\"${'$'}1\"")
            appendLine("  if [ -z \"${'$'}_pid\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  if ! kill -0 \"${'$'}_pid\" 2>/dev/null; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  kill \"${'$'}_pid\" 2>/dev/null || true")
            appendLine("  sleep 0.2")
            appendLine("  if kill -0 \"${'$'}_pid\" 2>/dev/null; then")
            appendLine("    kill -9 \"${'$'}_pid\" 2>/dev/null || true")
            appendLine("    sleep 0.2")
            appendLine("  fi")
            appendLine("  if kill -0 \"${'$'}_pid\" 2>/dev/null; then")
            appendLine("    return 1")
            appendLine("  fi")
            appendLine("  return 0")
            appendLine("}")
            appendLine()
            appendLine("cat > \"${'$'}SCRIPT_PATH\" <<'PY'")
            appendLine(termuxPtyServerScript().trim())
            appendLine("PY")
            appendLine()
            appendLine("if [ -f \"${'$'}PID_FILE\" ]; then")
            appendLine("  OLD_PID=\"$(cat \"${'$'}PID_FILE\" 2>/dev/null || true)\"")
            appendLine("  OLD_TOKEN=\"$(cat \"${'$'}TOKEN_FILE\" 2>/dev/null || true)\"")
            appendLine("  if [ -n \"${'$'}OLD_PID\" ] && kill -0 \"${'$'}OLD_PID\" 2>/dev/null; then")
            appendLine("    if is_pty_server_pid \"${'$'}OLD_PID\" \"${'$'}OLD_TOKEN\"; then")
            appendLine("      if ! kill_pid \"${'$'}OLD_PID\"; then")
            appendLine("        echo \"FAILED_TO_STOP_OLD_PTY_SERVER ${'$'}OLD_PID\" >&2")
            appendLine("        exit 1")
            appendLine("      fi")
            appendLine("    else")
            appendLine("      echo \"STALE_PTY_SERVER_PID ${'$'}OLD_PID\" >&2")
            appendLine("    fi")
            appendLine("  fi")
            appendLine("  rm -f \"${'$'}PID_FILE\" \"${'$'}TOKEN_FILE\"")
            appendLine("fi")
            appendLine()
            appendLine("ORPHAN_PID=\"${'$'}(find_running_pty_server_pid_by_proc \"${'$'}SCRIPT_PATH\" \"${'$'}PORT\" 2>/dev/null || true)\"")
            appendLine("if [ -z \"${'$'}ORPHAN_PID\" ]; then")
            appendLine("  ORPHAN_PID=\"${'$'}(find_running_pty_server_pid_by_ps \"${'$'}SCRIPT_PATH\" \"${'$'}PORT\" 2>/dev/null || true)\"")
            appendLine("fi")
            appendLine("if [ -n \"${'$'}ORPHAN_PID\" ]; then")
            appendLine("  if ! kill_pid \"${'$'}ORPHAN_PID\"; then")
            appendLine("    echo \"FAILED_TO_STOP_ORPHAN_PTY_SERVER ${'$'}ORPHAN_PID\" >&2")
            appendLine("    exit 1")
            appendLine("  fi")
            appendLine("fi")
            appendLine()
            appendLine(": > \"${'$'}LOG_FILE\"")
            appendLine("RIKKAHUB_PTY_SERVER_TOKEN=\"${'$'}TOKEN\" nohup python3 -u \"${'$'}SCRIPT_PATH\" --port \"${'$'}PORT\" --token \"${'$'}TOKEN\" >\"${'$'}LOG_FILE\" 2>&1 < /dev/null &")
            appendLine("echo \"${'$'}!\" > \"${'$'}PID_FILE\"")
            appendLine("echo \"${'$'}TOKEN\" > \"${'$'}TOKEN_FILE\"")
            appendLine()
            appendLine("python3 - \"${'$'}PORT\" \"${'$'}TOKEN\" \"${'$'}LOG_FILE\" \"${'$'}PID_FILE\" <<'PY'")
            appendLine("import json")
            appendLine("import os")
            appendLine("import sys")
            appendLine("import time")
            appendLine("import urllib.request")
            appendLine()
            appendLine("port = int(sys.argv[1])")
            appendLine("token = sys.argv[2]")
            appendLine("log_file = sys.argv[3]")
            appendLine("pid_file = sys.argv[4]")
            appendLine("url = \"http://127.0.0.1:{}/health\".format(port)")
            appendLine()
            appendLine("for _ in range(100):")
            appendLine("    request = urllib.request.Request(url, headers={\"X-RikkaHub-Token\": token})")
            appendLine("    try:")
            appendLine("        with urllib.request.urlopen(request, timeout=0.5) as response:")
            appendLine("            payload = json.loads(response.read().decode(\"utf-8\"))")
            appendLine("            if payload.get(\"ok\") is True:")
            appendLine("                sys.exit(0)")
            appendLine("    except Exception:")
            appendLine("        time.sleep(0.1)")
            appendLine()
            appendLine("print(\"Timed out waiting for PTY session server to become ready\", file=sys.stderr)")
            appendLine("if os.path.exists(pid_file):")
            appendLine("    try:")
            appendLine("        pid = open(pid_file, \"r\", encoding=\"utf-8\", errors=\"replace\").read().strip()")
            appendLine("    except Exception:")
            appendLine("        pid = \"\"")
            appendLine("    if pid:")
            appendLine("        if os.path.exists(\"/proc/{}\".format(pid)):")
            appendLine("            print(\"Server process is still alive but /health never became ready (pid={})\".format(pid), file=sys.stderr)")
            appendLine("        else:")
            appendLine("            print(\"Server process exited before becoming ready (pid={})\".format(pid), file=sys.stderr)")
            appendLine("if os.path.exists(log_file):")
            appendLine("    try:")
            appendLine("        with open(log_file, \"r\", encoding=\"utf-8\", errors=\"replace\") as handle:")
            appendLine("            lines = handle.readlines()")
            appendLine("    except Exception:")
            appendLine("        lines = []")
            appendLine("    if lines:")
            appendLine("        print(\"--- PTY server log tail ---\", file=sys.stderr)")
            appendLine("        for line in lines[-40:]:")
            appendLine("            print(line.rstrip(\"\\n\"), file=sys.stderr)")
            appendLine("sys.exit(1)")
            appendLine("PY")
        }
    }

    private fun buildRecoverTokenScript(): String {
        return """
            set -eu

            STATE_DIR="${'$'}HOME/.rikkahub"
            PID_FILE="${'$'}STATE_DIR/pty_session_server.pid"
            TOKEN_FILE="${'$'}STATE_DIR/pty_session_server.token"

            [ -r "${'$'}PID_FILE" ] || exit 0
            [ -r "${'$'}TOKEN_FILE" ] || exit 0

            PID="$(cat "${'$'}PID_FILE" 2>/dev/null || true)"
            [ -n "${'$'}PID" ] || exit 0
            kill -0 "${'$'}PID" 2>/dev/null || exit 0

            cat "${'$'}TOKEN_FILE" 2>/dev/null || true
        """.trimIndent()
    }

    private fun buildStopServerScript(): String {
        return """
            set -eu

            STATE_DIR="${'$'}HOME/.rikkahub"
            PID_FILE="${'$'}STATE_DIR/pty_session_server.pid"
            TOKEN_FILE="${'$'}STATE_DIR/pty_session_server.token"

            kill_pid() {
              _pid="${'$'}1"
              [ -n "${'$'}_pid" ] || return 0
              if ! kill -0 "${'$'}_pid" 2>/dev/null; then
                return 0
              fi
              kill "${'$'}_pid" 2>/dev/null || true
              sleep 0.2
              if kill -0 "${'$'}_pid" 2>/dev/null; then
                kill -9 "${'$'}_pid" 2>/dev/null || true
                sleep 0.2
              fi
              return 0
            }

            PID="$(cat "${'$'}PID_FILE" 2>/dev/null || true)"
            kill_pid "${'$'}PID"
            rm -f "${'$'}PID_FILE" "${'$'}TOKEN_FILE"
            echo "PTY session server stopped."
        """.trimIndent()
    }

    private fun buildProbeServerScript(port: Int): String {
        return """
            set -eu

            STATE_DIR="${'$'}HOME/.rikkahub"
            SCRIPT_PATH="${'$'}STATE_DIR/pty_session_server.py"
            PID_FILE="${'$'}STATE_DIR/pty_session_server.pid"
            PORT="$port"

            is_pty_server_cmdline() {
              _cmdline="${'$'}1"
              _script="${'$'}2"
              _port="${'$'}3"
              _padded=" ${'$'}{_cmdline} "
              case "${'$'}_padded" in
                *" python3 -u ${'$'}_script --port ${'$'}_port --token "*|*" python3 -u ${'$'}_script --port ${'$'}_port "*" --token "*)
                  return 0
                  ;;
                *)
                  return 1
                  ;;
              esac
            }

            is_pty_server_pid() {
              _pid="${'$'}1"
              [ -n "${'$'}_pid" ] || return 1
              [ -r "/proc/${'$'}_pid/cmdline" ] || return 1
              _cmdline="${'$'}(cat "/proc/${'$'}_pid/cmdline" 2>/dev/null | tr '\0' ' ')"
              [ -n "${'$'}_cmdline" ] || return 1
              is_pty_server_cmdline "${'$'}_cmdline" "${'$'}SCRIPT_PATH" "${'$'}PORT"
            }

            find_running_pty_server_pid_by_proc() {
              for _p in /proc/[0-9]*; do
                _pid="${'$'}{_p#/proc/}"
                [ -r "${'$'}_p/cmdline" ] || continue
                _cmdline="${'$'}(cat "${'$'}_p/cmdline" 2>/dev/null | tr '\0' ' ')"
                [ -n "${'$'}_cmdline" ] || continue
                if is_pty_server_cmdline "${'$'}_cmdline" "${'$'}SCRIPT_PATH" "${'$'}PORT"; then
                  echo "${'$'}_pid"
                  return 0
                fi
              done
              return 1
            }

            find_running_pty_server_pid_by_ps() {
              while read -r _user _pid _ppid _c _stime _tty _time _cmdline; do
                [ "${'$'}_pid" = "PID" ] && continue
                [ -n "${'$'}_cmdline" ] || continue
                if is_pty_server_cmdline " ${'$'}{_cmdline} " "${'$'}SCRIPT_PATH" "${'$'}PORT"; then
                  echo "${'$'}_pid"
                  return 0
                fi
              done < <(ps -ef 2>/dev/null)
              return 1
            }

            PID="$(cat "${'$'}PID_FILE" 2>/dev/null || true)"
            if [ -n "${'$'}PID" ] && kill -0 "${'$'}PID" 2>/dev/null && is_pty_server_pid "${'$'}PID"; then
              echo "running"
              exit 0
            fi

            ORPHAN_PID="${'$'}(find_running_pty_server_pid_by_proc 2>/dev/null || true)"
            if [ -z "${'$'}ORPHAN_PID" ]; then
              ORPHAN_PID="${'$'}(find_running_pty_server_pid_by_ps 2>/dev/null || true)"
            fi

            if [ -n "${'$'}ORPHAN_PID" ]; then
              echo "running"
            else
              echo "stopped"
            fi
        """.trimIndent()
    }

    private fun termuxPtyServerScript(): String {
        return """
            #!/usr/bin/env python3
            import argparse
            import codecs
            import errno
            import fcntl
            import json
            import os
            import pty
            import select
            import signal
            import struct
            import termios
            import threading
            import time
            import uuid
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

            TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
            DEFAULT_TERM = "xterm-256color"
            SESSION_TTL_SECONDS = 1800
            SESSION_SWEEP_INTERVAL_SECONDS = 60
            MAX_PENDING_OUTPUT_CHARS = 120000

            def decode_exit_code(status):
                if hasattr(os, "waitstatus_to_exitcode"):
                    return os.waitstatus_to_exitcode(status)
                if os.WIFEXITED(status):
                    return os.WEXITSTATUS(status)
                if os.WIFSIGNALED(status):
                    return 128 + os.WTERMSIG(status)
                return None

            def set_nonblocking(fd):
                flags = fcntl.fcntl(fd, fcntl.F_GETFL)
                fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

            def set_winsize(fd, rows, cols):
                packed = struct.pack("HHHH", rows, cols, 0, 0)
                fcntl.ioctl(fd, termios.TIOCSWINSZ, packed)

            class Session:
                def __init__(self, command, workdir, cols, rows):
                    self.id = uuid.uuid4().hex
                    self.command = command
                    self.workdir = workdir
                    self.cols = cols
                    self.rows = rows
                    self.pid = None
                    self.master_fd = None
                    self.running = True
                    self.exit_code = None
                    self.pending_output = ""
                    self.output_overflowed = False
                    now = time.time()
                    self.created_at = now
                    self.last_access = now
                    self.lock = threading.Lock()
                    self.condition = threading.Condition(self.lock)
                    self.decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                    self._start()

                def _start(self):
                    pid, master_fd = pty.fork()
                    if pid == 0:
                        try:
                            os.chdir(self.workdir)
                        except Exception as exc:
                            os.write(
                                2,
                                "Failed to change directory to {}: {}\n".format(
                                    self.workdir,
                                    exc,
                                ).encode("utf-8", errors="replace"),
                            )
                            os._exit(1)
                        env = os.environ.copy()
                        env.setdefault("TERM", DEFAULT_TERM)
                        env["COLUMNS"] = str(self.cols)
                        env["LINES"] = str(self.rows)
                        os.execvpe(TERMUX_BASH_PATH, [TERMUX_BASH_PATH, "-lc", self.command], env)
                    self.pid = pid
                    self.master_fd = master_fd
                    set_nonblocking(master_fd)
                    set_winsize(master_fd, self.rows, self.cols)
                    threading.Thread(target=self._reader_loop, daemon=True).start()
                    threading.Thread(target=self._waiter_loop, daemon=True).start()

                def _append_output(self, text):
                    if not text:
                        return
                    self.pending_output += text
                    if len(self.pending_output) > MAX_PENDING_OUTPUT_CHARS:
                        self.pending_output = self.pending_output[-MAX_PENDING_OUTPUT_CHARS:]
                        self.output_overflowed = True

                def _reader_loop(self):
                    while True:
                        try:
                            ready, _, _ = select.select([self.master_fd], [], [], 0.2)
                            if not ready:
                                with self.condition:
                                    if not self.running:
                                        self.condition.notify_all()
                                if not self.running:
                                    return
                                continue
                            data = os.read(self.master_fd, 4096)
                            if not data:
                                break
                            text = self.decoder.decode(data)
                            with self.condition:
                                self._append_output(text)
                                self.last_access = time.time()
                                self.condition.notify_all()
                        except OSError as exc:
                            if exc.errno in (errno.EIO, errno.EBADF):
                                break
                            with self.condition:
                                self._append_output("\n[PTY read error] {}\n".format(exc))
                                self.condition.notify_all()
                            break
                    tail = self.decoder.decode(b"", final=True)
                    if tail:
                        with self.condition:
                            self._append_output(tail)
                            self.condition.notify_all()
                    with self.condition:
                        self.condition.notify_all()

                def _waiter_loop(self):
                    _, status = os.waitpid(self.pid, 0)
                    with self.condition:
                        self.running = False
                        self.exit_code = decode_exit_code(status)
                        self.last_access = time.time()
                        self.condition.notify_all()
                    try:
                        os.close(self.master_fd)
                    except OSError:
                        pass

                def write(self, chars):
                    if not chars:
                        return
                    pending = memoryview(chars.encode("utf-8"))
                    while pending:
                        with self.condition:
                            if not self.running:
                                raise RuntimeError("Session is no longer running")
                        try:
                            written = os.write(self.master_fd, pending)
                            if written <= 0:
                                raise RuntimeError("Failed to write to PTY session")
                            pending = pending[written:]
                        except BlockingIOError:
                            _, ready, _ = select.select([], [self.master_fd], [], 1.0)
                            if not ready:
                                continue
                        except InterruptedError:
                            continue
                        except OSError as exc:
                            raise RuntimeError(str(exc) or "Failed to write to PTY session")
                    with self.condition:
                        self.last_access = time.time()

                def read(self, yield_time_ms, max_output_chars):
                    timeout_seconds = max(yield_time_ms, 0) / 1000.0
                    end_time = time.time() + timeout_seconds
                    with self.condition:
                        self.last_access = time.time()
                        while (
                            not self.pending_output
                            and self.running
                            and timeout_seconds > 0
                        ):
                            remaining = end_time - time.time()
                            if remaining <= 0:
                                break
                            self.condition.wait(remaining)
                        output = self.pending_output[:max_output_chars]
                        self.pending_output = self.pending_output[max_output_chars:]
                        overflowed = self.output_overflowed
                        self.output_overflowed = False
                        truncated = bool(self.pending_output) or overflowed
                        has_more = bool(self.pending_output)
                        running = self.running
                        exit_code = self.exit_code
                        self.last_access = time.time()
                    return {
                        "output": output,
                        "running": running,
                        "exit_code": exit_code,
                        "truncated": truncated,
                        "keep_session": running or has_more,
                    }

                def is_idle(self, now):
                    return now - self.last_access > SESSION_TTL_SECONDS

                def close(self):
                    try:
                        pgid = os.getpgid(self.pid)
                        os.killpg(pgid, signal.SIGHUP)
                    except Exception:
                        pass
                    time.sleep(0.2)
                    try:
                        if self.running:
                            pgid = os.getpgid(self.pid)
                            os.killpg(pgid, signal.SIGKILL)
                    except Exception:
                        pass
                    try:
                        os.close(self.master_fd)
                    except Exception:
                        pass

                def to_info(self):
                    with self.condition:
                        return {
                            "id": self.id,
                            "command": self.command,
                            "workdir": self.workdir,
                            "pid": self.pid,
                            "running": self.running,
                            "exit_code": self.exit_code,
                            "created_at_ms": int(self.created_at * 1000),
                            "last_access_ms": int(self.last_access * 1000),
                            "pending_output_chars": len(self.pending_output),
                        }

            class SessionRegistry:
                def __init__(self):
                    self._sessions = {}
                    self._lock = threading.Lock()

                def create(self, command, workdir, cols, rows):
                    session = Session(command=command, workdir=workdir, cols=cols, rows=rows)
                    with self._lock:
                        self._sessions[session.id] = session
                    return session

                def get(self, session_id):
                    with self._lock:
                        return self._sessions.get(session_id)

                def maybe_remove(self, session_id, keep_session):
                    if keep_session:
                        return
                    with self._lock:
                        self._sessions.pop(session_id, None)

                def list_all(self):
                    with self._lock:
                        return [session.to_info() for session in self._sessions.values()]

                def close_session(self, session_id):
                    with self._lock:
                        session = self._sessions.pop(session_id, None)
                    if session is None:
                        return False
                    session.close()
                    return True

                def close_all(self):
                    with self._lock:
                        sessions = list(self._sessions.values())
                        self._sessions.clear()
                    for session in sessions:
                        session.close()
                    return len(sessions)

                def sweep(self):
                    now = time.time()
                    expired = []
                    with self._lock:
                        for session_id, session in list(self._sessions.items()):
                            if session.is_idle(now):
                                expired.append((session_id, session))
                                self._sessions.pop(session_id, None)
                    for _, session in expired:
                        session.close()

            registry = SessionRegistry()

            class ReusableThreadingHTTPServer(ThreadingHTTPServer):
                allow_reuse_address = True

            class Handler(BaseHTTPRequestHandler):
                server_version = "RikkaHubPTY/1.0"

                def do_GET(self):
                    if not self._authorize():
                        return
                    if self.path == "/health":
                        self._respond(200, {"ok": True})
                        return
                    if self.path == "/sessions":
                        self._respond(200, {"sessions": registry.list_all(), "running": True})
                        return
                    self._respond(404, {"error": "Not found", "running": False})

                def do_POST(self):
                    if not self._authorize():
                        return
                    try:
                        content_length = int(self.headers.get("Content-Length", "0"))
                    except ValueError:
                        content_length = 0
                    raw_body = self.rfile.read(content_length) if content_length > 0 else b"{}"
                    try:
                        payload = json.loads(raw_body.decode("utf-8"))
                    except Exception:
                        self._respond(400, {"error": "Invalid JSON payload", "running": False})
                        return

                    if self.path == "/sessions":
                        self._handle_create(payload)
                        return

                    parts = self.path.strip("/").split("/")
                    if len(parts) == 3 and parts[0] == "sessions" and parts[2] == "stdin":
                        self._handle_write(parts[1], payload)
                        return

                    self._respond(404, {"error": "Not found", "running": False})

                def do_DELETE(self):
                    if not self._authorize():
                        return
                    if self.path == "/sessions":
                        closed = registry.close_all()
                        self._respond(200, {"success": True, "running": False, "closed": closed})
                        return

                    parts = self.path.strip("/").split("/")
                    if len(parts) == 2 and parts[0] == "sessions":
                        if registry.close_session(parts[1]):
                            self._respond(200, {"success": True, "running": False})
                        else:
                            self._respond(404, {"success": False, "running": False, "error": "Session not found"})
                        return

                    self._respond(404, {"error": "Not found", "running": False})

                def log_message(self, format, *args):
                    return

                def _authorize(self):
                    expected = self.server.auth_token
                    actual = self.headers.get("X-RikkaHub-Token", "")
                    if actual != expected:
                        self._respond(401, {"error": "Unauthorized", "running": False})
                        return False
                    return True

                def _handle_create(self, payload):
                    command = str(payload.get("command", "")).strip()
                    workdir = str(payload.get("workdir", "")).strip() or os.path.expanduser("~")
                    yield_time_ms = int(payload.get("yield_time_ms", 250))
                    max_output_chars = max(int(payload.get("max_output_chars", 12000)), 256)
                    cols = max(int(payload.get("cols", 120)), 20)
                    rows = max(int(payload.get("rows", 40)), 5)

                    if not command:
                        self._respond(400, {"error": "command is required", "running": False})
                        return

                    session = registry.create(command=command, workdir=workdir, cols=cols, rows=rows)
                    response = session.read(yield_time_ms=yield_time_ms, max_output_chars=max_output_chars)
                    keep_session = response.pop("keep_session")
                    response["session_id"] = session.id if keep_session else None
                    registry.maybe_remove(session.id, keep_session)
                    self._respond(200, response)

                def _handle_write(self, session_id, payload):
                    session = registry.get(session_id)
                    if session is None:
                        self._respond(404, {"error": "Session not found", "running": False})
                        return

                    chars = str(payload.get("chars", ""))
                    yield_time_ms = int(payload.get("yield_time_ms", 250))
                    max_output_chars = max(int(payload.get("max_output_chars", 12000)), 256)

                    try:
                        session.write(chars)
                    except Exception as exc:
                        self._respond(
                            409,
                            {
                                "error": str(exc) or "Failed to write to session",
                                "running": False,
                                "session_id": None,
                            }
                        )
                        return

                    response = session.read(yield_time_ms=yield_time_ms, max_output_chars=max_output_chars)
                    keep_session = response.pop("keep_session")
                    response["session_id"] = session.id if keep_session else None
                    registry.maybe_remove(session.id, keep_session)
                    self._respond(200, response)

                def _respond(self, status_code, payload):
                    encoded = json.dumps(payload).encode("utf-8")
                    self.send_response(status_code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(encoded)))
                    self.end_headers()
                    self.wfile.write(encoded)

            def sweep_forever():
                while True:
                    time.sleep(SESSION_SWEEP_INTERVAL_SECONDS)
                    registry.sweep()

            def main():
                parser = argparse.ArgumentParser()
                parser.add_argument("--port", type=int, required=True)
                parser.add_argument("--token", type=str, required=True)
                args = parser.parse_args()

                threading.Thread(target=sweep_forever, daemon=True).start()

                server = ReusableThreadingHTTPServer(("127.0.0.1", args.port), Handler)
                server.auth_token = args.token
                server.serve_forever()

            if __name__ == "__main__":
                main()
        """.trimIndent()
    }

    private companion object {
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
        private const val TOKEN_HEADER = "X-RikkaHub-Token"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
