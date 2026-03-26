package me.rerere.sandbox

object Pty {
    init {
        System.loadLibrary("sandbox")
    }

    /**
     * Fork a child process with a PTY.
     * @return IntArray of [masterFd, pid], or null on failure.
     */
    external fun nativeExec(
        cmd: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): IntArray?

    external fun nativeSetWindowSize(fd: Int, rows: Int, cols: Int)
    external fun nativeWaitFor(pid: Int): Int
    external fun nativeClose(fd: Int)
    external fun nativeRead(fd: Int, buffer: ByteArray, len: Int): Int
    external fun nativeWrite(fd: Int, data: ByteArray, len: Int): Int
    external fun nativeKill(pid: Int, signal: Int)
}
