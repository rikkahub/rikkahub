package me.rerere.rikkahub.data.ai.tools.termux

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sun.misc.Unsafe

@OptIn(ExperimentalEncodingApi::class)
class TermuxMcpStdioServerManagerTest {
    @Test
    fun `health endpoint should expose stdio bridge server version`() {
        assumeTrue(canRunCommand("python3"))

        val tempHome = Files.createTempDirectory("termux-mcp-stdio-health").toFile()
        val stateDir = File(tempHome, ".rikkahub").apply { mkdirs() }
        val scriptFile = File(stateDir, "mcp_stdio_server.py")
        val port = reservePort()
        val token = "test-token"
        var serverProcess: Process? = null

        try {
            val manager = allocateManager()
            scriptFile.writeText(manager.termuxMcpStdioServerScriptForTest())

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

            assertTrue("stdio bridge server never became healthy", waitForHealth(port = port, token = token))

            val health = getJson<TermuxMcpStdioHealthResponse>(
                port = port,
                token = token,
                path = "/health",
            )

            assertTrue(health.ok)
            assertEquals(TERMUX_MCP_STDIO_SERVER_VERSION, health.version)
        } finally {
            serverProcess?.destroyForcibly()
            serverProcess?.waitFor(5, TimeUnit.SECONDS)
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `stdio bridge should proxy stdout stderr and stdin`() {
        assumeTrue(canRunCommand("python3"))

        val pythonPath = resolveCommandPath("python3")
        assumeTrue("python3 path could not be resolved", pythonPath != null)

        val tempHome = Files.createTempDirectory("termux-mcp-stdio-roundtrip").toFile()
        val stateDir = File(tempHome, ".rikkahub").apply { mkdirs() }
        val scriptFile = File(stateDir, "mcp_stdio_server.py")
        val port = reservePort()
        val token = "test-token"
        var serverProcess: Process? = null

        try {
            val manager = allocateManager()
            scriptFile.writeText(manager.termuxMcpStdioServerScriptForTest())

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

            assertTrue("stdio bridge server never became healthy", waitForHealth(port = port, token = token))

            val startResponse = postJson<TermuxMcpStdioStartRequest, TermuxMcpStdioStartResponse>(
                port = port,
                token = token,
                path = "/sessions",
                payload = TermuxMcpStdioStartRequest(
                    command = pythonPath!!,
                    args = listOf("-u", "-c", echoServerScript()),
                    workdir = tempHome.absolutePath,
                    env = emptyMap(),
                )
            )

            val sessionId = checkNotNull(startResponse.sessionId) {
                "session_id missing from start response: $startResponse"
            }

            val stdoutReady = readUntilContains(
                port = port,
                token = token,
                sessionId = sessionId,
                stream = TermuxMcpStdioStream.Stdout,
                expected = "ready",
            )
            assertTrue(stdoutReady.contains("ready"))

            val stderrReady = readUntilContains(
                port = port,
                token = token,
                sessionId = sessionId,
                stream = TermuxMcpStdioStream.Stderr,
                expected = "warn",
            )
            assertTrue(stderrReady.contains("warn"))

            val writeResponse = postJson<TermuxMcpStdioWriteRequest, TermuxMcpStdioActionResponse>(
                port = port,
                token = token,
                path = "/sessions/$sessionId/stdin",
                payload = TermuxMcpStdioWriteRequest(
                    dataBase64 = Base64.encode("ping\n".encodeToByteArray())
                )
            )
            assertTrue(writeResponse.success)

            val stdoutEcho = readUntilContains(
                port = port,
                token = token,
                sessionId = sessionId,
                stream = TermuxMcpStdioStream.Stdout,
                expected = "echo:ping",
            )
            assertTrue(stdoutEcho.contains("echo:ping"))

            val closeResponse = deleteJson<TermuxMcpStdioActionResponse>(
                port = port,
                token = token,
                path = "/sessions/$sessionId",
            )
            assertTrue(closeResponse.success)
        } finally {
            serverProcess?.destroyForcibly()
            serverProcess?.waitFor(5, TimeUnit.SECONDS)
            tempHome.deleteRecursively()
        }
    }

    private fun allocateManager(): TermuxMcpStdioServerManager {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(TermuxMcpStdioServerManager::class.java) as TermuxMcpStdioServerManager
    }

    private fun TermuxMcpStdioServerManager.termuxMcpStdioServerScriptForTest(): String {
        val method = javaClass.getDeclaredMethod("termuxMcpStdioServerScript")
        method.isAccessible = true
        return method.invoke(this) as String
    }

    private fun echoServerScript(): String {
        return """
            import sys
            print("ready", flush=True)
            print("warn", file=sys.stderr, flush=True)
            line = sys.stdin.readline()
            print("echo:" + line.strip(), flush=True)
        """.trimIndent()
    }

    private fun readUntilContains(
        port: Int,
        token: String,
        sessionId: String,
        stream: TermuxMcpStdioStream,
        expected: String,
        timeoutMs: Long = 5_000L,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val builder = StringBuilder()
        while (System.nanoTime() < deadline) {
            val response = postJson<TermuxMcpStdioReadRequest, TermuxMcpStdioReadResponse>(
                port = port,
                token = token,
                path = "/sessions/$sessionId/read",
                payload = TermuxMcpStdioReadRequest(
                    stream = stream.wireName,
                    waitTimeMs = 250L,
                    maxBytes = 8_192,
                )
            )
            if (response.dataBase64.isNotBlank()) {
                builder.append(Base64.decode(response.dataBase64).decodeToString())
            }
            if (builder.contains(expected)) {
                return builder.toString()
            }
            if (response.eof) {
                break
            }
        }
        return builder.toString()
    }

    private fun reservePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
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
}
