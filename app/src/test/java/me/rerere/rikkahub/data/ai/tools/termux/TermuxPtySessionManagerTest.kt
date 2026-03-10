package me.rerere.rikkahub.data.ai.tools.termux

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sun.misc.Unsafe

class TermuxPtySessionManagerTest {
    @Test
    fun `bootstrap script should replace legacy server when token file is missing`() {
        assumeTrue(canRunCommand("bash"))
        assumeTrue(canRunCommand("python3"))

        val tempHome = Files.createTempDirectory("termux-pty-bootstrap").toFile()
        val stateDir = File(tempHome, ".rikkahub").apply { mkdirs() }
        val pidFile = File(stateDir, "pty_session_server.pid")
        val tokenFile = File(stateDir, "pty_session_server.token")
        val scriptFile = File(stateDir, "pty_session_server.py")
        val legacyToken = "legacy-token"
        val newToken = "fresh-token"
        val port = reservePort()
        var legacyPid: Long? = null

        try {
            val sessionManager = allocateSessionManager()
            scriptFile.writeText(sessionManager.termuxPtyServerScriptForTest())

            legacyPid = startDetachedPythonServer(
                homeDir = tempHome,
                scriptFile = scriptFile,
                port = port,
                token = legacyToken,
            )
            pidFile.writeText("$legacyPid\n")

            assertTrue(waitForHealth(port = port, token = legacyToken))
            assertFalse(tokenFile.exists())

            val bootstrap = ProcessBuilder("bash", "-s")
                .directory(tempHome)
                .apply {
                    environment()["HOME"] = tempHome.absolutePath
                }
                .start()
            bootstrap.outputStream.bufferedWriter().use { writer ->
                writer.write(
                    sessionManager.buildBootstrapScriptForTest(
                        port = port,
                        token = newToken,
                    )
                )
            }

            assertTrue("bootstrap script timed out", bootstrap.waitFor(20, TimeUnit.SECONDS))
            val stdout = bootstrap.inputStream.bufferedReader().readText()
            val stderr = bootstrap.errorStream.bufferedReader().readText()
            assertEquals(
                "bootstrap failed\nstdout:\n$stdout\nstderr:\n$stderr",
                0,
                bootstrap.exitValue(),
            )

            assertTrue(waitForPidExit(legacyPid))
            val newPid = pidFile.readText().trim().toLong()
            assertNotEquals(legacyPid, newPid)
            assertEquals(newToken, tokenFile.readText().trim())
            assertTrue(waitForHealth(port = port, token = newToken))
            assertFalse(isHealthy(port = port, token = legacyToken))
        } finally {
            legacyPid?.let(::killPid)
            readPid(pidFile)?.let { killPid(it) }
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `bootstrap script should replace orphan server when pid file is missing`() {
        assumeTrue(canRunCommand("bash"))
        assumeTrue(canRunCommand("python3"))

        val tempHome = Files.createTempDirectory("termux-pty-bootstrap-orphan").toFile()
        val stateDir = File(tempHome, ".rikkahub").apply { mkdirs() }
        val pidFile = File(stateDir, "pty_session_server.pid")
        val tokenFile = File(stateDir, "pty_session_server.token")
        val scriptFile = File(stateDir, "pty_session_server.py")
        val legacyToken = "legacy-token"
        val newToken = "fresh-token"
        val port = reservePort()
        var legacyPid: Long? = null

        try {
            val sessionManager = allocateSessionManager()
            scriptFile.writeText(sessionManager.termuxPtyServerScriptForTest())

            legacyPid = startDetachedPythonServer(
                homeDir = tempHome,
                scriptFile = scriptFile,
                port = port,
                token = legacyToken,
            )

            assertTrue(waitForHealth(port = port, token = legacyToken))
            assertFalse(pidFile.exists())
            assertFalse(tokenFile.exists())

            val bootstrap = ProcessBuilder("bash", "-s")
                .directory(tempHome)
                .apply {
                    environment()["HOME"] = tempHome.absolutePath
                }
                .start()
            bootstrap.outputStream.bufferedWriter().use { writer ->
                writer.write(
                    sessionManager.buildBootstrapScriptForTest(
                        port = port,
                        token = newToken,
                    )
                )
            }

            assertTrue("bootstrap script timed out", bootstrap.waitFor(20, TimeUnit.SECONDS))
            val stdout = bootstrap.inputStream.bufferedReader().readText()
            val stderr = bootstrap.errorStream.bufferedReader().readText()
            assertEquals(
                "bootstrap failed\nstdout:\n$stdout\nstderr:\n$stderr",
                0,
                bootstrap.exitValue(),
            )

            assertTrue(waitForPidExit(legacyPid))
            val newPid = pidFile.readText().trim().toLong()
            assertNotEquals(legacyPid, newPid)
            assertEquals(newToken, tokenFile.readText().trim())
            assertTrue(waitForHealth(port = port, token = newToken))
            assertFalse(isHealthy(port = port, token = legacyToken))
        } finally {
            legacyPid?.let(::killPid)
            readPid(pidFile)?.let { killPid(it) }
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `start should wait long enough to capture delayed startup output`() {
        assumeTrue(canRunCommand("bash"))
        assumeTrue(canRunCommand("python3"))

        val localBashPath = resolveCommandPath("bash")
        assumeTrue("bash path could not be resolved", localBashPath != null)

        val tempHome = Files.createTempDirectory("termux-pty-delayed-start").toFile()
        val stateDir = File(tempHome, ".rikkahub").apply { mkdirs() }
        val scriptFile = File(stateDir, "pty_session_server.py")
        val port = reservePort()
        val token = "test-token"
        var serverProcess: Process? = null

        try {
            val sessionManager = allocateSessionManager()
            scriptFile.writeText(
                sessionManager.termuxPtyServerScriptForTest().replace(
                    oldValue = "\"/data/data/com.termux/files/usr/bin/bash\"",
                    newValue = "\"$localBashPath\"",
                )
            )

            serverProcess = ProcessBuilder(
                "python3",
                "-u",
                scriptFile.absolutePath,
                "--port",
                port.toString(),
                "--token",
                token,
            )
                .directory(tempHome)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = tempHome.absolutePath
                }
                .start()

            assertTrue("patched PTY server never became healthy", waitForHealth(port = port, token = token))

            val startResponse = postJson<TermuxPtyStartRequest, TermuxPtyServerResponse>(
                port = port,
                token = token,
                path = "/sessions",
                payload = TermuxPtyStartRequest(
                    command = delayedStartupCommand(),
                    workdir = tempHome.absolutePath,
                    yieldTimeMs = 50L,
                    maxOutputChars = 12_000,
                )
            )

            assertTrue(
                "expected delayed startup output in initial response, got: ${startResponse.output}",
                startResponse.output.contains("boot"),
            )
            assertTrue(
                "expected prompt in initial response, got: ${startResponse.output}",
                startResponse.output.contains("prompt> "),
            )

            startResponse.sessionId?.let { sessionId ->
                deleteJson<TermuxPtyActionResponse>(
                    port = port,
                    token = token,
                    path = "/sessions/$sessionId",
                )
            }
        } finally {
            serverProcess?.let { process ->
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `write should include post input output even when stale startup output is pending`() {
        assumeTrue(canRunCommand("bash"))
        assumeTrue(canRunCommand("python3"))

        val localBashPath = resolveCommandPath("bash")
        assumeTrue("bash path could not be resolved", localBashPath != null)

        val tempHome = Files.createTempDirectory("termux-pty-stale-output").toFile()
        val stateDir = File(tempHome, ".rikkahub").apply { mkdirs() }
        val scriptFile = File(stateDir, "pty_session_server.py")
        val port = reservePort()
        val token = "test-token"
        var serverProcess: Process? = null

        try {
            val sessionManager = allocateSessionManager()
            scriptFile.writeText(
                sessionManager.termuxPtyServerScriptForTest().replace(
                    oldValue = "\"/data/data/com.termux/files/usr/bin/bash\"",
                    newValue = "\"$localBashPath\"",
                )
            )

            serverProcess = ProcessBuilder(
                "python3",
                "-u",
                scriptFile.absolutePath,
                "--port",
                port.toString(),
                "--token",
                token,
            )
                .directory(tempHome)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = tempHome.absolutePath
                }
                .start()

            assertTrue("patched PTY server never became healthy", waitForHealth(port = port, token = token))

            val startResponse = postJson<TermuxPtyStartRequest, TermuxPtyServerResponse>(
                port = port,
                token = token,
                path = "/sessions",
                payload = TermuxPtyStartRequest(
                    command = delayedPromptEchoCommand(),
                    workdir = tempHome.absolutePath,
                    yieldTimeMs = 50L,
                    maxOutputChars = 12_000,
                )
            )
            val sessionId = checkNotNull(startResponse.sessionId) { "session_id missing from start response: $startResponse" }
            assertTrue("expected initial burst in startup output, got: ${startResponse.output}", startResponse.output.contains("boot"))

            Thread.sleep(350)

            val sessions = getJson<TermuxPtySessionListResponse>(
                port = port,
                token = token,
                path = "/sessions",
            )
            val sessionInfo = sessions.sessions.firstOrNull { it.id == sessionId }
            checkNotNull(sessionInfo) { "session $sessionId was not listed" }
            assertTrue(
                "expected stale startup output to remain buffered before write, session=$sessionInfo",
                sessionInfo.pendingOutputChars > 0,
            )

            val writeResponse = postJson<TermuxPtyWriteRequest, TermuxPtyServerResponse>(
                port = port,
                token = token,
                path = "/sessions/$sessionId/stdin",
                payload = TermuxPtyWriteRequest(
                    chars = "hello from test\n",
                    yieldTimeMs = 350L,
                    maxOutputChars = 12_000,
                )
            )

            assertTrue(
                "write response should include post-write output, got: ${writeResponse.output}",
                writeResponse.output.contains("got:hello from test"),
            )
        } finally {
            serverProcess?.let { process ->
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            tempHome.deleteRecursively()
        }
    }

    private fun allocateSessionManager(): TermuxPtySessionManager {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(TermuxPtySessionManager::class.java) as TermuxPtySessionManager
    }

    private fun TermuxPtySessionManager.buildBootstrapScriptForTest(port: Int, token: String): String {
        val method = javaClass.getDeclaredMethod(
            "buildBootstrapScript",
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(this, port, token) as String
    }

    private fun TermuxPtySessionManager.termuxPtyServerScriptForTest(): String {
        val method = javaClass.getDeclaredMethod("termuxPtyServerScript")
        method.isAccessible = true
        return method.invoke(this) as String
    }

    private fun reservePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private fun startDetachedPythonServer(
        homeDir: File,
        scriptFile: File,
        port: Int,
        token: String,
    ): Long {
        val process = ProcessBuilder(
            "bash",
            "-lc",
            "python3 -u '${scriptFile.absolutePath}' --port '$port' --token '$token' >/dev/null 2>&1 < /dev/null & echo \$!",
        )
            .directory(homeDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = homeDir.absolutePath
            }
            .start()
        assertTrue("failed to start legacy server shell", process.waitFor(5, TimeUnit.SECONDS))
        return process.inputStream.bufferedReader().readText().trim().toLong()
    }

    private fun canRunCommand(command: String): Boolean {
        return runCatching {
            ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    private fun resolveCommandPath(command: String): String? {
        val process = runCatching {
            ProcessBuilder("bash", "-lc", "command -v '$command'")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readText().trim().ifBlank { null }
    }

    private fun delayedPromptEchoCommand(): String {
        return """
            stty -echo
            python3 -u -c "import sys,time; print('boot', flush=True); time.sleep(0.2); print('prompt> ', end='', flush=True); line=sys.stdin.readline(); time.sleep(0.2); print('got:' + line.strip(), flush=True)"
        """.trimIndent()
    }

    private fun delayedStartupCommand(): String {
        return """
            stty -echo
            python3 -u -c "import time; time.sleep(0.35); print('boot', flush=True); print('prompt> ', end='', flush=True); time.sleep(1.0)"
        """.trimIndent()
    }

    private fun waitForHealth(
        port: Int,
        token: String,
        timeoutMs: Long = 10_000L,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (isHealthy(port = port, token = token)) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun isHealthy(port: Int, token: String): Boolean {
        return runCatching {
            val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.setRequestProperty("X-RikkaHub-Token", token)
            connection.responseCode == 200
        }.getOrDefault(false)
    }

    private inline fun <reified P : Any, reified T : Any> postJson(
        port: Int,
        token: String,
        path: String,
        payload: P,
    ): T {
        val connection = openConnection(port = port, token = token, path = path, method = "POST")
        connection.doOutput = true
        connection.outputStream.bufferedWriter().use { writer ->
            writer.write(JsonInstant.encodeToString(payload))
        }
        return decodeJsonResponse(connection)
    }

    private inline fun <reified T : Any> getJson(
        port: Int,
        token: String,
        path: String,
    ): T {
        val connection = openConnection(port = port, token = token, path = path, method = "GET")
        return decodeJsonResponse(connection)
    }

    private inline fun <reified T : Any> deleteJson(
        port: Int,
        token: String,
        path: String,
    ): T {
        val connection = openConnection(port = port, token = token, path = path, method = "DELETE")
        return decodeJsonResponse(connection)
    }

    private fun openConnection(
        port: Int,
        token: String,
        path: String,
        method: String,
    ): HttpURLConnection {
        return (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-RikkaHub-Token", token)
        }
    }

    private inline fun <reified T : Any> decodeJsonResponse(connection: HttpURLConnection): T {
        try {
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.readText()
                .orEmpty()
            assertTrue("HTTP ${connection.responseCode} for ${connection.url}\n$body", connection.responseCode in 200..299)
            return JsonInstant.decodeFromString(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForPidExit(
        pid: Long,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!isPidAlive(pid)) return true
            Thread.sleep(100)
        }
        return !isPidAlive(pid)
    }

    private fun readPid(pidFile: File): Long? {
        return pidFile.takeIf(File::exists)?.readText()?.trim()?.toLongOrNull()
    }

    private fun killPid(pid: Long) {
        runCatching {
            ProcessBuilder("kill", "-9", pid.toString())
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun isPidAlive(pid: Long): Boolean {
        return runCatching {
            val process = ProcessBuilder("kill", "-0", pid.toString()).start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }
}
