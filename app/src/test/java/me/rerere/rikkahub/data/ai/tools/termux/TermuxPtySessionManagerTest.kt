package me.rerere.rikkahub.data.ai.tools.termux

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
