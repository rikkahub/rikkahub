package me.rerere.workspace.ssh

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

class SshJumpHostTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var jump: SshServer
    private lateinit var target: SshServer
    private lateinit var remoteDir: File

    @Before
    fun setUp() {
        remoteDir = tempFolder.newFolder("remote").canonicalFile
        jump = startServer("jump", JUMP_USER, JUMP_PASSWORD)
        target = startServer("target", USER, PASSWORD)
    }

    @After
    fun tearDown() {
        jump.stop(true)
        target.stop(true)
    }

    private fun startServer(name: String, user: String, password: String): SshServer {
        val home = tempFolder.newFolder("home-$name").canonicalFile
        return SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tempFolder.root, "host_key_$name").toPath())
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == user && p == password }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            commandFactory = CommandFactory { channel, command ->
                ProcessShellFactory(command, "/usr/bin/env", "HOME=${home.path}", "/bin/sh", "-c", command)
                    .createShell(channel)
            }
            start()
        }
    }

    private fun jumpConfig(hostKey: SshHostKey? = null) = SshJumpHostConfig(
        host = "127.0.0.1",
        port = jump.port,
        username = JUMP_USER,
        authType = SshAuthType.PASSWORD,
        password = JUMP_PASSWORD,
        hostKey = hostKey,
    )

    private fun config(
        hostKey: SshHostKey? = null,
        jumpHost: SshJumpHostConfig? = jumpConfig(),
        port: Int = target.port,
    ) = SshConnectionConfig(
        host = "127.0.0.1",
        port = port,
        username = USER,
        authType = SshAuthType.PASSWORD,
        password = PASSWORD,
        remoteDir = remoteDir.path,
        hostKey = hostKey,
        jumpHost = jumpHost,
    )

    /** 依次确认跳板机和目标主机的密钥 */
    private fun trustedConfig(): SshConnectionConfig {
        val jumpKey = assertThrows(SshUnknownHostKeyException::class.java) {
            SshConnection.probe(config())
        }.also { assertTrue(it.jumpHost) }.hostKey
        val targetKey = assertThrows(SshUnknownHostKeyException::class.java) {
            SshConnection.probe(config(jumpHost = jumpConfig(jumpKey)))
        }.also { assertFalse(it.jumpHost) }.hostKey
        return config(hostKey = targetKey, jumpHost = jumpConfig(jumpKey))
    }

    @Test
    fun hostKeysAreConfirmedSeparatelyForJumpAndTarget() {
        val config = trustedConfig()
        assertNotEquals(config.jumpHost!!.hostKey, config.hostKey)

        val result = SshConnection.probe(config)
        assertEquals(config.hostKey, result.hostKey)
        assertTrue(result.remoteDirExists)
    }

    @Test
    fun changedJumpHostKeyIsRejectedBeforeReachingTarget() {
        val config = trustedConfig()
        val wrongJumpKey = config.hostKey!!
        val error = assertThrows(SshHostKeyChangedException::class.java) {
            SshConnection.probe(config.copy(jumpHost = jumpConfig(wrongJumpKey)))
        }
        assertTrue(error.jumpHost)
        assertTrue(target.activeSessions.isEmpty())
    }

    @Test
    fun commandsAndFilesGoThroughTheTunnel() {
        val connection = SshConnection.open(trustedConfig())
        connection.use {
            val result = it.exec("printf hello", timeoutMillis = 10_000)
            assertEquals(0, result.exitCode)
            assertEquals("hello", result.stdout)

            // 大于隧道窗口的数据, 验证双向流控不会卡住
            val payload = ByteArray(4 * 1024 * 1024) { index -> (index % 251).toByte() }
            it.sftp { sftp -> sftp.put(payload.inputStream(), "${remoteDir.path}/big.bin") }
            assertTrue(payload.contentEquals(File(remoteDir, "big.bin").readBytes()))
            val counted = it.exec("wc -c", timeoutMillis = 30_000, stdin = payload)
            assertEquals(payload.size.toString(), counted.stdout.trim())
        }
    }

    @Test
    fun closingTargetConnectionClosesJumpSession() {
        val connection = SshConnection.open(trustedConfig())
        assertEquals(1, jump.activeSessions.size)
        connection.close()
        assertTrue(waitUntil(5_000) { jump.activeSessions.isEmpty() })
    }

    @Test
    fun lostJumpHostDisconnectsTarget() {
        val connection = SshConnection.open(trustedConfig())
        connection.use {
            jump.stop(true)
            assertTrue(waitUntil(10_000) { !it.isConnected })
        }
    }

    @Test
    fun rejectedTunnelFailsAndReleasesJumpSession() {
        val config = trustedConfig()
        jump.forwardingFilter = RejectAllForwardingFilter.INSTANCE
        val error = assertThrows(IOException::class.java) { SshConnection.probe(config) }
        assertTrue(error.message.orEmpty().contains("tunnel"))
        assertTrue(waitUntil(5_000) { jump.activeSessions.isEmpty() })
    }

    @Test
    fun silentTargetTimesOutInsteadOfHanging() {
        val jumpKey = assertThrows(SshUnknownHostKeyException::class.java) {
            SshConnection.probe(config())
        }.hostKey
        // 接受连接但从不发送 SSH 版本号的服务器
        val accepted = Collections.synchronizedList(mutableListOf<Socket>())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            thread(isDaemon = true) {
                runCatching { while (true) accepted += silent.accept() }
            }
            val started = System.nanoTime()
            assertThrows(IOException::class.java) {
                SshConnection.probe(config(jumpHost = jumpConfig(jumpKey), port = silent.localPort))
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $elapsedMillis ms", elapsedMillis < 30_000)
        }
        accepted.forEach { runCatching { it.close() } }
        assertTrue(waitUntil(5_000) { jump.activeSessions.isEmpty() })
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        const val USER = "tester"
        const val PASSWORD = "secret"
        const val JUMP_USER = "bastion"
        const val JUMP_PASSWORD = "jump-secret"
    }
}
