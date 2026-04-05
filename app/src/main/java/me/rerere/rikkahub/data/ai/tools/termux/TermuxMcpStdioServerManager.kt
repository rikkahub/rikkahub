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

class TermuxMcpStdioServerManager(
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    private val termuxCommandManager: TermuxCommandManager,
) {
    private val ensureServerMutex = Mutex()

    @Volatile
    private var serverToken: String? = null

    suspend fun startSession(
        command: String,
        args: List<String>,
        workdir: String,
        environment: Map<String, String>,
    ): TermuxMcpStdioStartResponse {
        val port = ensureServerRunning()
        return postJson(
            port = port,
            path = "/sessions",
            payload = TermuxMcpStdioStartRequest(
                command = command,
                args = args,
                workdir = workdir,
                env = environment,
            )
        )
    }

    internal suspend fun readStream(
        sessionId: String,
        stream: TermuxMcpStdioStream,
        waitTimeMs: Long = TERMUX_MCP_STDIO_DEFAULT_WAIT_TIME_MS,
        maxBytes: Int = TERMUX_MCP_STDIO_DEFAULT_MAX_BYTES,
    ): TermuxMcpStdioReadResponse {
        val port = ensureServerRunning()
        return postJson(
            port = port,
            path = "/sessions/$sessionId/read",
            payload = TermuxMcpStdioReadRequest(
                stream = stream.wireName,
                waitTimeMs = waitTimeMs.coerceAtLeast(0L),
                maxBytes = maxBytes.coerceAtLeast(1),
            )
        )
    }

    suspend fun writeStdin(
        sessionId: String,
        dataBase64: String,
    ): TermuxMcpStdioActionResponse {
        val port = ensureServerRunning()
        return postJson(
            port = port,
            path = "/sessions/$sessionId/stdin",
            payload = TermuxMcpStdioWriteRequest(dataBase64 = dataBase64),
        )
    }

    suspend fun closeSession(sessionId: String): TermuxMcpStdioActionResponse {
        val port = currentPort()
        val token = resolveServerToken(port) ?: return TermuxMcpStdioActionResponse(
            success = false,
            running = false,
            error = "Termux MCP stdio server is not running.",
        )
        return runCatching {
            deleteJson<TermuxMcpStdioActionResponse>(
                port = port,
                token = token,
                path = "/sessions/$sessionId",
            )
        }.getOrElse { e ->
            TermuxMcpStdioActionResponse(
                success = false,
                running = false,
                error = e.message ?: e.javaClass.name,
            )
        }
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
                    label = "RikkaHub MCP stdio bridge",
                    description = "Start local MCP stdio bridge server",
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
                        if (result.timedOut) append("Timed out while starting MCP stdio bridge server.")
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
                error(message.ifBlank { "Failed to start MCP stdio bridge server in Termux." })
            }

            if (!hasCompatibleServer(port = port, token = token)) {
                val output = TermuxOutputFormatter.merge(
                    stdout = result.stdout,
                    stderr = result.stderr,
                    errMsg = result.errMsg,
                )
                error(
                    listOf(
                        "MCP stdio bridge server did not become ready.",
                        output.takeIf { it.isNotBlank() },
                    ).joinToString(separator = "\n")
                )
            }

            serverToken = token
            return port
        }
    }

    private suspend fun resolveServerToken(port: Int): String? {
        val existingToken = serverToken
        if (existingToken != null && hasCompatibleServer(port = port, token = existingToken)) {
            return existingToken
        }

        val recoveredToken = recoverPersistedToken()?.takeIf { hasCompatibleServer(port = port, token = it) } ?: return null
        serverToken = recoveredToken
        return recoveredToken
    }

    private suspend fun recoverPersistedToken(): String? {
        val result = runCatching {
            termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf("-lc", buildRecoverTokenScript()),
                    workdir = TERMUX_HOME_PATH,
                    background = true,
                    timeoutMs = 5_000L,
                    label = "RikkaHub inspect MCP stdio bridge",
                    description = "Read local MCP stdio bridge token",
                    trackLifecycle = false,
                )
            )
        }.getOrNull() ?: return null
        if (!result.isSuccessful()) return null
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private suspend fun hasCompatibleServer(
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
                    if (!response.isSuccessful) return@use false
                    val payload = json.decodeFromString<TermuxMcpStdioHealthResponse>(response.body.string())
                    payload.ok && payload.version == TERMUX_MCP_STDIO_SERVER_VERSION
                }
            }.getOrDefault(false)
        }
    }

    private suspend inline fun <reified T : Any, reified R : Any> postJson(
        port: Int,
        path: String,
        payload: T,
    ): R {
        val token = serverToken ?: resolveServerToken(port) ?: error("MCP stdio bridge server token is missing")
        return postJson(
            port = port,
            token = token,
            path = path,
            payload = payload,
        )
    }

    private suspend inline fun <reified T : Any, reified R : Any> postJson(
        port: Int,
        token: String,
        path: String,
        payload: T,
    ): R {
        return withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
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
                runCatching { json.decodeFromString<TermuxMcpStdioActionResponse>(it) }.getOrNull()
            }
            throw IOException(
                parsed?.error ?: "MCP stdio bridge request failed for $path with HTTP ${response.code}"
            )
        }
        return json.decodeFromString(body)
    }

    private fun currentPort(): Int {
        return TERMUX_MCP_STDIO_DEFAULT_SERVER_PORT
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
            appendLine("SCRIPT_PATH=\"${'$'}STATE_DIR/mcp_stdio_server.py\"")
            appendLine("PID_FILE=\"${'$'}STATE_DIR/mcp_stdio_server.pid\"")
            appendLine("TOKEN_FILE=\"${'$'}STATE_DIR/mcp_stdio_server.token\"")
            appendLine("LOG_FILE=\"${'$'}STATE_DIR/mcp_stdio_server.log\"")
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
            appendLine("is_stdio_server_cmdline() {")
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
            appendLine("is_stdio_server_pid() {")
            appendLine("  _pid=\"${'$'}1\"")
            appendLine("  _token=\"${'$'}2\"")
            appendLine("  [ -n \"${'$'}_pid\" ] || return 1")
            appendLine("  [ -r \"/proc/${'$'}_pid/cmdline\" ] || return 1")
            appendLine("  _cmdline=\"${'$'}(cat \"/proc/${'$'}_pid/cmdline\" 2>/dev/null | tr '\\0' ' ')\"")
            appendLine("  [ -n \"${'$'}_cmdline\" ] || return 1")
            appendLine("  is_stdio_server_cmdline \"${'$'}_cmdline\" \"${'$'}SCRIPT_PATH\" \"${'$'}PORT\" || return 1")
            appendLine("  if [ -z \"${'$'}_token\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  if [ ! -r \"/proc/${'$'}_pid/environ\" ]; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  if tr '\\0' '\\n' < \"/proc/${'$'}_pid/environ\" 2>/dev/null | grep -Fxq \"RIKKAHUB_MCP_STDIO_SERVER_TOKEN=${'$'}_token\"; then")
            appendLine("    return 0")
            appendLine("  fi")
            appendLine("  return 1")
            appendLine("}")
            appendLine()
            appendLine("find_running_stdio_server_pid_by_proc() {")
            appendLine("  _script=\"${'$'}1\"")
            appendLine("  _port=\"${'$'}2\"")
            appendLine("  [ -n \"${'$'}_script\" ] || return 1")
            appendLine("  [ -n \"${'$'}_port\" ] || return 1")
            appendLine("  for _p in /proc/[0-9]*; do")
            appendLine("    _pid=\"${'$'}{_p#/proc/}\"")
            appendLine("    [ -r \"${'$'}_p/cmdline\" ] || continue")
            appendLine("    _cmdline=\"${'$'}(cat \"${'$'}_p/cmdline\" 2>/dev/null | tr '\\0' ' ')\"")
            appendLine("    [ -n \"${'$'}_cmdline\" ] || continue")
            appendLine("    if is_stdio_server_cmdline \"${'$'}_cmdline\" \"${'$'}_script\" \"${'$'}_port\"; then")
            appendLine("      echo \"${'$'}_pid\"")
            appendLine("      return 0")
            appendLine("    fi")
            appendLine("  done")
            appendLine("  return 1")
            appendLine("}")
            appendLine()
            appendLine("find_running_stdio_server_pid_by_ps() {")
            appendLine("  _script=\"${'$'}1\"")
            appendLine("  _port=\"${'$'}2\"")
            appendLine("  [ -n \"${'$'}_script\" ] || return 1")
            appendLine("  [ -n \"${'$'}_port\" ] || return 1")
            appendLine("  while read -r _user _pid _ppid _c _stime _tty _time _cmdline; do")
            appendLine("    if [ \"${'$'}_pid\" = \"PID\" ]; then")
            appendLine("      continue")
            appendLine("    fi")
            appendLine("    [ -n \"${'$'}_cmdline\" ] || continue")
            appendLine("    if is_stdio_server_cmdline \" ${'$'}{_cmdline} \" \"${'$'}_script\" \"${'$'}_port\"; then")
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
            appendLine(termuxMcpStdioServerScript().trim())
            appendLine("PY")
            appendLine()
            appendLine("if [ -f \"${'$'}PID_FILE\" ]; then")
            appendLine("  OLD_PID=\"$(cat \"${'$'}PID_FILE\" 2>/dev/null || true)\"")
            appendLine("  OLD_TOKEN=\"$(cat \"${'$'}TOKEN_FILE\" 2>/dev/null || true)\"")
            appendLine("  if [ -n \"${'$'}OLD_PID\" ] && kill -0 \"${'$'}OLD_PID\" 2>/dev/null; then")
            appendLine("    if is_stdio_server_pid \"${'$'}OLD_PID\" \"${'$'}OLD_TOKEN\"; then")
            appendLine("      if ! kill_pid \"${'$'}OLD_PID\"; then")
            appendLine("        echo \"FAILED_TO_STOP_OLD_MCP_STDIO_SERVER ${'$'}OLD_PID\" >&2")
            appendLine("        exit 1")
            appendLine("      fi")
            appendLine("    else")
            appendLine("      echo \"STALE_MCP_STDIO_SERVER_PID ${'$'}OLD_PID\" >&2")
            appendLine("    fi")
            appendLine("  fi")
            appendLine("  rm -f \"${'$'}PID_FILE\" \"${'$'}TOKEN_FILE\"")
            appendLine("fi")
            appendLine()
            appendLine("ORPHAN_PID=\"${'$'}(find_running_stdio_server_pid_by_proc \"${'$'}SCRIPT_PATH\" \"${'$'}PORT\" 2>/dev/null || true)\"")
            appendLine("if [ -z \"${'$'}ORPHAN_PID\" ]; then")
            appendLine("  ORPHAN_PID=\"${'$'}(find_running_stdio_server_pid_by_ps \"${'$'}SCRIPT_PATH\" \"${'$'}PORT\" 2>/dev/null || true)\"")
            appendLine("fi")
            appendLine("if [ -n \"${'$'}ORPHAN_PID\" ]; then")
            appendLine("  if ! kill_pid \"${'$'}ORPHAN_PID\"; then")
            appendLine("    echo \"FAILED_TO_STOP_ORPHAN_MCP_STDIO_SERVER ${'$'}ORPHAN_PID\" >&2")
            appendLine("    exit 1")
            appendLine("  fi")
            appendLine("fi")
            appendLine()
            appendLine(": > \"${'$'}LOG_FILE\"")
            appendLine("RIKKAHUB_MCP_STDIO_SERVER_TOKEN=\"${'$'}TOKEN\" nohup python3 -u \"${'$'}SCRIPT_PATH\" --port \"${'$'}PORT\" --token \"${'$'}TOKEN\" >\"${'$'}LOG_FILE\" 2>&1 < /dev/null &")
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
            appendLine("print(\"Timed out waiting for MCP stdio bridge server to become ready\", file=sys.stderr)")
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
            appendLine("        print(\"--- MCP stdio bridge log tail ---\", file=sys.stderr)")
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
            PID_FILE="${'$'}STATE_DIR/mcp_stdio_server.pid"
            TOKEN_FILE="${'$'}STATE_DIR/mcp_stdio_server.token"

            [ -r "${'$'}PID_FILE" ] || exit 0
            [ -r "${'$'}TOKEN_FILE" ] || exit 0

            PID="$(cat "${'$'}PID_FILE" 2>/dev/null || true)"
            [ -n "${'$'}PID" ] || exit 0
            kill -0 "${'$'}PID" 2>/dev/null || exit 0

            cat "${'$'}TOKEN_FILE" 2>/dev/null || true
        """.trimIndent()
    }

    private fun termuxMcpStdioServerScript(): String {
        return """
            #!/usr/bin/env python3
            import argparse
            import base64
            import json
            import os
            import signal
            import subprocess
            import threading
            import time
            import uuid
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

            SESSION_TTL_SECONDS = 1800
            SESSION_SWEEP_INTERVAL_SECONDS = 60
            SERVER_VERSION = ${TERMUX_MCP_STDIO_SERVER_VERSION}

            class StdioSession:
                def __init__(self, command, args, workdir, env):
                    self.id = uuid.uuid4().hex
                    self.command = command
                    self.args = list(args)
                    self.workdir = workdir
                    self.env = dict(env)
                    self.process = None
                    self.running = True
                    self.exit_code = None
                    self.stdout_pending = bytearray()
                    self.stderr_pending = bytearray()
                    self.stdout_eof = False
                    self.stderr_eof = False
                    now = time.time()
                    self.created_at = now
                    self.last_access = now
                    self.lock = threading.Lock()
                    self.condition = threading.Condition(self.lock)
                    self._start()

                def _start(self):
                    child_env = os.environ.copy()
                    child_env.update({str(k): str(v) for k, v in self.env.items()})
                    self.process = subprocess.Popen(
                        [self.command] + self.args,
                        cwd=self.workdir,
                        env=child_env,
                        stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        bufsize=0,
                        start_new_session=True,
                    )
                    threading.Thread(
                        target=self._reader_loop,
                        args=("stdout", self.process.stdout),
                        daemon=True,
                    ).start()
                    threading.Thread(
                        target=self._reader_loop,
                        args=("stderr", self.process.stderr),
                        daemon=True,
                    ).start()
                    threading.Thread(target=self._waiter_loop, daemon=True).start()

                def _pending_buffer(self, stream):
                    return self.stdout_pending if stream == "stdout" else self.stderr_pending

                def _stream_eof(self, stream):
                    return self.stdout_eof if stream == "stdout" else self.stderr_eof

                def _set_stream_eof(self, stream):
                    if stream == "stdout":
                        self.stdout_eof = True
                    else:
                        self.stderr_eof = True

                def _reader_loop(self, stream, handle):
                    try:
                        while True:
                            chunk = handle.read(4096)
                            if not chunk:
                                break
                            with self.condition:
                                self._pending_buffer(stream).extend(chunk)
                                self.last_access = time.time()
                                self.condition.notify_all()
                    finally:
                        with self.condition:
                            self._set_stream_eof(stream)
                            self.last_access = time.time()
                            self.condition.notify_all()
                        try:
                            handle.close()
                        except Exception:
                            pass

                def _waiter_loop(self):
                    code = self.process.wait()
                    with self.condition:
                        self.running = False
                        self.exit_code = code
                        self.last_access = time.time()
                        self.condition.notify_all()

                def read(self, stream, wait_time_ms, max_bytes):
                    timeout_seconds = max(wait_time_ms, 0) / 1000.0
                    deadline = time.time() + timeout_seconds
                    with self.condition:
                        self.last_access = time.time()
                        while not self._pending_buffer(stream) and not self._stream_eof(stream):
                            remaining = deadline - time.time()
                            if remaining <= 0:
                                break
                            self.condition.wait(remaining)
                        pending = self._pending_buffer(stream)
                        chunk = bytes(pending[:max_bytes])
                        del pending[:max_bytes]
                        eof = self._stream_eof(stream) and not pending
                        self.last_access = time.time()
                    return {
                        "data_base64": base64.b64encode(chunk).decode("ascii") if chunk else "",
                        "eof": eof,
                        "running": self.running,
                        "exit_code": self.exit_code,
                        "error": None,
                    }

                def write(self, data):
                    if not data:
                        return
                    if self.process.stdin is None:
                        raise RuntimeError("stdin is not available")
                    if self.process.poll() is not None:
                        raise RuntimeError("Process is no longer running")
                    self.process.stdin.write(data)
                    self.process.stdin.flush()
                    with self.condition:
                        self.last_access = time.time()

                def close(self):
                    proc = self.process
                    if proc is None:
                        return
                    if proc.poll() is None:
                        try:
                            os.killpg(proc.pid, signal.SIGTERM)
                        except Exception:
                            proc.terminate()
                        time.sleep(0.2)
                        if proc.poll() is None:
                            try:
                                os.killpg(proc.pid, signal.SIGKILL)
                            except Exception:
                                proc.kill()
                    for handle in (proc.stdin, proc.stdout, proc.stderr):
                        if handle is None:
                            continue
                        try:
                            handle.close()
                        except Exception:
                            pass

                def is_idle(self, now):
                    return now - self.last_access > SESSION_TTL_SECONDS

            class SessionRegistry:
                def __init__(self):
                    self._sessions = {}
                    self._lock = threading.Lock()

                def create(self, command, args, workdir, env):
                    session = StdioSession(command=command, args=args, workdir=workdir, env=env)
                    with self._lock:
                        self._sessions[session.id] = session
                    return session

                def get(self, session_id):
                    with self._lock:
                        return self._sessions.get(session_id)

                def close_session(self, session_id):
                    with self._lock:
                        session = self._sessions.pop(session_id, None)
                    if session is None:
                        return False
                    session.close()
                    return True

                def sweep(self):
                    now = time.time()
                    expired = []
                    with self._lock:
                        for session_id, session in list(self._sessions.items()):
                            if session.is_idle(now):
                                expired.append(session)
                                self._sessions.pop(session_id, None)
                    for session in expired:
                        session.close()

            registry = SessionRegistry()

            class ReusableThreadingHTTPServer(ThreadingHTTPServer):
                allow_reuse_address = True
                daemon_threads = True

            class Handler(BaseHTTPRequestHandler):
                server_version = "RikkaHubMcpStdio/1.0"

                def _send_json(self, status_code, payload):
                    body = json.dumps(payload).encode("utf-8")
                    self.send_response(status_code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

                def _read_json_body(self):
                    length = int(self.headers.get("Content-Length", "0") or "0")
                    raw = self.rfile.read(length) if length > 0 else b"{}"
                    try:
                        payload = json.loads(raw.decode("utf-8"))
                        if isinstance(payload, dict):
                            return payload
                    except Exception:
                        pass
                    return {}

                def _require_auth(self):
                    expected = getattr(self.server, "auth_token", "")
                    actual = self.headers.get("X-RikkaHub-Token", "")
                    if expected and actual != expected:
                        self._send_json(401, {"error": "Unauthorized"})
                        return False
                    return True

                def _split_path(self):
                    return [part for part in self.path.split("?")[0].split("/") if part]

                def do_GET(self):
                    if not self._require_auth():
                        return
                    if self.path.split("?")[0] == "/health":
                        self._send_json(200, {"ok": True, "version": SERVER_VERSION})
                        return
                    self._send_json(404, {"error": "Not found"})

                def do_POST(self):
                    if not self._require_auth():
                        return
                    parts = self._split_path()
                    payload = self._read_json_body()

                    if parts == ["sessions"]:
                        command = str(payload.get("command", "")).strip()
                        if not command:
                            self._send_json(400, {"error": "command is required"})
                            return
                        raw_args = payload.get("args", [])
                        args = [str(item) for item in raw_args] if isinstance(raw_args, list) else []
                        raw_env = payload.get("env", {})
                        env = {
                            str(key): str(value)
                            for key, value in raw_env.items()
                        } if isinstance(raw_env, dict) else {}
                        workdir = str(payload.get("workdir", "")).strip() or os.path.expanduser("~")
                        try:
                            session = registry.create(command=command, args=args, workdir=workdir, env=env)
                        except Exception as exc:
                            self._send_json(
                                500,
                                {
                                    "running": False,
                                    "exit_code": None,
                                    "error": str(exc) or exc.__class__.__name__,
                                },
                            )
                            return
                        self._send_json(
                            200,
                            {
                                "session_id": session.id,
                                "running": True,
                                "exit_code": None,
                                "error": None,
                            },
                        )
                        return

                    if len(parts) == 3 and parts[0] == "sessions" and parts[2] == "read":
                        session = registry.get(parts[1])
                        if session is None:
                            self._send_json(404, {"error": "Session not found"})
                            return
                        stream = str(payload.get("stream", "stdout"))
                        if stream not in ("stdout", "stderr"):
                            self._send_json(400, {"error": "stream must be stdout or stderr"})
                            return
                        wait_time_ms = int(payload.get("wait_time_ms", 0) or 0)
                        max_bytes = max(1, int(payload.get("max_bytes", 1) or 1))
                        self._send_json(200, session.read(stream=stream, wait_time_ms=wait_time_ms, max_bytes=max_bytes))
                        return

                    if len(parts) == 3 and parts[0] == "sessions" and parts[2] == "stdin":
                        session = registry.get(parts[1])
                        if session is None:
                            self._send_json(404, {"error": "Session not found"})
                            return
                        data_base64 = str(payload.get("data_base64", "") or "")
                        try:
                            data = base64.b64decode(data_base64.encode("ascii")) if data_base64 else b""
                            session.write(data)
                        except Exception as exc:
                            self._send_json(
                                500,
                                {
                                    "success": False,
                                    "running": session.running,
                                    "exit_code": session.exit_code,
                                    "error": str(exc) or exc.__class__.__name__,
                                },
                            )
                            return
                        self._send_json(
                            200,
                            {
                                "success": True,
                                "running": session.running,
                                "exit_code": session.exit_code,
                                "error": None,
                            },
                        )
                        return

                    self._send_json(404, {"error": "Not found"})

                def do_DELETE(self):
                    if not self._require_auth():
                        return
                    parts = self._split_path()
                    if len(parts) == 2 and parts[0] == "sessions":
                        closed = registry.close_session(parts[1])
                        if not closed:
                            self._send_json(404, {"error": "Session not found"})
                            return
                        self._send_json(
                            200,
                            {
                                "success": True,
                                "running": False,
                                "exit_code": None,
                                "error": None,
                            },
                        )
                        return
                    self._send_json(404, {"error": "Not found"})

                def log_message(self, format, *args):
                    return

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
