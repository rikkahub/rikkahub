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

class TermuxPtySessionManager(
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    private val termuxCommandManager: TermuxCommandManager,
) {
    private val ensureServerMutex = Mutex()
    @Volatile
    private var serverToken: String? = null

    suspend fun startSession(
        command: String,
        workdir: String,
        yieldTimeMs: Long,
        maxOutputChars: Int,
        cols: Int = TERMUX_PTY_DEFAULT_COLUMNS,
        rows: Int = TERMUX_PTY_DEFAULT_ROWS,
    ): TermuxPtyServerResponse {
        ensureServerRunning()
        return postJson(
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
    }

    suspend fun writeStdin(
        sessionId: String,
        chars: String,
        yieldTimeMs: Long,
        maxOutputChars: Int,
    ): TermuxPtyServerResponse {
        ensureServerRunning()
        return postJson(
            path = "/sessions/$sessionId/stdin",
            payload = TermuxPtyWriteRequest(
                chars = chars,
                yieldTimeMs = yieldTimeMs.coerceAtLeast(0L),
                maxOutputChars = maxOutputChars.coerceAtLeast(256),
            )
        )
    }

    private suspend fun ensureServerRunning() {
        ensureServerMutex.withLock {
            val existingToken = serverToken
            if (existingToken != null && ping(existingToken)) return

            val token = UUID.randomUUID().toString()
            val result = termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    workdir = TERMUX_HOME_PATH,
                    stdin = buildBootstrapScript(port = TERMUX_PTY_SERVER_PORT, token = token),
                    background = true,
                    timeoutMs = 20_000L,
                    label = "RikkaHub PTY session server",
                    description = "Start interactive shell session server",
                )
            )

            if (result.exitCode != 0 || result.errCode != null || result.timedOut) {
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
                        result.errCode?.let {
                            if (isNotBlank()) append('\n')
                            append("Err code: $it")
                        }
                    }
                }
                error(message.ifBlank { "Failed to start PTY session server in Termux." })
            }

            if (!ping(token)) {
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
        }
    }

    private suspend fun ping(token: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(baseUrl(path = "/health"))
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
        path: String,
        payload: T,
    ): TermuxPtyServerResponse {
        val token = serverToken ?: error("PTY session server token is missing")
        return withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(payload)
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(baseUrl(path))
                .header(TOKEN_HEADER, token)
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val parsed = body.takeIf { it.isNotBlank() }?.let {
                        runCatching { json.decodeFromString<TermuxPtyServerResponse>(it) }.getOrNull()
                    }
                    throw IOException(
                        parsed?.error ?: "PTY server request failed with HTTP ${response.code}"
                    )
                }
                json.decodeFromString(body)
            }
        }
    }

    private fun baseUrl(path: String): String {
        return "http://127.0.0.1:$TERMUX_PTY_SERVER_PORT$path"
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
            appendLine("cat > \"${'$'}SCRIPT_PATH\" <<'PY'")
            appendLine(termuxPtyServerScript().trim())
            appendLine("PY")
            appendLine()
            appendLine("if [ -f \"${'$'}PID_FILE\" ]; then")
            appendLine("  OLD_PID=\"$(cat \"${'$'}PID_FILE\" 2>/dev/null || true)\"")
            appendLine("  if [ -n \"${'$'}OLD_PID\" ] && kill -0 \"${'$'}OLD_PID\" 2>/dev/null; then")
            appendLine("    kill \"${'$'}OLD_PID\" 2>/dev/null || true")
            appendLine("    sleep 0.2")
            appendLine("    if kill -0 \"${'$'}OLD_PID\" 2>/dev/null; then")
            appendLine("      kill -9 \"${'$'}OLD_PID\" 2>/dev/null || true")
            appendLine("      sleep 0.2")
            appendLine("    fi")
            appendLine("  fi")
            appendLine("  rm -f \"${'$'}PID_FILE\"")
            appendLine("fi")
            appendLine()
            appendLine("nohup python3 -u \"${'$'}SCRIPT_PATH\" --port \"${'$'}PORT\" --token \"${'$'}TOKEN\" >/dev/null 2>&1 < /dev/null &")
            appendLine("echo \"${'$'}!\" > \"${'$'}PID_FILE\"")
            appendLine()
            appendLine("python3 - \"${'$'}PORT\" \"${'$'}TOKEN\" <<'PY'")
            appendLine("import json")
            appendLine("import sys")
            appendLine("import time")
            appendLine("import urllib.request")
            appendLine()
            appendLine("port = int(sys.argv[1])")
            appendLine("token = sys.argv[2]")
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
            appendLine("sys.exit(1)")
            appendLine("PY")
        }
    }

    private fun termuxPtyServerScript(): String {
        return """
            #!/usr/bin/env python3
            import argparse
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
                    self.last_access = time.time()
                    self.lock = threading.Lock()
                    self.condition = threading.Condition(self.lock)
                    self._start()

                def _start(self):
                    pid, master_fd = pty.fork()
                    if pid == 0:
                        try:
                            os.chdir(self.workdir)
                        except Exception:
                            pass
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
                            text = data.decode("utf-8", errors="replace")
                            with self.condition:
                                self.pending_output += text
                                self.last_access = time.time()
                                self.condition.notify_all()
                        except OSError as exc:
                            if exc.errno in (errno.EIO, errno.EBADF):
                                break
                            with self.condition:
                                self.pending_output += "\n[PTY read error] {}\n".format(exc)
                                self.condition.notify_all()
                            break
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
                    encoded = chars.encode("utf-8")
                    with self.condition:
                        if not self.running:
                            raise RuntimeError("Session is no longer running")
                    os.write(self.master_fd, encoded)
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
                        truncated = bool(self.pending_output)
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

            class Handler(BaseHTTPRequestHandler):
                server_version = "RikkaHubPTY/1.0"

                def do_GET(self):
                    if not self._authorize():
                        return
                    if self.path == "/health":
                        self._respond(200, {"ok": True})
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

                server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
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
