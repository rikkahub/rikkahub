package me.rerere.workspace

import java.io.File

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "请先安装 Rootfs",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val script = context.createCommandScript()
        try {
            val process = ProcessBuilder(buildCommand(context, proot, script.rootfsPath))
                .directory(context.filesDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                    environment()["TMPDIR"] = context.tempDir.absolutePath
                }
                .start()

            return process.readResult(context.timeoutMillis)
        } finally {
            script.hostFile.delete()
        }
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
        scriptPath: String,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "-i",
            scriptPath,
        )
        return command
    }

    private fun WorkspaceShellContext.createCommandScript(): ProotCommandScript {
        val tmpDir = File(linuxDir, ROOTFS_TMP_DIR).apply { mkdirs() }
        val file = File(tmpDir, "rikkahub-command-${System.nanoTime()}.sh")
        file.writeText(
            buildString {
                appendLine("#!/bin/bash")
                append(command)
                if (!command.endsWith('\n')) {
                    appendLine()
                }
            }
        )
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
        return ProotCommandScript(
            hostFile = file,
            rootfsPath = "/$ROOTFS_TMP_DIR/${file.name}",
        )
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val WORKSPACE_DIR = "/workspace"
        private const val ROOTFS_TMP_DIR = "tmp"
    }
}

private data class ProotCommandScript(
    val hostFile: File,
    val rootfsPath: String,
)
