package me.rerere.rikkahub.data.container

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * PRoot 容器管理器 - 全局单例架构
 *
 * 核心变更：
 * - 全局单一容器实例（非 per-conversation）
 * - 4 状态管理：未初始化 / 初始化中 / 运行中 / 已停止
 * - upper 层全局共享（所有对话共用 pip 包）
 * - 沙箱目录 per-conversation 隔离（通过 workspace bind mount）
 *
 * 状态流转：
 * NotInitialized → Initializing → Running ←→ Stopped
 *                      ↓              ↓
 *                   Error          NotInitialized (销毁后)
 */
@Singleton
class PRootManager(private val context: Context) {

    companion object {
        private const val TAG = "PRootManager"
        private const val DEFAULT_TIMEOUT_MS = 300_000L // 5分钟
        private const val DEFAULT_MAX_MEMORY_MB = 6144  // 6GB for compilation tasks
    }

    // 目录
    private val prootDir: File by lazy { File(context.filesDir, "proot") }
    private val rootfsDir: File by lazy { File(context.filesDir, "rootfs") }
    private val containerDir: File by lazy { File(context.filesDir, "container") }

    // 全局容器状态
    private var globalContainer: ContainerState? = null
    private var currentProcess: Process? = null
    private val processMutex = Mutex()  // 保护 currentProcess 的并发访问

    // 后台进程管理
    private val backgroundProcesses = ConcurrentHashMap<String, BackgroundProcessRecord>()

    // 状态流
    private val _containerState = MutableStateFlow<ContainerStateEnum>(ContainerStateEnum.NotInitialized)
    val containerState: StateFlow<ContainerStateEnum> = _containerState.asStateFlow()

    // 运行状态（用于工具暴露判断）
    val isRunning: Boolean
        get() = _containerState.value == ContainerStateEnum.Running

    /**
     * 获取容器 upper 层目录路径（用于工具安装）
     * 修复：不再依赖 globalContainer，直接检查目录存在性
     */
    fun getUpperDir(): String? {
        // 优先使用 globalContainer 的路径（如果已创建）
        globalContainer?.upperDir?.let { return it }

        // App 重启后 globalContainer 为 null，直接检查目录
        val upperDir = File(containerDir, "upper")
        return if (upperDir.exists()) upperDir.absolutePath else null
    }

    // 自动管理标志
    @Volatile
    private var autoManagementEnabled = false

    // 自动管理的监听器引用（用于移除）
    private var autoManagementObserver: LifecycleEventObserver? = null

    // 当前容器启用设置
    @Volatile
    private var currentEnableContainerRuntime = false

    /**
     * 检查是否需要初始化（资源文件是否存在且有效）
     */
    fun checkInitializationStatus(): Boolean {
        val prootBinary = File(prootDir, "proot")
        val rootfsValid = rootfsDir.exists()
                && rootfsDir.listFiles()?.isNotEmpty() == true
                && File(rootfsDir, "bin/sh").exists()
                && File(rootfsDir, "bin/sh").length() > 0

        if (prootBinary.exists() && rootfsDir.exists()) {
            val shFile = File(rootfsDir, "bin/sh")
            Log.d(TAG, "Checking rootfs: exists=${rootfsDir.exists()}, " +
                    "hasFiles=${rootfsDir.listFiles()?.isNotEmpty()}, " +
                    "shExists=${shFile.exists()}, " +
                    "shSize=${if (shFile.exists()) shFile.length() else 0}")
        }

        return prootBinary.exists() && rootfsValid
    }

    /**
     * 初始化 PRoot 环境（下载/解压资源）
     * 从 NotInitialized → Initializing → Running
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_containerState.value != ContainerStateEnum.NotInitialized) {
                return@withContext Result.failure(IllegalStateException("Already initialized or initializing"))
            }
            
            Log.d(TAG, "Starting container initialization...")
            _containerState.value = ContainerStateEnum.Initializing(0f)
            
            // 创建目录
            Log.d(TAG, "Creating directories: prootDir=$prootDir, rootfsDir=$rootfsDir, containerDir=$containerDir")
            prootDir.mkdirs()
            rootfsDir.mkdirs()
            containerDir.mkdirs()
            
            _containerState.value = ContainerStateEnum.Initializing(0.2f)
            
            // 解压 PRoot 二进制文件
            Log.d(TAG, "Extracting PRoot binary...")
            extractPRootBinary()
            Log.d(TAG, "PRoot binary extracted successfully")
            
            _containerState.value = ContainerStateEnum.Initializing(0.5f)
            
            // 解压 Alpine rootfs
            Log.d(TAG, "Extracting Alpine rootfs...")
            extractAlpineRootfs()
            Log.d(TAG, "Alpine rootfs extracted successfully")
            
            _containerState.value = ContainerStateEnum.Initializing(0.8f)
            
            // 创建全局容器
            Log.d(TAG, "Creating global container...")
            createGlobalContainer()
            Log.d(TAG, "Global container created successfully")
            
            _containerState.value = ContainerStateEnum.Running
            Log.d(TAG, "Container initialization completed successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Container initialization failed", e)
            _containerState.value = ContainerStateEnum.Error(e.message ?: "Unknown error: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /**
     * 启动容器（从 Stopped 到 Running）
     * 如果从未初始化，会先执行初始化
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (_containerState.value) {
                is ContainerStateEnum.Running -> {
                    return@withContext Result.success(Unit) // 已经在运行
                }
                is ContainerStateEnum.Initializing -> {
                    return@withContext Result.failure(IllegalStateException("Already initializing"))
                }
                is ContainerStateEnum.NotInitialized -> {
                    // 需要初始化
                    return@withContext initialize()
                }
                is ContainerStateEnum.Stopped -> {
                    // 从停止状态恢复
                    if (globalContainer == null) {
                        createGlobalContainer()
                    } else {
                        // 确保子目录存在（以防万一目录被删除）
                        val upperDir = File(containerDir, "upper")
                        File(upperDir, "usr/local").apply { mkdirs() }
                        File(upperDir, "usr/lib").apply { mkdirs() }
                        File(upperDir, "root").apply { mkdirs() }
                    }
                    _containerState.value = ContainerStateEnum.Running
                    return@withContext Result.success(Unit)
                }
                is ContainerStateEnum.Error -> {
                    // 错误后重试，需要重新初始化
                    _containerState.value = ContainerStateEnum.NotInitialized
                    return@withContext initialize()
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止容器（Running → Stopped）
     * 保留 upper 层，只 kill 进程
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_containerState.value != ContainerStateEnum.Running) {
                return@withContext Result.failure(IllegalStateException("Container not running"))
            }
            
            // 终止当前进程（使用 Mutex 保护）
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }
            
            _containerState.value = ContainerStateEnum.Stopped
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 销毁容器（任意状态 → NotInitialized）
     * 删除 upper 层，需要重新初始化
     */
    suspend fun destroy(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 停止进程（使用 Mutex 保护）
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }
            
            // 删除 upper 层（保留 rootfs）
            val upperDir = File(containerDir, "upper")
            if (upperDir.exists()) {
                upperDir.deleteRecursively()
            }

            // 删除 work 目录（OverlayFS 工作目录）
            val workDir = File(containerDir, "work")
            if (workDir.exists()) {
                workDir.deleteRecursively()
            }

            // 清理可能残留的开发工具配置文件
            val configDir = File(context.filesDir, "container/config")
            if (configDir.exists()) {
                configDir.deleteRecursively()
            }

            globalContainer = null
            _containerState.value = ContainerStateEnum.NotInitialized
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 执行 Python 代码
     * @param sandboxId 沙箱 ID（通常是 conversationId）
     */
    suspend fun executePython(
        sandboxId: String,
        code: String,
        packages: List<String> = emptyList()
    ): JsonObject {
        if (_containerState.value != ContainerStateEnum.Running) {
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
        
        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            sandboxDir.mkdirs()
            
            // 安装依赖
            if (packages.isNotEmpty()) {
                val pipResult = execInContainer(
                    sandboxId = sandboxId,
                    command = listOf("pip", "install") + packages,
                    timeoutMs = 120_000
                )
                
                if (pipResult.exitCode != 0) {
                    return buildJsonObject {
                        put("success", false)
                        put("error", "Failed to install packages: ${pipResult.stderr}")
                        put("exitCode", pipResult.exitCode)
                        put("stdout", pipResult.stdout)
                        put("stderr", pipResult.stderr)
                    }
                }
            }

            // 使用 Base64 编码写入脚本文件（避免 shell 注入）
            val scriptFile = "/tmp/script_${System.currentTimeMillis()}.py"
            val encodedCode = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val toolEnv = getToolEnvironment()
            val writeResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", "echo '$encodedCode' | base64 -d > $scriptFile"),
                env = toolEnv
            )

            if (writeResult.exitCode != 0) {
                return buildJsonObject {
                    put("success", false)
                    put("error", "Failed to write script: ${writeResult.stderr}")
                    put("exitCode", writeResult.exitCode)
                    put("stdout", writeResult.stdout)
                    put("stderr", writeResult.stderr)
                }
            }

            // 执行脚本
            val execResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("python3", scriptFile),
                env = toolEnv
            )

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)  // 始终包含 stderr，即使为空
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("success", false)
                put("error", "Container execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    /**
     * 执行 Shell 命令
     * @param sandboxId 沙箱 ID（通常是 conversationId）
     */
    suspend fun executeShell(
        sandboxId: String,
        command: String
    ): JsonObject {
        Log.d(TAG, "[ExecuteShell] ========== Command: $command ==========")
        Log.d(TAG, "[ExecuteShell] Container state: ${_containerState.value}")

        if (_containerState.value != ContainerStateEnum.Running) {
            Log.e(TAG, "[ExecuteShell] FAILED: Container not running!")
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }

        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            sandboxDir.mkdirs()

            // 获取已安装工具的环境变量
            val toolEnv = getToolEnvironment()
            Log.d(TAG, "[ExecuteShell] Environment: $toolEnv")

            val execResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                env = toolEnv
            )

            Log.d(TAG, "[ExecuteShell] Result - exitCode=${execResult.exitCode}")
            Log.d(TAG, "[ExecuteShell] stdout: ${execResult.stdout.take(500)}")
            Log.d(TAG, "[ExecuteShell] stderr: ${execResult.stderr.take(500)}")

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ExecuteShell] Exception!", e)
            buildJsonObject {
                put("success", false)
                put("error", "Shell execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    /**
     * 获取已安装的包列表（用于统计展示）
     */
    suspend fun getInstalledPackages(): List<String> = withContext(Dispatchers.IO) {
        try {
            val upperDir = File(containerDir, "upper/usr/local")
            if (!upperDir.exists()) return@withContext emptyList()
            
            // 读取 pip 列表
            val result = execInContainer(
                sandboxId = "system",
                command = listOf("pip", "list", "--format=freeze"),
                timeoutMs = 30_000
            )
            
            if (result.exitCode == 0) {
                result.stdout.lines()
                    .mapNotNull { line ->
                        line.substringBefore("==").takeIf { it.isNotBlank() }
                    }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取容器大小（用于统计展示）
     */
    fun getContainerSize(): Long {
        return calculateDirectorySize(containerDir)
    }

    /**
     * 基础 PATH（Alpine Linux 默认）
     */
    private val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * 获取已安装工具的环境变量（用于容器命令执行）
     * 避免循环依赖，直接检查 upper 层目录
     */
    private fun getToolEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val toolPaths = mutableListOf<String>()
        val upperLocalDir = File(containerDir, "upper/usr/local")

        Log.d(TAG, "[ToolEnv] Checking tools in: ${upperLocalDir.absolutePath}")
        Log.d(TAG, "[ToolEnv] Directory exists: ${upperLocalDir.exists()}")

        // Node.js
        val nodeExists = File(upperLocalDir, "node/bin/node").exists()
        Log.d(TAG, "[ToolEnv] Node.js exists: $nodeExists")
        if (nodeExists) {
            toolPaths.add("/usr/local/node/bin")
            env["NODE_HOME"] = "/usr/local/node"
        }

        // Go
        val goExists = File(upperLocalDir, "go/bin/go").exists()
        Log.d(TAG, "[ToolEnv] Go exists: $goExists")
        if (goExists) {
            toolPaths.add("/usr/local/go/bin")
            env["GOROOT"] = "/usr/local/go"
        }

        // OpenJDK
        val jdkDirs = upperLocalDir.listFiles { f -> f.isDirectory && f.name.startsWith("jdk") }
        Log.d(TAG, "[ToolEnv] JDK dirs: ${jdkDirs?.map { it.name }}")
        if (jdkDirs?.isNotEmpty() == true) {
            toolPaths.add("/usr/local/${jdkDirs[0].name}/bin")
            env["JAVA_HOME"] = "/usr/local/${jdkDirs[0].name}"
        }

        // Rust
        val rustExists = File(upperLocalDir, "rust/bin/rustc").exists()
        Log.d(TAG, "[ToolEnv] Rust exists: $rustExists")
        if (rustExists) {
            toolPaths.add("/usr/local/rust/bin")
            env["RUST_HOME"] = "/usr/local/rust"
        }

        // Python (包括 pip)
        val pythonBinDir = when {
            File(upperLocalDir, "python3/bin/python3").exists() -> "python3/bin"
            File(upperLocalDir, "python/bin/python3").exists() -> "python/bin"
            File(upperLocalDir, "python/bin/python").exists() -> "python/bin"
            else -> null
        }
        Log.d(TAG, "[ToolEnv] Python bin dir: $pythonBinDir")
        if (pythonBinDir != null) {
            val pythonHome = "/usr/local/${pythonBinDir.substringBefore("/")}"
            toolPaths.add("$pythonHome/bin")
            env["PYTHON_HOME"] = pythonHome
            // 确保 pip 能找到
            env["PIP_CACHE_DIR"] = "/tmp/pip-cache"
            Log.d(TAG, "[ToolEnv] Python configured: PYTHON_HOME=$pythonHome, pip available")
        }

        // 组合 PATH：工具路径 + 基础 PATH（确保基础命令可用）
        val finalPath = if (toolPaths.isNotEmpty()) {
            toolPaths.joinToString(":") + ":" + basePath
        } else {
            basePath
        }
        env["PATH"] = finalPath

        Log.d(TAG, "[ToolEnv] Generated PATH: $finalPath")
        Log.d(TAG, "[ToolEnv] Full env: $env")

        return env
    }

    // ==================== Private Methods ====================

    private suspend fun createGlobalContainer() = withContext(Dispatchers.IO) {
        val workDir = File(containerDir, "work").apply { mkdirs() }
        val upperDir = File(containerDir, "upper").apply { mkdirs() }
        
        // 创建 bind mount 所需的子目录
        File(upperDir, "usr/local").apply { mkdirs() }
        File(upperDir, "usr/lib").apply { mkdirs() }
        File(upperDir, "root").apply { mkdirs() }

        globalContainer = ContainerState(
            id = "global",
            workDir = workDir.absolutePath,
            upperDir = upperDir.absolutePath
        )
    }

    private suspend fun execInContainer(
        sandboxId: String,
        command: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Global container not created"
                )

            // 构建 PRoot 命令
            val prootBinary = File(prootDir, "proot").absolutePath
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")

            // 确保沙箱目录存在
            sandboxDir.mkdirs()

            // 检查 termux-exec 是否可用
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val termuxExecLib = File(nativeLibDir, "libtermux-exec.so")
            val hasTermuxExec = termuxExecLib.exists()

            val prootCmd = buildList {
                add(prootBinary)

                // 绑定挂载系统目录（必要）
                add("-b")
                add("/dev")
                add("-b")
                add("/proc")
                add("-b")
                add("/sys")

                // 绑定挂载对话的沙箱目录到 /workspace
                add("-b")
                add("${sandboxDir.absolutePath}:/workspace")

                // 绑定挂载容器的 upper 层到 /usr/local（pip 安装位置）
                add("-b")
                add("${container.upperDir}/usr/local:/usr/local")

                // 绑定挂载 upper 层到 /root（用户级 pip 配置）
                add("-b")
                add("${container.upperDir}/root:/root")

                // 额外绑定挂载 usr/lib 以确保库文件可访问
                // 使用 ! 后缀表示不追踪符号链接，确保覆盖 rootfs 中的同名目录
                add("-b")
                add("${container.upperDir}/usr/lib:/usr/lib!")

                // 根目录使用基础 rootfs（只读）- 必须在 -b 之后
                add("-R")
                add(rootfsDir.absolutePath)

                // 设置工作目录
                add("-w")
                add("/workspace")

                // 启用符号链接修复
                add("--link2symlink")

                // 执行的命令
                addAll(command)
            }

            // 执行命令
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false) // 分离 stdout 和 stderr

            // 设置环境变量
            val processEnv = processBuilder.environment()
            processEnv["HOME"] = "/root"
            processEnv["TMPDIR"] = "/tmp"
            processEnv["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            processEnv["PREFIX"] = "/usr"
            processEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

            // 如果 termux-exec 可用，设置 LD_PRELOAD
            if (hasTermuxExec) {
                processEnv["LD_PRELOAD"] = termuxExecLib.absolutePath
                Log.d(TAG, "Using termux-exec: ${termuxExecLib.absolutePath}")
            }

            // 合并用户传入的环境变量
            processEnv.putAll(env)

            Log.d(TAG, "[ExecInContainer] ========== Executing command ==========")
            Log.d(TAG, "[ExecInContainer] Command: $command")
            Log.d(TAG, "[ExecInContainer] Full prootCmd: $prootCmd")
            Log.d(TAG, "[ExecInContainer] Final env PATH: ${processEnv["PATH"]}")
            Log.d(TAG, "[ExecInContainer] Full env: $processEnv")

            val process = processBuilder.start()

            // 使用 Mutex 保护 currentProcess 赋值
            processMutex.withLock {
                currentProcess = process
            }

            // 并行读取 stdout 和 stderr
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stdout", e)
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stderr", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            // 等待完成或超时
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            // 等待读取线程完成
            stdoutThread.join(1000)
            stderrThread.join(1000)

            if (!finished) {
                process.destroyForcibly()
                cleanupResidualProcesses()
                // 清理 currentProcess
                processMutex.withLock {
                    if (currentProcess == process) {
                        currentProcess = null
                    }
                }
                return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString() + "\nExecution timed out after ${timeoutMs}ms"
                )
            }

            val exitCode = process.exitValue()

            // 使用 Mutex 保护 currentProcess 清理
            processMutex.withLock {
                if (currentProcess == process) {
                    currentProcess = null
                }
            }

            val result = ExecutionResult(
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim()
            )

            Log.d(TAG, "[ExecInContainer] ========== Execution completed ==========")
            Log.d(TAG, "[ExecInContainer] exitCode=$exitCode")
            Log.d(TAG, "[ExecInContainer] stdout: ${result.stdout.take(500)}")
            Log.d(TAG, "[ExecInContainer] stderr: ${result.stderr.take(500)}")

            result
        } catch (e: Exception) {
            // 清理 currentProcess
            processMutex.withLock {
                currentProcess = null
            }
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Execution error: ${e.message}"
            )
        }
    }

    private fun cleanupResidualProcesses() {
        try {
            val process = ProcessBuilder("pkill", "-f", "proot").start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            // 忽略清理错误
        }
    }

    /**
     * 清理 upper 层的临时文件，但保留已安装的开发工具
     * 用于释放空间而不影响开发环境
     */
    suspend fun cleanupUpperLayer(): Result<CleanupResult> = withContext(Dispatchers.IO) {
        try {
            val upperDir = File(containerDir, "upper")
            if (!upperDir.exists()) {
                return@withContext Result.success(CleanupResult(0, 0, emptyList()))
            }

            var totalFreedBytes = 0L
            var totalFilesCleaned = 0
            val cleanedPaths = mutableListOf<String>()

            // 1. 清理 /tmp 目录（临时文件）
            val tmpDir = File(upperDir, "tmp")
            if (tmpDir.exists()) {
                val size = calculateDirectorySize(tmpDir)
                tmpDir.listFiles()?.forEach { it.deleteRecursively() }
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/tmp")
            }

            // 2. 清理 /var/cache 目录
            val cacheDir = File(upperDir, "var/cache")
            if (cacheDir.exists()) {
                val size = calculateDirectorySize(cacheDir)
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/var/cache")
            }

            // 3. 清理 pip 缓存
            val pipCacheDir = File(upperDir, "root/.cache/pip")
            if (pipCacheDir.exists()) {
                val size = calculateDirectorySize(pipCacheDir)
                pipCacheDir.deleteRecursively()
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/root/.cache/pip")
            }

            // 4. 清理 npm 缓存（如果安装了 Node.js）
            val npmCacheDir = File(upperDir, "root/.npm")
            if (npmCacheDir.exists()) {
                val size = calculateDirectorySize(npmCacheDir)
                npmCacheDir.deleteRecursively()
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/root/.npm")
            }

            // 5. 清理 /workspace 中的临时构建文件（但保留源代码）
            val workspaceDir = File(upperDir, "workspace")
            if (workspaceDir.exists()) {
                val buildPatterns = listOf("build", "dist", "target", "node_modules", ".gradle", "__pycache__", "*.pyc", ".pytest_cache")
                workspaceDir.walkTopDown()
                    .filter { it.isDirectory }
                    .filter { dir -> buildPatterns.any { pattern -> dir.name == pattern || dir.name.endsWith(pattern.removePrefix("*.")) } }
                    .forEach { dirToClean ->
                        val size = calculateDirectorySize(dirToClean)
                        dirToClean.deleteRecursively()
                        totalFreedBytes += size
                        totalFilesCleaned++
                        cleanedPaths.add("/workspace/${dirToClean.relativeTo(workspaceDir).path}")
                    }
            }

            Log.d(TAG, "[CleanupUpperLayer] Freed ${totalFreedBytes / 1024 / 1024}MB in $totalFilesCleaned directories")

            Result.success(CleanupResult(
                freedBytes = totalFreedBytes,
                cleanedCount = totalFilesCleaned,
                cleanedPaths = cleanedPaths
            ))
        } catch (e: Exception) {
            Log.e(TAG, "[CleanupUpperLayer] Cleanup failed", e)
            Result.failure(e)
        }
    }

    /**
     * 清理结果数据类
     */
    data class CleanupResult(
        val freedBytes: Long,
        val cleanedCount: Int,
        val cleanedPaths: List<String>
    ) {
        fun formatFreedSize(): String {
            return when {
                freedBytes < 1024 -> "$freedBytes B"
                freedBytes < 1024 * 1024 -> String.format("%.2f KB", freedBytes / 1024.0)
                freedBytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", freedBytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", freedBytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    private suspend fun extractPRootBinary() = withContext(Dispatchers.IO) {
        val prootBinary = File(prootDir, "proot")
        if (!prootBinary.exists()) {
            val arch = getDeviceArchitecture()
            val assetPath = "proot/proot-$arch"
            Log.d(TAG, "Extracting PRoot binary for architecture: $arch from $assetPath")
            
            try {
                context.assets.open(assetPath).use { input ->
                    prootBinary.outputStream().use { output ->
                        val copied = input.copyTo(output)
                        Log.d(TAG, "Copied $copied bytes to ${prootBinary.absolutePath}")
                    }
                }
                // 使用 chmod 命令设置可执行权限（Android 10+ 兼容性更好）
                try {
                    val process = Runtime.getRuntime().exec("chmod 755 ${prootBinary.absolutePath}")
                    val exitCode = process.waitFor()
                    Log.d(TAG, "Set executable permission via chmod, exit code: $exitCode")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set executable permission via chmod, trying setExecutable", e)
                    prootBinary.setExecutable(true)
                }
                Log.d(TAG, "PRoot binary ready, file exists: ${prootBinary.exists()}, size: ${prootBinary.length()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract PRoot binary from $assetPath", e)
                throw RuntimeException("Failed to extract PRoot binary for $arch: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "PRoot binary already exists at ${prootBinary.absolutePath}")
        }
    }

    private suspend fun extractAlpineRootfs() = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists() || rootfsDir.listFiles()?.isEmpty() == true) {
            Log.d(TAG, "Extracting Alpine rootfs to $rootfsDir")
            try {
                // 根据架构选择正确的 rootfs 文件
                val arch = getDeviceArchitecture()
                val rootfsAssetName = when (arch) {
                    "aarch64" -> "rootfs/alpine-minirootfs-3.19.0-aarch64.tar.gz"
                    "armv7a" -> "rootfs/alpine-minirootfs-3.19.0-armhf.tar.gz"
                    "x86_64" -> "rootfs/alpine-minirootfs-3.19.0-x86_64.tar.gz"
                    "i686" -> "rootfs/alpine-minirootfs-3.19.0-x86.tar.gz"
                    else -> "rootfs/alpine-minirootfs-3.19.0-aarch64.tar.gz"
                }

                Log.d(TAG, "Loading rootfs from assets: $rootfsAssetName for architecture: $arch")

                val inputStream = try {
                    context.assets.open(rootfsAssetName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open rootfs asset: $rootfsAssetName", e)
                    // 回退：尝试不带 .gz 后缀的文件名
                    val tarName = rootfsAssetName.replace(".tar.gz", ".tar")
                    try {
                        Log.d(TAG, "Trying: $tarName")
                        context.assets.open(tarName)
                    } catch (e2: Exception) {
                        // 再回退到通用文件名
                        try {
                            Log.d(TAG, "Trying fallback: alpine/alpine-rootfs.tar")
                            context.assets.open("alpine/alpine-rootfs.tar")
                        } catch (e3: Exception) {
                            Log.d(TAG, "Trying fallback: rootfs/alpine-rootfs.tar.gz")
                            context.assets.open("rootfs/alpine-rootfs.tar.gz")
                        }
                    }
                }

                inputStream.use { input ->
                    extractTarGz(input, rootfsDir)
                }
                val fileCount = rootfsDir.walkTopDown().count()
                Log.d(TAG, "Alpine rootfs extracted successfully, total files: $fileCount")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract Alpine rootfs", e)
                throw RuntimeException("Failed to extract Alpine rootfs: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Alpine rootfs already exists at $rootfsDir")
        }
    }

    /**
     * 纯 Java 实现的 tar/tar.gz 解压（不依赖系统 tar 命令）
     * 自动检测是否为 gzip 格式
     */
    private fun extractTarGz(input: java.io.InputStream, targetDir: File) {
        targetDir.mkdirs()

        // 检测是否为 gzip 格式（前两个字节是 0x1f 0x8b）
        val magic = ByteArray(2)
        input.mark(2)
        val magicRead = input.read(magic)
        input.reset()

        val isGzip = magicRead == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        Log.d(TAG, "Archive format detected: ${if (isGzip) "gzip" else "plain tar"}")

        val tarInput = if (isGzip) {
            java.util.zip.GZIPInputStream(input)
        } else {
            input
        }

        tarInput.use { tarIn ->
            extractTar(tarIn, targetDir)
        }
    }

    /**
     * 解压纯 tar 格式
     */
    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        val buffer = ByteArray(8192)

        while (true) {
            // 读取 tar 头部（512 字节）
            val header = ByteArray(512)
            var bytesRead = 0
            while (bytesRead < 512) {
                val read = input.read(header, bytesRead, 512 - bytesRead)
                if (read == -1) break
                bytesRead += read
            }

            if (bytesRead < 512) break // 文件结束

            // 检查是否为空块（tar 结尾）
            if (header.all { it == 0.toByte() }) {
                // 检查下一个块是否也是空的
                val nextHeader = ByteArray(512)
                var nextBytesRead = 0
                while (nextBytesRead < 512) {
                    val read = input.read(nextHeader, nextBytesRead, 512 - nextBytesRead)
                    if (read == -1) break
                    nextBytesRead += read
                }
                if (nextHeader.all { it == 0.toByte() }) break
            }

            // 解析文件名（前 100 字节）
            val nameBytes = header.copyOfRange(0, 100)
            val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
            if (name.isEmpty()) continue

            // 解析文件大小（第 124-135 字节，八进制）
            val sizeBytes = header.copyOfRange(124, 136)
            val sizeStr = String(sizeBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
            val fileSize = if (sizeStr.isEmpty()) 0 else sizeStr.toLong(8)

            // 解析文件类型（第 156 字节）
            val typeFlag = header[156].toInt()

            val file = File(targetDir, name)

            when (typeFlag) {
                '5'.code -> {
                    // 目录
                    file.mkdirs()
                }
                '0'.code, 0 -> {
                    // 普通文件
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        var remaining = fileSize
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    // 设置可执行权限（如果 mode 中有执行位）
                    val modeBytes = header.copyOfRange(100, 108)
                    val mode = String(modeBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    if (mode.isNotEmpty()) {
                        val modeInt = mode.toInt(8)
                        if ((modeInt and 0b001001001) != 0) {
                            file.setExecutable(true)
                        }
                    }
                }
                '2'.code -> {
                    // 符号链接 - 读取链接目标并创建真正的符号链接
                    // 符号链接目标在 tar 头部的 157-256 字节（linkname 字段）
                    val linkNameBytes = header.copyOfRange(157, 257)
                    val linkName = String(linkNameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    if (linkName.isNotEmpty()) {
                        file.parentFile?.mkdirs()
                        try {
                            android.system.Os.symlink(linkName, file.absolutePath)
                            Log.d(TAG, "Created symlink: ${file.absolutePath} -> $linkName")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create symlink: ${file.absolutePath} -> $linkName, falling back to empty file", e)
                            file.createNewFile()
                        }
                    } else {
                        // 如果链接名为空，创建空文件占位
                        file.parentFile?.mkdirs()
                        file.createNewFile()
                    }
                }
                else -> {
                    // 其他类型，跳过内容
                    var remaining = fileSize
                    while (remaining > 0) {
                        val toSkip = minOf(buffer.size.toLong(), remaining).toInt()
                        val skipped = input.read(buffer, 0, toSkip)
                        if (skipped == -1) break
                        remaining -= skipped
                    }
                }
            }

            // 跳过填充到 512 字节边界的字节
            val padding = (512 - (fileSize % 512)) % 512
            var remainingPadding = padding
            while (remainingPadding > 0) {
                val skipped = input.skip(remainingPadding.toLong())
                if (skipped == 0L) break
                remainingPadding -= skipped.toInt()
            }
        }
    }

    private fun getDeviceArchitecture(): String {
        return when (android.system.Os.uname().machine) {
            "aarch64" -> "aarch64"
            "armv7l", "armv8l" -> "armv7a"
            "x86_64" -> "x86_64"
            "i686", "i386" -> "i686"
            else -> "aarch64"
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    // ==================== Auto Management ====================

    /**
     * App 启动时恢复容器状态
     * 如果 upper 目录存在且有效，恢复到 Stopped 状态
     * 调用此方法前应先调用 checkInitializationStatus() 确认已初始化
     *
     * @return 是否成功恢复状态
     */
    suspend fun restoreState(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 检查 rootfs 是否已初始化
            if (!checkInitializationStatus()) {
                Log.d(TAG, "[RestoreState] Rootfs not initialized, cannot restore state")
                return@withContext false
            }

            // 检查 upper 目录是否存在（表示之前有容器被创建过）
            val upperDir = File(containerDir, "upper")
            val workDir = File(containerDir, "work")

            if (upperDir.exists()) {
                Log.d(TAG, "[RestoreState] Found existing upper directory, restoring container state")

                // 确保子目录存在（兼容旧版本升级或部分目录被删除的情况）
                File(upperDir, "usr/local").apply { mkdirs() }
                File(upperDir, "usr/lib").apply { mkdirs() }
                File(upperDir, "root").apply { mkdirs() }

                // 创建 globalContainer（不启动进程）
                if (globalContainer == null) {
                    globalContainer = ContainerState(
                        id = "global",
                        workDir = workDir.absolutePath,
                        upperDir = upperDir.absolutePath
                    )
                    Log.d(TAG, "[RestoreState] Global container recreated")
                }

                // 设置状态为 Stopped（表示容器数据存在但未运行）
                _containerState.value = ContainerStateEnum.Stopped
                Log.d(TAG, "[RestoreState] Container state restored to Stopped")
                true
            } else {
                // rootfs 已初始化但 upper 目录不存在，说明是首次初始化
                // 创建 upper 目录并设置为 Running（兼容旧逻辑）
                Log.d(TAG, "[RestoreState] Rootfs initialized but no upper dir, creating fresh container")
                createGlobalContainer()
                _containerState.value = ContainerStateEnum.Running
                Log.d(TAG, "[RestoreState] Fresh container created and set to Running")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RestoreState] Failed to restore container state", e)
            false
        }
    }

    /**
     * 启用容器自动管理
     * - 应用进入前台时自动启动容器（如果启用且处于 Stopped 状态）
     * - 应用进入后台时自动停止容器
     *
     * @param enableContainerRuntime 用户是否启用了容器运行时功能
     */
    fun enableAutoManagement(enableContainerRuntime: Boolean) {
        // 更新当前设置
        currentEnableContainerRuntime = enableContainerRuntime
        Log.d(TAG, "[AutoManage] enableAutoManagement called with enableContainerRuntime=$enableContainerRuntime, autoManagementEnabled=$autoManagementEnabled")

        // 如果已经添加过 observer，不再重复添加
        if (autoManagementEnabled) {
            Log.d(TAG, "[AutoManage] Auto management already enabled, just updating setting to $enableContainerRuntime")
            return
        }

        autoManagementEnabled = true
        Log.d(TAG, "[AutoManage] Enabling container auto management (enableContainerRuntime=$enableContainerRuntime)")

        // 监听应用生命周期
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                Log.d(TAG, "[AutoManage] Lifecycle event: $event, currentEnableContainerRuntime=$currentEnableContainerRuntime")
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        // 应用进入前台
                        Log.d(TAG, "[AutoManage] App came to foreground, checking if should auto-init...")
                        Log.d(TAG, "[AutoManage] currentEnableContainerRuntime=$currentEnableContainerRuntime, state=${_containerState.value}")
                        if (currentEnableContainerRuntime) {
                            when (_containerState.value) {
                                is ContainerStateEnum.Stopped -> {
                                    Log.d(TAG, "[AutoManage] Auto-starting container from Stopped state")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val result = start()
                                        Log.d(TAG, "[AutoManage] Auto-start result: $result")
                                    }
                                }
                                is ContainerStateEnum.NotInitialized -> {
                                    Log.d(TAG, "[AutoManage] Container not initialized, auto-initializing...")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        Log.d(TAG, "[AutoManage] Calling initialize()...")
                                        val result = initialize()
                                        Log.d(TAG, "[AutoManage] Auto-initialize result: $result")
                                    }
                                }
                                else -> {
                                    Log.d(TAG, "Container state: ${_containerState.value}, no action needed")
                                }
                            }
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        // 应用进入后台
                        Log.d(TAG, "App went to background")
                        if (currentEnableContainerRuntime && _containerState.value == ContainerStateEnum.Running) {
                            Log.d(TAG, "Auto-stopping container")
                            GlobalScope.launch(Dispatchers.IO) {
                                stop()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        lifecycle.addObserver(observer)
        autoManagementObserver = observer
    }

    // ==================== Background Process Management ====================

    /**
     * 后台执行命令（非阻塞）
     *
     * @param sandboxId 沙箱ID
     * @param command 要执行的命令列表
     * @param processId 进程ID
     * @param stdoutFile 标准输出日志文件
     * @param stderrFile 标准错误日志文件
     * @param env 环境变量
     * @return ExecutionResult
     */
    suspend fun execInBackground(
        sandboxId: String,
        command: List<String>,
        processId: String,
        stdoutFile: File,
        stderrFile: File,
        env: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer ?: return@withContext ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Global container not created"
            )

            // 构建 PRoot 命令
            val prootCmd = buildProotCommand(sandboxId, command, env, container)

            Log.d(TAG, "[ExecInBackground] Starting process: $processId")
            Log.d(TAG, "[ExecInBackground] Command: ${command.joinToString(" ")}")

            // 创建进程
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false)

            // 设置环境变量
            val processEnv = processBuilder.environment()
            setupProcessEnvironment(processEnv, env)

            val process = processBuilder.start()

            // 获取进程PID
            val pid = getProcessPid(process)

            // 启动异步线程读取输出并写入文件
            val stdoutJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        stdoutFile.bufferedWriter().use { writer ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ExecInBackground] Error reading stdout for $processId", e)
                }
            }

            val stderrJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        stderrFile.bufferedWriter().use { writer ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ExecInBackground] Error reading stderr for $processId", e)
                }
            }

            // 存储后台进程记录
            val record = BackgroundProcessRecord(
                processId = processId,
                process = process,
                stdoutJob = stdoutJob,
                stderrJob = stderrJob,
                createdAt = System.currentTimeMillis()
            )
            backgroundProcesses[processId] = record

            Log.d(TAG, "[ExecInBackground] Process started: $processId, PID: $pid")

            ExecutionResult(
                exitCode = 0,
                stdout = "Process started with PID: $pid",
                stderr = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[ExecInBackground] Failed to start process: $processId", e)
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to start background process: ${e.message}"
            )
        }
    }

    /**
     * 终止后台进程
     */
    suspend fun killBackgroundProcess(processId: String): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val record = backgroundProcesses[processId]
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Background process not found: $processId"
                )

            Log.d(TAG, "[KillBackgroundProcess] Killing process: $processId")

            // 销毁进程
            record.process?.destroyForcibly()

            // 等待进程结束
            record.process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)

            // 取消输出读取Job
            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()

            // 移除记录
            backgroundProcesses.remove(processId)

            Log.d(TAG, "[KillBackgroundProcess] Process killed: $processId")

            ExecutionResult(
                exitCode = 0,
                stdout = "Process killed successfully",
                stderr = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[KillBackgroundProcess] Error killing process: $processId", e)
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Error: ${e.message}"
            )
        }
    }

    /**
     * 检查后台进程是否还在运行
     */
    fun isBackgroundProcessAlive(processId: String): Boolean {
        val record = backgroundProcesses[processId] ?: return false
        return record.process?.isAlive == true
    }

    /**
     * 获取后台进程的退出码
     */
    fun getBackgroundProcessExitCode(processId: String): Int? {
        val record = backgroundProcesses[processId] ?: return null
        val process = record.process ?: return null
        return if (!process.isAlive) {
            process.exitValue()
        } else {
            null
        }
    }

    /**
     * 清理已结束的后台进程
     */
    suspend fun cleanupFinishedBackgroundProcesses() = withContext(Dispatchers.IO) {
        val finished = mutableListOf<String>()

        backgroundProcesses.keys.forEach { processId ->
            val record = backgroundProcesses[processId]
            if (record?.process?.isAlive == false) {
                finished.add(processId)
                record.stdoutJob?.cancel()
                record.stderrJob?.cancel()
            }
        }

        finished.forEach { backgroundProcesses.remove(it) }

        if (finished.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${finished.size} finished background processes")
        }
    }

    /**
     * 获取Java进程的PID
     */
    private fun getProcessPid(process: Process): Int? {
        return try {
            // 通过反射获取PID（不同Android版本可能不同）
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get process PID", e)
            null
        }
    }

    /**
     * 设置进程环境变量
     */
    private fun setupProcessEnvironment(
        processEnv: MutableMap<String, String>,
        customEnv: Map<String, String>
    ) {
        processEnv["HOME"] = "/root"
        processEnv["TMPDIR"] = "/tmp"
        processEnv["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        processEnv["PREFIX"] = "/usr"
        processEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        // 检查 termux-exec 是否可用
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val termuxExecLib = File(nativeLibDir, "libtermux-exec.so")
        val hasTermuxExec = termuxExecLib.exists()

        if (hasTermuxExec) {
            processEnv["LD_PRELOAD"] = termuxExecLib.absolutePath
        }

        // 合并自定义环境变量
        processEnv.putAll(customEnv)
    }

    /**
     * 构建PRoot命令
     */
    private fun buildProotCommand(
        sandboxId: String,
        command: List<String>,
        env: Map<String, String>,
        container: ContainerState
    ): List<String> {
        val prootBinary = File(prootDir, "proot").absolutePath
        val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")

        return buildList {
            add(prootBinary)

            // 绑定挂载系统目录（必要）
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")

            // 绑定挂载对话的沙箱目录到 /workspace
            add("-b")
            add("${sandboxDir.absolutePath}:/workspace")

            // 绑定挂载容器的 upper 层到 /usr/local（pip 安装位置）
            add("-b")
            add("${container.upperDir}/usr/local:/usr/local")

            // 绑定挂载 upper 层到 /root（用户级 pip 配置）
            add("-b")
            add("${container.upperDir}/root:/root")

            // 额外绑定挂载 usr/lib 以确保库文件可访问
            add("-b")
            add("${container.upperDir}/usr/lib:/usr/lib!")

            // 根目录使用基础 rootfs（只读）- 必须在 -b 之后
            add("-R")
            add(rootfsDir.absolutePath)

            // 设置工作目录
            add("-w")
            add("/workspace")

            // 启用符号链接修复
            add("--link2symlink")

            // 执行的命令
            addAll(command)
        }
    }
}

// ==================== Data Classes ====================

data class ContainerState(
    val id: String,
    val workDir: String,
    val upperDir: String
)

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * 容器状态枚举（4状态）
 */
sealed class ContainerStateEnum {
    object NotInitialized : ContainerStateEnum()
    data class Initializing(val progress: Float) : ContainerStateEnum()
    object Running : ContainerStateEnum()
    object Stopped : ContainerStateEnum()
    data class Error(val message: String) : ContainerStateEnum()
}

/**
 * 后台进程记录（内部使用）
 *
 * @property processId 进程ID
 * @property process Java Process对象
 * @property stdoutJob stdout读取Job
 * @property stderrJob stderr读取Job
 * @property createdAt 创建时间戳
 */
data class BackgroundProcessRecord(
    val processId: String,
    val process: Process?,
    val stdoutJob: kotlinx.coroutines.Job?,
    val stderrJob: kotlinx.coroutines.Job?,
    val createdAt: Long
)
