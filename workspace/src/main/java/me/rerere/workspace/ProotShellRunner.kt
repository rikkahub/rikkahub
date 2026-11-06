package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /** AI 写入该目录时免强制审批 */
    val writable: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        unavailableReason(context)?.let { reason ->
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = reason,
            )
        }
        val process = processBuilder(context, buildCommand(context, EXEC_SCRIPT))
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun startBackground(context: WorkspaceShellContext, id: String): WorkspaceBackgroundCommand {
        unavailableReason(context)?.let { error(it) }
        // 日志放在 Rootfs 的 /tmp 下, 模型可以用 shell 直接 tail/grep; 应用启动时随 /tmp 一起清理
        val dir = File(context.linuxDir, BACKGROUND_DIR.trimStart('/')).apply { mkdirs() }
        val logFile = File(dir, "$id.log")
        val pidFile = File(dir, "$id.pid")
        val process = processBuilder(context, buildCommand(context, BACKGROUND_SCRIPT, "$BACKGROUND_DIR/$id.pid"))
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .redirectInput(File("/dev/null"))
            .start()
        return ProotBackgroundCommand(process, logFile, "$BACKGROUND_DIR/$id.log", pidFile)
    }

    private fun unavailableReason(context: WorkspaceShellContext): String? = when {
        !WorkspaceManager.isUsableRootfs(context.linuxDir) -> "Rootfs is not installed"
        !File(nativeLibraryDir, PROOT_EXEC).isFile ->
            "proot executable not found: ${File(nativeLibraryDir, PROOT_EXEC).absolutePath}"
        !File(nativeLibraryDir, PROOT_LOADER).isFile ->
            "proot loader not found: ${File(nativeLibraryDir, PROOT_LOADER).absolutePath}"
        else -> null
    }

    private fun processBuilder(context: WorkspaceShellContext, command: List<String>): ProcessBuilder {
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                environment()["PROOT_LOADER"] = File(nativeLibraryDir, PROOT_LOADER).absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
    }

    override fun terminalLaunch(context: WorkspaceTerminalContext): WorkspaceTerminalLaunch {
        val shell = WorkspaceManager.rootfsShell(context.linuxDir)
        val args = prootArgs(context.linuxDir, context.filesDir, WORKSPACE_DIR, context.bindMounts)
        args += ROOTFS_ENV
        args += listOf(
            "USER=root",
            "SHELL=$shell",
            shell,
        )

        val env = mutableListOf(
            "PROOT_LOADER=${File(nativeLibraryDir, PROOT_LOADER).absolutePath}",
            "PROOT_TMP_DIR=${context.tempDir.absolutePath}",
            "TMPDIR=${context.tempDir.absolutePath}",
        )
        if (context.shellCompatibilityMode) {
            env += "PROOT_NO_SECCOMP=1"
        }

        return WorkspaceTerminalLaunch(
            executable = File(nativeLibraryDir, PROOT_EXEC).absolutePath,
            cwd = context.filesDir.absolutePath,
            args = args,
            env = env,
        )
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        script: String,
        vararg extraArgs: String,
    ): List<String> {
        val command = mutableListOf(File(nativeLibraryDir, PROOT_EXEC).absolutePath)
        command += prootArgs(context.linuxDir, context.filesDir, context.prootCwd(), context.bindMounts)
        command += ROOTFS_ENV
        command += listOf(
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            WorkspaceManager.rootfsShell(context.linuxDir),
            "-l",
            "-c",
            script,
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        command += extraArgs
        return command
    }

    /** PRoot 自身的参数: Rootfs、工作目录与挂载表, 命令执行和交互式终端共用 */
    private fun prootArgs(
        linuxDir: File,
        filesDir: File,
        cwd: String,
        bindMounts: List<WorkspaceBindMount>,
    ): MutableList<String> {
        val args = mutableListOf(
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            cwd,
            "-b",
            "${filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                args += "-b"
                args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        return args
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
        private const val EXEC_SCRIPT = "cd -- \"\$1\" && eval \"\$2\""

        // 后台命令先把 shell 与 PRoot 的进程号写入 $3, 终止时据此给整组进程发信号, 见 ProotBackgroundCommand
        private const val BACKGROUND_SCRIPT = "echo \"\$\$ \$PPID\" > \"\$3\"; $EXEC_SCRIPT"

        /** Rootfs 内存放后台命令日志与进程号的目录 */
        private const val BACKGROUND_DIR = "/tmp/rikkahub/background"

        /** 清空宿主机环境变量后 Rootfs 内的基础环境 */
        private val ROOTFS_ENV = listOf(
            "/usr/bin/env",
            "-i",
            "HOME=${WorkspaceManager.ROOTFS_HOME_DIR}",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )
    }
}
