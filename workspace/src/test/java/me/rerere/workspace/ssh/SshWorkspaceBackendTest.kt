package me.rerere.workspace.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import me.rerere.workspace.WorkspaceStorageArea
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SshWorkspaceBackendTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var remoteDir: File
    private val pool = SshConnectionPool()

    private lateinit var home: File

    // SLOW_USER 的认证会卡住, 直到测试放行, 用来复现建连期间关闭连接池
    private val slowAuthEntered = CountDownLatch(1)
    private val slowAuthRelease = CountDownLatch(1)

    // 收到带 SLOW_REPLY_MARKER 的命令后拖过客户端的 channel 超时才回应 exec 请求,
    // 用来复现服务器已经接受命令、客户端却等回应超时
    private val slowReplyRequests = AtomicInteger()

    // 读取 SLOW_READ_FILE 时服务器一直不回应, 直到测试放行, 用来复现 SFTP 读取卡在网络上
    private val slowReadEntered = CountDownLatch(1)
    private val slowReadRelease = CountDownLatch(1)

    // 打开时服务器迟迟不回应 SFTP subsystem 请求, 直到测试放行
    @Volatile
    private var stallSftpOpen = false
    private val sftpOpens = AtomicInteger()
    private val stalledSftpOpenEntered = CountDownLatch(1)
    private val stalledSftpOpenRelease = CountDownLatch(1)

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
                if (username == SLOW_USER) {
                    slowAuthEntered.countDown()
                    slowAuthRelease.await(10, TimeUnit.SECONDS)
                }
                (username == USER || username == SLOW_USER) && password == PASSWORD
            }
            publickeyAuthenticator = PublickeyAuthenticator { username, _, _ -> username == KEY_USER }
            subsystemFactories = listOf(
                object : SftpSubsystemFactory() {
                    // SSHD 在创建 subsystem 之后才回应请求, 在这里等待就能推迟回应
                    override fun createSubsystem(channel: ChannelSession): Command {
                        sftpOpens.incrementAndGet()
                        if (stallSftpOpen) {
                            stalledSftpOpenEntered.countDown()
                            stalledSftpOpenRelease.await(10, TimeUnit.SECONDS)
                        }
                        return super.createSubsystem(channel)
                    }
                }.apply {
                    addSftpEventListener(object : SftpEventListener {
                        override fun reading(
                            session: ServerSession,
                            remoteHandle: String,
                            localHandle: FileHandle,
                            offset: Long,
                            data: ByteArray,
                            dataOffset: Int,
                            dataLen: Int,
                        ) {
                            if (localHandle.file.fileName.toString() == SLOW_READ_FILE) {
                                slowReadEntered.countDown()
                                slowReadRelease.await(10, TimeUnit.SECONDS)
                            }
                        }
                    })
                },
            )
            commandFactory = CommandFactory { channel, command ->
                // SSHD 在创建命令之后才回应 exec 请求, 在这里等待就能推迟回应
                if (SLOW_REPLY_MARKER in command) {
                    slowReplyRequests.incrementAndGet()
                    Thread.sleep(1_500)
                }
                // 固定 SHELL, 交互式 shell 测试不受开发机登录 shell 影响
                ProcessShellFactory(
                    command,
                    "/usr/bin/env",
                    "HOME=${home.path}",
                    "SHELL=/bin/sh",
                    "/bin/sh",
                    "-c",
                    command
                )
                    .createShell(channel)
            }
            start()
        }

    @After
    fun tearDown() {
        pool.close()
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

    private fun backend(config: SshConnectionConfig = trustedConfig(), pool: SshConnectionPool = this.pool) =
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
    fun commandIsNotRerunWhenExecRequestTimesOut() {
        SshConnectionPool(channelTimeoutMillis = 500).use { slowPool ->
            val backend = backend(pool = slowPool)
            // cat 等着读 stdin, 远端 channel 不清理就会一直留着
            assertThrows(Exception::class.java) {
                backend.executeCommand("cat # $SLOW_REPLY_MARKER", "", 10_000, null)
            }
            assertEquals(1, slowReplyRequests.get())

            // 断开连接才能清理远端 channel, 下一次使用重新连接
            assertFalse(slowPool.isConnected(WORKSPACE_ID))
            assertTrue(waitUntil(5_000) { server.activeSessions.isEmpty() })
            assertEquals("ok", backend.executeCommand("echo ok", "", 10_000, null).stdout.trim())
        }
    }

    @Test
    fun interruptedSftpReadDoesNotBlockLaterOperations() {
        val backend = backend()
        File(remoteDir, SLOW_READ_FILE).writeText("slow")
        File(remoteDir, "fast.txt").writeText("fast")
        assertEquals("fast", backend.readText("fast.txt"))
        try {
            val stuckError = AtomicReference<Throwable>()
            val stuck = thread { stuckError.set(runCatching { backend.readText(SLOW_READ_FILE) }.exceptionOrNull()) }
            assertTrue(slowReadEntered.await(5, TimeUnit.SECONDS))

            // 排在后面等 SFTP 锁的操作也能被打断
            val waiting = thread { runCatching { backend.readText("fast.txt") } }
            Thread.sleep(200)
            waiting.interrupt()
            waiting.join(2_000)
            assertFalse(waiting.isAlive)

            stuck.interrupt()
            stuck.join(2_000)
            assertFalse(stuck.isAlive)
            assertNotNull(stuckError.get())

            // 卡住的回应还没到, 后续操作换了新 channel, 不会读到错位的回应
            assertEquals("fast", backend.readText("fast.txt"))
        } finally {
            slowReadRelease.countDown()
        }
    }

    @Test
    fun interruptedSftpOpenReturnsWithoutRetrying() {
        val backend = backend()
        File(remoteDir, "fast.txt").writeText("fast")
        stallSftpOpen = true
        sftpOpens.set(0)
        try {
            val stuckError = AtomicReference<Throwable>()
            val stuck = thread { stuckError.set(runCatching { backend.readText("fast.txt") }.exceptionOrNull()) }
            assertTrue(stalledSftpOpenEntered.await(5, TimeUnit.SECONDS))
            stuck.interrupt()
            stuck.join(2_000)
            assertFalse(stuck.isAlive)
            assertTrue(stuckError.get() is InterruptedException)
        } finally {
            stallSftpOpen = false
            stalledSftpOpenRelease.countDown()
        }
        // 没有重连重试; 放行后打开的 channel 被丢弃, 下次重新打开
        assertEquals("fast", backend.readText("fast.txt"))
        assertEquals(2, sftpOpens.get())
    }

    @Test
    fun abandonedSftpOpenThatTimesOutClosesConnection() {
        SshConnectionPool(channelTimeoutMillis = 1_000).use { slowPool ->
            val backend = backend(pool = slowPool)
            stallSftpOpen = true
            try {
                val stuck = thread { runCatching { backend.readText("missing.txt") } }
                assertTrue(stalledSftpOpenEntered.await(5, TimeUnit.SECONDS))
                stuck.interrupt()
                stuck.join(2_000)
                assertFalse(stuck.isAlive)

                // 放弃等待后后台等 subsystem 回应超时, 远端 channel 可能已经开着, JSch 又不会发 close, 断开连接清理
                assertTrue(waitUntil(5_000) { !slowPool.isConnected(WORKSPACE_ID) })
            } finally {
                stallSftpOpen = false
                stalledSftpOpenRelease.countDown()
            }
            assertTrue(waitUntil(5_000) { server.activeSessions.isEmpty() })
        }
    }

    @Test
    fun interruptedConnectReturnsAndReleasesConnection() {
        val config = trustedConfig().copy(username = SLOW_USER)
        val stuckError = AtomicReference<Throwable>()
        val stuck = thread { stuckError.set(runCatching { backend(config).readText("missing.txt") }.exceptionOrNull()) }
        assertTrue(slowAuthEntered.await(10, TimeUnit.SECONDS))
        stuck.interrupt()
        stuck.join(2_000)
        assertFalse(stuck.isAlive)
        assertTrue(stuckError.get() is InterruptedException)

        // 放行后建好的连接没有登记进连接池, 直接关掉, 服务器上不会遗留会话
        slowAuthRelease.countDown()
        assertFalse(pool.isConnected(WORKSPACE_ID))
        assertTrue(waitUntil(5_000) { server.activeSessions.isEmpty() })
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

    @Test
    fun idleConnectionIsClosedAndReopenedOnNextUse() {
        SshConnectionPool(idleTimeoutMillis = 200, sweepIntervalMillis = 50).use { idlePool ->
            val backend = backend(pool = idlePool)
            assertEquals("1", backend.executeCommand("echo 1", "", 10_000, null).stdout.trim())
            assertTrue(idlePool.isConnected(WORKSPACE_ID))

            assertTrue(waitUntil(5_000) { !idlePool.isConnected(WORKSPACE_ID) })

            assertEquals("2", backend.executeCommand("echo 2", "", 10_000, null).stdout.trim())
            assertTrue(idlePool.isConnected(WORKSPACE_ID))
        }
    }

    @Test
    fun connectionInUseIsNotClosedAsIdle() {
        SshConnectionPool(idleTimeoutMillis = 100, sweepIntervalMillis = 20).use { idlePool ->
            val result = backend(pool = idlePool)
                .executeCommand("sleep 1; echo done", "", 10_000, null)
            assertEquals(0, result.exitCode)
            assertEquals("done", result.stdout.trim())
        }
    }

    @Test
    fun closedPoolReleasesConnectionsAndSweeperThread() {
        val existingSweepers = sweeperThreads()
        val closingPool = SshConnectionPool(sweepIntervalMillis = 20)
        // 调度器在创建连接池时就启动线程, 找出属于这个连接池的那一个
        val sweeper = (sweeperThreads() - existingSweepers).single()
        val backend = backend(pool = closingPool)
        backend.executeCommand("true", "", 10_000, null)
        assertTrue(closingPool.isConnected(WORKSPACE_ID))

        closingPool.close()

        assertFalse(closingPool.isConnected(WORKSPACE_ID))
        assertThrows(IllegalStateException::class.java) {
            backend.executeCommand("true", "", 10_000, null)
        }
        assertTrue(waitUntil(5_000) { !sweeper.isAlive })
    }

    @Test
    fun connectionCompletedDuringCloseIsReleased() {
        val config = trustedConfig().copy(username = SLOW_USER)
        val closingPool = SshConnectionPool()
        val failure = AtomicReference<Throwable>()
        val worker = thread {
            try {
                backend(config, closingPool).executeCommand("true", "", 10_000, null)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        // 建连卡在认证, 这时连接还没登记进连接池
        assertTrue(slowAuthEntered.await(10, TimeUnit.SECONDS))
        closingPool.close()
        slowAuthRelease.countDown()
        worker.join(10_000)

        assertTrue(failure.get() is IllegalStateException)
        assertFalse(closingPool.isConnected(WORKSPACE_ID))
        // 建好的连接被立即释放, 服务器上不会遗留会话
        assertTrue(waitUntil(5_000) { server.activeSessions.isEmpty() })
    }

    @Test
    fun shellStartsInWorkspaceDirWithRequestedPty() {
        val shell = backend().openShell(columns = 100, rows = 30)
        shell.outputStream.write("pwd; echo \"\$COLUMNS \$LINES \$TERM\"; exit 3\n".toByteArray())
        shell.outputStream.flush()

        // 测试服务器没有真正的 PTY, shell 读完 stdin 里的命令后退出
        val output = shell.inputStream.bufferedReader().readLines()
        assertEquals(listOf(remoteDir.path, "100 30 xterm-256color"), output)
        assertEquals(3, shell.waitFor())
        assertFalse(shell.connectionLost)
        shell.close()
    }

    @Test
    fun openShellKeepsConnectionFromIdleClose() {
        SshConnectionPool(idleTimeoutMillis = 100, sweepIntervalMillis = 20).use { idlePool ->
            val shell = backend(pool = idlePool).openShell(columns = 80, rows = 24)
            Thread.sleep(500)
            assertTrue(idlePool.isConnected(WORKSPACE_ID))

            val reader = shell.inputStream.bufferedReader()
            shell.outputStream.write("echo alive\n".toByteArray())
            shell.outputStream.flush()
            assertEquals("alive", reader.readLine())
            shell.resize(columns = 120, rows = 40)

            // 关闭后挂断远程 shell, 连接回到空闲状态并被断开
            shell.close()
            assertEquals(null, shell.waitFor())
            assertTrue(waitUntil(5_000) { !idlePool.isConnected(WORKSPACE_ID) })
        }
    }

    @Test
    fun shellReportsConnectionLoss() {
        val shell = backend().openShell(columns = 80, rows = 24)
        server.stop(true)

        val exitCode = AtomicReference<Int?>(-2)
        val waiter = thread { exitCode.set(shell.waitFor()) }
        waiter.join(10_000)
        assertFalse(waiter.isAlive)
        assertEquals(null, exitCode.get())
        assertTrue(shell.connectionLost)
        shell.close()
    }

    private fun sweeperThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "ssh-idle-sweeper" && it.isAlive }.toSet()

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private companion object {
        const val SLOW_REPLY_MARKER = "rikkahub-slow-reply"
        const val SLOW_READ_FILE = "slow.txt"
        const val WORKSPACE_ID = "test"
        const val USER = "tester"
        const val PASSWORD = "secret"
        const val KEY_USER = "keyuser"
        const val SLOW_USER = "slowuser"
    }
}
