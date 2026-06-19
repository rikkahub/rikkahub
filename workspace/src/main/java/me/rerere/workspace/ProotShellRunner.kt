package me.rerere.workspace

import java.io.File

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val precheck = precheck(context) ?: return startProcess(context).readResult(context.timeoutMillis)
        return precheck
    }

    override fun startShellRun(
        context: WorkspaceShellContext,
        outputFile: File,
        sizeCapBytes: Long,
    ): ShellRunHandle {
        // A precheck failure (no rootfs / missing proot binary) has no process to own; surface it as a
        // pre-resolved handle so the caller's await() yields the SAME WorkspaceCommandResult execute()
        // would have. Otherwise hand back a live process-owning handle.
        precheck(context)?.let { return PreResolvedShellRunHandle(it) }
        return ProcessShellRunHandle.start(startProcess(context), context.timeoutMillis, outputFile, sizeCapBytes)
    }

    /**
     * The shared rootfs / binary preconditions. Returns a terminal [WorkspaceCommandResult] (exit 127)
     * when the run cannot start, or null when it is safe to spawn the process. Side effects (tempDir
     * mkdirs, rootfs patch) run only on the success path, exactly as the old [execute] did.
     */
    private fun precheck(context: WorkspaceShellContext): WorkspaceCommandResult? {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
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
        return null
    }

    private fun startProcess(context: WorkspaceShellContext): Process {
        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
    }

    // internal (not private) so the positional-arg escaping form is unit-testable without a rootfs.
    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
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
            "${context.filesDir.absolutePath}:${WorkspaceCwdPolicy.WORKSPACE_DIR}",
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
            // --noprofile --norc, NOT -l: this is a PROGRAMMATIC shell, not the interactive terminal
            // (which keeps login startup). A login shell sources the rootfs profile/rc BEFORE the -c
            // script, and the rootfs is shell-mutable — a poisoned profile (`cd(){ exit 0; }`,
            // `trap(){ exit 0; }`) could shadow the wrapper's own verbs and corrupt the reported exit
            // code before the command even runs. Starting startup-free means the -c script runs in a
            // pristine shell (the fixed env above is the whole environment), so the cwd-capture trap is
            // registered, and the command's exit code is reported, deterministically.
            "--noprofile",
            "--norc",
            "-c",
            // The command is passed as a positional arg ($2) to avoid any escaping; `eval "$2"`
            // evaluates the command text exactly once, equivalent to `bash -c "$cmd"`. Inlining the
            // command into this -c script (the old form) let bash expand $ and backticks at parse
            // time, before eval — corrupting commands with shell metacharacters.
            cScript(context),
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    // The `-c` script body. The base form runs the user command (raw, eval'd exactly once) at the
    // resolved cwd. When a [WorkspaceShellContext.cwdCaptureToken] is set, the final cwd is captured
    // from an EXIT trap installed BEFORE the user command — and the exit code is NEVER rewritten.
    //
    // Why an INLINE EXIT trap and no exit rewrite: any capture running in the same shell after
    // `eval "$2"` is exposed to whatever the user command set up — `exit()`/`printf()`/`pwd()`/`builtin()`
    // functions, `enable -n`, a replaced `trap ... EXIT`, `set -e`. The process therefore exits with the
    // user command's NATURAL status (the last statement is `cd -- "$1" && eval "$2"`); nothing in this
    // script ever calls `exit`, so there is no exit code for any of those to corrupt — a command that
    // genuinely exits N (incl. via its own `trap`) truthfully reports N. The combined invocation is also
    // startup-free (`--noprofile --norc`, see buildCommand), so no rootfs profile can shadow the
    // wrapper's verbs BEFORE this script runs. The cwd marker is BEST-EFFORT only.
    internal fun cScript(context: WorkspaceShellContext): String {
        val base = "cd -- \"\$1\" && eval \"\$2\""
        val token = context.cwdCaptureToken
        if (token.isNullOrEmpty()) return base
        // The EXIT trap is INLINE (no named handler the user command could redefine) and is wrapped in
        // `! { ...; }`: the `!` reserved word — which cannot be shadowed by a function or alias —
        // SUPPRESSES `errexit` for the group, so a failing capture verb (e.g. `enable -n printf` under
        // `set -e`, or a shadowed `builtin`) can NEVER flip the process exit code; it just yields no
        // marker (=> keep the prior cwd). `builtin` bypasses user-defined printf/pwd; `2>/dev/null`
        // suppresses any "not a builtin" stderr. Single-quoted so the body runs at trap-FIRE time
        // (capturing the final pwd); inner double quotes avoid single-quote nesting; the token is safe.
        return "trap '! { builtin printf \"%s%s\\n\" \"$token\" \"\$(builtin pwd -P)\"; } 2>/dev/null' EXIT" +
            "\n$base"
    }

    // The cwd -> PRoot `-w` mapping is NOT a private copy here: it delegates to the ONE central policy
    // so the runner's `-w` and the sideload terminal's `-w` derive from the IDENTICAL function (issue
    // #282 W-I6). [context.cwd] is the already-resolved FILES-relative path the manager produced.
    private fun WorkspaceShellContext.prootCwd(): String = WorkspaceCwdPolicy.toShellPath(cwd)

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
    }
}
