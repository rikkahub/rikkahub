package me.rerere.workspace.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import me.rerere.workspace.WorkspaceStorageArea
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class SshWorkspaceBackendTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var remoteDir: File
    private val pool = SshConnectionPool()

    private lateinit var home: File

    @Before
    fun setUp() {
        remoteDir = tempFolder.newFolder("remote").canonicalFile
        // 用空的 HOME 跑命令, 避免开发机上的 shell 配置往 stdout 打印内容
        home = tempFolder.newFolder("home").canonicalFile
        server = startServer(port = 0)
    }

    // 主机密钥存在固定文件里, 重启后服务器出示的还是同一把
    private fun startServer(port: Int): SshServer =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            this.port = port
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tempFolder.root, "host_key").toPath())
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == USER && password == PASSWORD
            }
            publickeyAuthenticator = PublickeyAuthenticator { username, _, _ -> username == KEY_USER }
            subsystemFactories = listOf(SftpSubsystemFactory())
            commandFactory = CommandFactory { channel, command ->
                ProcessShellFactory(command, "/usr/bin/env", "HOME=${home.path}", "/bin/sh", "-c", command)
                    .createShell(channel)
            }
            start()
        }

    @After
    fun tearDown() {
        pool.close(WORKSPACE_ID)
        server.stop(true)
    }

    private fun config(hostKey: SshHostKey? = null) = SshConnectionConfig(
        host = "127.0.0.1",
        port = server.port,
        username = USER,
        authType = SshAuthType.PASSWORD,
        password = PASSWORD,
        remoteDir = remoteDir.path,
        hostKey = hostKey,
    )

    private fun trustedConfig(): SshConnectionConfig {
        val hostKey = assertThrows(SshUnknownHostKeyException::class.java) {
            pool.probe(config())
        }.hostKey
        return config(hostKey)
    }

    private fun backend(config: SshConnectionConfig = trustedConfig()) =
        SshWorkspaceBackend(pool, WORKSPACE_ID, config)

    @Test
    fun unknownHostKeyIsRejectedUntilTrusted() {
        val hostKey = assertThrows(SshUnknownHostKeyException::class.java) {
            pool.probe(config())
        }.hostKey
        assertTrue(hostKey.fingerprint.startsWith("SHA256:"))

        val result = pool.probe(config(hostKey))
        assertEquals(hostKey, result.hostKey)
        assertTrue(result.remoteDirExists)
        assertTrue(result.shell.startsWith("/"))
    }

    @Test
    fun changedHostKeyIsRejected() {
        val forged = SshHostKey(type = "ssh-ed25519", key = Base64.getEncoder().encodeToString(ByteArray(51) { 1 }))
        val error = assertThrows(SshHostKeyChangedException::class.java) {
            pool.probe(config(forged))
        }
        assertEquals(forged, error.expected)
    }

    @Test
    fun wrongPasswordFailsWithoutLeakingCredentials() {
        val config = trustedConfig().copy(password = "wrong")
        val error = assertThrows(java.io.IOException::class.java) { pool.probe(config) }
        assertFalse(error.message.orEmpty().contains("wrong"))
        assertFalse(config.toString().contains("wrong"))
    }

    @Test
    fun privateKeyAuthentication() {
        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val privateKey = ByteArrayOutputStream().also { keyPair.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
        val hostKey = trustedConfig().hostKey
        val config = SshConnectionConfig(
            host = "127.0.0.1",
            port = server.port,
            username = KEY_USER,
            authType = SshAuthType.PRIVATE_KEY,
            privateKey = privateKey,
            remoteDir = remoteDir.path,
            hostKey = hostKey,
        )
        assertEquals(hostKey, pool.probe(config).hostKey)
    }

    @Test
    fun fileOperationsInWorkspaceArea() {
        val backend = backend()

        backend.writeText("notes/a.txt", "hello", overwrite = true)
        assertEquals("hello", File(remoteDir, "notes/a.txt").readText())
        assertEquals("hello", backend.readText("notes/a.txt"))
        assertThrows(IllegalArgumentException::class.java) {
            backend.writeText("notes/a.txt", "again", overwrite = false)
        }

        val entries = backend.listFiles(WorkspaceStorageArea.FILES, "")
        assertEquals(listOf("notes"), entries.map { it.name })
        assertTrue(entries.single().isDirectory)
        assertEquals("notes/a.txt", backend.listFiles(WorkspaceStorageArea.FILES, "notes").single().path)

        val imported = backend.importFile(WorkspaceStorageArea.FILES, "notes", "a.txt", "copy".byteInputStream())
        assertEquals("notes/a (1).txt", imported.path)

        assertEquals(5L, backend.fileSize(WorkspaceStorageArea.FILES, "notes/a.txt"))
        val exported = ByteArrayOutputStream()
        backend.exportFile(WorkspaceStorageArea.FILES, "notes/a.txt", exported)
        assertEquals("hello", exported.toString(Charsets.UTF_8.name()))

        assertThrows(IllegalArgumentException::class.java) {
            backend.listFiles(WorkspaceStorageArea.FILES, "../")
        }
        assertThrows(IllegalArgumentException::class.java) {
            backend.deleteFile(WorkspaceStorageArea.FILES, "notes", recursive = false)
        }
        assertTrue(backend.deleteFile(WorkspaceStorageArea.FILES, "notes", recursive = true))
        assertFalse(File(remoteDir, "notes").exists())
        assertFalse(backend.deleteFile(WorkspaceStorageArea.FILES, "notes", recursive = true))
    }

    @Test
    fun absolutePathsAndSystemArea() {
        val backend = backend()
        val path = "${remoteDir.path}/deep/dir/file.txt"

        val entry = backend.writeAbsoluteText(path, "abc", overwrite = false)
        assertEquals(path, entry.path)
        assertEquals("file.txt", entry.name)
        assertEquals(3L, backend.absoluteFileSize(path))
        val output = ByteArrayOutputStream()
        backend.exportAbsoluteFile(path, output)
        assertEquals("abc", output.toString(Charsets.UTF_8.name()))

        val systemPath = path.removePrefix("/")
        assertEquals(3L, backend.fileSize(WorkspaceStorageArea.LINUX, systemPath))
        assertEquals(null, backend.localAbsoluteFile(path))
    }

    @Test
    fun wildcardCharactersAreLiteral() {
        val backend = backend()
        backend.writeText("a*b.txt", "star", overwrite = true)
        backend.writeText("axb.txt", "x", overwrite = true)

        assertEquals("star", backend.readText("a*b.txt"))
        assertTrue(backend.deleteFile(WorkspaceStorageArea.FILES, "a*b.txt", recursive = false))
        assertTrue(File(remoteDir, "axb.txt").exists())
    }

    @Test
    fun missingWorkspaceDirectoryIsCreatedOnFirstUse() {
        val nested = File(remoteDir, "not/yet")
        val backend = backend(trustedConfig().copy(remoteDir = nested.path))

        assertEquals(emptyList<Any>(), backend.listFiles(WorkspaceStorageArea.FILES, ""))
        assertTrue(nested.isDirectory)
    }

    @Test
    fun executeCommandInWorkingDirectory() {
        val backend = backend()
        File(remoteDir, "sub").mkdirs()

        val pwd = backend.executeCommand("pwd", cwd = "sub", timeoutMillis = 10_000, stdin = null)
        assertEquals(0, pwd.exitCode)
        assertEquals(File(remoteDir, "sub").path, File(pwd.stdout.trim()).canonicalPath)

        val result = backend.executeCommand(
            "printf '%s' \"\$CI\"; echo err >&2; exit 3",
            cwd = "",
            timeoutMillis = 10_000,
            stdin = null,
        )
        assertEquals(3, result.exitCode)
        assertEquals("true", result.stdout)
        assertEquals("err", result.stderr.trim())

        val quoted = backend.executeCommand("echo 'it'\"'\"'s'", cwd = "", timeoutMillis = 10_000, stdin = null)
        assertEquals("it's", quoted.stdout.trim())

        val cat = backend.executeCommand("cat", cwd = "", timeoutMillis = 10_000, stdin = "piped".toByteArray())
        assertEquals("piped", cat.stdout)

        // 没有 stdin 时立即 EOF, cat 不会挂住
        val emptyCat = backend.executeCommand("cat", cwd = "", timeoutMillis = 10_000, stdin = null)
        assertEquals(0, emptyCat.exitCode)
    }

    @Test
    fun commandTimeout() {
        val backend = backend()
        val start = System.currentTimeMillis()
        val result = backend.executeCommand("sleep 10", cwd = "", timeoutMillis = 500, stdin = null)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(System.currentTimeMillis() - start < 5_000)

        // 超时只关掉那个 channel, 连接还能继续用
        assertEquals("ok", backend.executeCommand("echo ok", "", 10_000, null).stdout.trim())
    }

    @Test
    fun reconnectsAfterServerRestart() {
        val config = trustedConfig()
        val backend = backend(config)
        assertEquals("1", backend.executeCommand("echo 1", "", 10_000, null).stdout.trim())

        val port = server.port
        server.stop(true)
        server = startServer(port)

        // 旧连接已断, 读操作会重连后重试
        assertEquals(emptyList<Any>(), backend.listFiles(WorkspaceStorageArea.FILES, ""))
    }

    private companion object {
        const val WORKSPACE_ID = "test"
        const val USER = "tester"
        const val PASSWORD = "secret"
        const val KEY_USER = "keyuser"
    }
}
