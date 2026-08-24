package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * rootfs 内常见 shell 可执行文件的绝对路径 (以 rootfs 根为 /).
 * 顺序即优先级: 优先功能完整的 bash, 其次 busybox 系 ash/dash, 最后退回 POSIX sh.
 */
val ROOTFS_SHELL_CANDIDATES: List<String> = listOf(
    "/bin/bash",
    "/usr/bin/bash",
    "/bin/ash",
    "/bin/dash",
    "/bin/sh",
    "/usr/bin/sh",
)

// /etc/passwd 中可采信的登录 shell 名 (Bourne 系): 工具执行链路依赖 POSIX 语法
// (cd -- "$1" && eval "$2"), fish/csh 等非 Bourne 系跑不了; nologin/false 启动即退
private val BOURNE_SHELL_NAMES = setOf("sh", "ash", "dash", "bash", "zsh", "ksh", "mksh")

private const val MAX_SYMLINK_DEPTH = 16

/**
 * 探测已解压的 rootfs 目录 [linuxDir] 中可用的 shell, 返回其 rootfs 内绝对路径 (如 "/bin/ash"),
 * 无可用 shell 时返回 null.
 *
 * 优先采用 /etc/passwd 里 root 用户的登录 shell (发行版钦定, 如 Alpine 的 /bin/ash),
 * 不可用时回退到 [ROOTFS_SHELL_CANDIDATES] 逐个探测.
 *
 * rootfs 里的 /bin/sh 往往是指向 bash/busybox 的符号链接, 且链接目标常是**绝对路径** (如 /bin/busybox).
 * 直接用 File.isFile 判断会让 JVM 跟随绝对链接到**宿主机**的同名路径 (通常不存在), 把装好的 rootfs
 * 误判为不可用. 这里在 rootfs 内部手动解析符号链接 —— 绝对链接重新以 [linuxDir] 为根解析 —— 再判断
 * 目标是否为常规文件.
 */
fun detectRootfsShell(linuxDir: File): String? {
    if (!linuxDir.isDirectory) return null
    passwdRootShell(linuxDir)?.let { shell ->
        if (rootfsRegularFile(linuxDir, shell) != null) return shell
    }
    return ROOTFS_SHELL_CANDIDATES.firstOrNull { candidate ->
        rootfsRegularFile(linuxDir, candidate) != null
    }
}

/**
 * 从 rootfs 的 /etc/passwd 读取 root 用户的登录 shell 字段.
 * 不能按 "root:x:0:0::/root:" 这类固定前缀匹配 —— GECOS/home 字段各发行版不同
 * (Alpine 为 "root:x:0:0:root:/root:/bin/ash"), 这里按冒号取第 7 个字段.
 * 字段缺失、非绝对路径、或不在 Bourne 系白名单时返回 null, 由调用方回退候选列表.
 */
private fun passwdRootShell(linuxDir: File): String? {
    val passwd = rootfsRegularFile(linuxDir, "/etc/passwd") ?: return null
    val rootLine = runCatching {
        passwd.useLines { lines -> lines.firstOrNull { it.startsWith("root:") } }
    }.getOrNull() ?: return null
    val shell = rootLine.split(':').getOrNull(6)?.trim() ?: return null
    if (!shell.startsWith("/")) return null
    if (shell.substringAfterLast('/') !in BOURNE_SHELL_NAMES) return null
    return shell
}

/**
 * 把 rootfs 内路径 [path] 解析成宿主机上的常规文件, 跟随 rootfs 内部符号链接;
 * 绝对符号链接目标以 rootfs 根 ([linuxDir]) 为根重新解析. 超过深度上限或解析不到常规文件时返回 null.
 */
private fun rootfsRegularFile(
    linuxDir: File,
    path: String,
    depth: Int = 0,
): File? {
    if (depth > MAX_SYMLINK_DEPTH) return null
    val normalized = normalizeRootfsPath(path) ?: return null
    val file = File(linuxDir, normalized)
    val nioPath = file.toPath()
    if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) return null
    if (Files.isSymbolicLink(nioPath)) {
        val link = runCatching { Files.readSymbolicLink(nioPath).toString() }
            .getOrNull()
            ?.replace('\\', '/')
            ?: return null
        val next = if (link.startsWith("/")) {
            link
        } else {
            val parent = normalized.substringBeforeLast('/', "")
            if (parent.isEmpty()) link else "$parent/$link"
        }
        return rootfsRegularFile(linuxDir, next, depth + 1)
    }
    return file.takeIf { it.isFile }
}

/**
 * 规范化 rootfs 内路径: 丢弃 . 与空段, 解析 .. 段 (钳制在 rootfs 根内, 越界即忽略),
 * 绝对路径按 rootfs 根解析. 结果为空 (指向根自身) 时返回 null.
 */
private fun normalizeRootfsPath(path: String): String? {
    val segments = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
}
