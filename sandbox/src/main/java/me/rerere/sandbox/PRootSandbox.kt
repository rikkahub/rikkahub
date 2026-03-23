package me.rerere.sandbox

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class PRootSandbox(private val context: Context) {
    fun resolveBinaries(): SandboxBinaries {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = nativeLibDir.resolve(LIB_PROOT)
        val prootUserland = nativeLibDir.resolve(LIB_PROOT_USERLAND)
        val loader = nativeLibDir.resolve(LIB_PROOT_LOADER)
        val loader32 = nativeLibDir.resolve(LIB_PROOT_LOADER_32).takeIf(File::exists)

        require(proot.exists()) { "Missing PRoot binary: ${proot.absolutePath}" }
        require(prootUserland.exists()) { "Missing PRoot userland binary: ${prootUserland.absolutePath}" }
        require(loader.exists()) { "Missing PRoot loader: ${loader.absolutePath}" }

        return SandboxBinaries(
            proot = proot,
            prootUserland = prootUserland,
            loader = loader,
            loader32 = loader32,
        )
    }

    fun buildCommand(
        config: SandboxConfig,
        guestCommand: List<String>,
    ): List<String> {
        return SandboxCommandBuilder.build(resolveBinaries(), config, guestCommand)
    }

    fun start(
        config: SandboxConfig,
        guestCommand: List<String>,
    ): Process {
        val binaries = resolveBinaries()
        val processBuilder = ProcessBuilder(SandboxCommandBuilder.build(binaries, config, guestCommand))
        processBuilder.directory(resolveLaunchDirectory(config))
        processBuilder.environment().apply {
            put("PROOT_LOADER", binaries.loader.absolutePath)
            binaries.loader32?.let { put("PROOT_LOADER_32", it.absolutePath) }
            put("PROOT_TMP_DIR", ensureProotTmpDir(config).absolutePath)
            putAll(config.hostEnvironment)
        }
        return processBuilder.start()
    }

    fun execute(
        config: SandboxConfig,
        guestCommand: List<String>,
        timeoutMillis: Long? = null,
    ): SandboxExecutionResult {
        val process = start(config, guestCommand)
        val stdoutReader = StreamCollector(process.inputStream)
        val stderrReader = StreamCollector(process.errorStream)
        stdoutReader.start()
        stderrReader.start()

        val timedOut = if (timeoutMillis == null) {
            process.waitFor()
            false
        } else {
            !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        if (timedOut) {
            process.destroy()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
            }
        }

        stdoutReader.join()
        stderrReader.join()

        return SandboxExecutionResult(
            command = buildCommand(config, guestCommand),
            exitCode = process.exitValue(),
            stdout = stdoutReader.output(),
            stderr = stderrReader.output(),
            timedOut = timedOut,
        )
    }

    fun startSession(
        config: SandboxConfig,
        guestCommand: List<String>,
    ): SandboxSession {
        return SandboxSession(start(config, guestCommand))
    }

    fun startShellSession(
        config: SandboxConfig,
        shell: String = "/bin/sh",
    ): SandboxSession {
        return startSession(
            config = config,
            guestCommand = listOf(shell, "-l"),
        )
    }

    fun executeShell(
        config: SandboxConfig,
        script: String,
        shell: String = "/bin/sh",
        timeoutMillis: Long? = null,
    ): SandboxExecutionResult {
        return execute(
            config = config,
            guestCommand = listOf(shell, "-lc", script),
            timeoutMillis = timeoutMillis,
        )
    }

    private fun ensureProotTmpDir(config: SandboxConfig): File {
        val dir = config.prootTmpDir ?: File(context.cacheDir, "sandbox/proot-tmp")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        require(dir.isDirectory) { "Failed to create PRoot tmp dir: ${dir.absolutePath}" }
        return dir
    }

    private fun resolveLaunchDirectory(config: SandboxConfig): File {
        val preferred = config.launchDirectory ?: context.filesDir
        if (!preferred.exists()) {
            preferred.mkdirs()
        }
        require(preferred.isDirectory) { "Launch directory is not a directory: ${preferred.absolutePath}" }
        return preferred
    }

    private companion object {
        const val LIB_PROOT = "libproot.so"
        const val LIB_PROOT_USERLAND = "libproot-userland.so"
        const val LIB_PROOT_LOADER = "libproot-loader.so"
        const val LIB_PROOT_LOADER_32 = "libproot-loader32.so"
    }
}
