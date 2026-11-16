package me.rerere.rikkahub.data.model

import android.annotation.SuppressLint
import android.os.Environment
import kotlinx.serialization.Serializable
import java.nio.file.Paths

/**
 * 挂载进本地工作区 Rootfs 的共享存储目录, 以 JSON 列表存在 workspaces.storage_mounts 列。
 *
 * 在 Rootfs 内挂到与宿主机相同的路径 (如 /storage/emulated/0/Documents), AI 和用户看到的路径一致。
 * 需要「所有文件访问权限」才能真正读写, 没有权限时目录照样挂载, 但里面是空的或报 Permission denied。
 */
@Serializable
data class WorkspaceStorageMount(
    /** 共享存储上的绝对路径, 已经过 [normalizeStorageMountPath] 规范化 */
    val path: String,
    /** AI 写入该目录时免强制审批 */
    val writable: Boolean = false,
)

/**
 * 当前 Android 用户的内部共享存储路径。主用户是 /storage/emulated/0,
 * 次要用户和工作资料是 /storage/emulated/<userId>, 不能写死。
 */
@Suppress("DEPRECATION")
fun sharedStorageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

private const val STORAGE_ROOT = "/storage"

// /sdcard 等是指向内部共享存储的软链, 统一换成真实路径, 这样挂载点和文件工具解析的路径一致
@SuppressLint("SdCardPath")
private val SHARED_STORAGE_ALIASES = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")

/**
 * 规范化用户填写的路径, 只允许 /storage 下的目录 (内部共享存储或外置 SD 卡);
 * /sdcard 等别名换成 [sharedRoot] (即 [sharedStorageRoot])。不合法时抛出 [IllegalArgumentException]。
 */
fun normalizeStorageMountPath(input: String, sharedRoot: String = sharedStorageRoot()): String {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/")) {
        throw InvalidStorageMountPathException(InvalidStorageMountPathException.Reason.NOT_ABSOLUTE, input)
    }
    val normalized = Paths.get(trimmed).normalize().toString().trimEnd('/').ifEmpty { "/" }
    val resolved = SHARED_STORAGE_ALIASES.firstNotNullOfOrNull { alias ->
        when {
            normalized == alias -> sharedRoot
            normalized.startsWith("$alias/") -> sharedRoot + normalized.removePrefix(alias)
            else -> null
        }
    } ?: normalized
    if (!resolved.startsWith("$STORAGE_ROOT/") || resolved == "$STORAGE_ROOT/emulated") {
        throw InvalidStorageMountPathException(InvalidStorageMountPathException.Reason.OUTSIDE_STORAGE, input)
    }
    return resolved
}

/** 路径不合法; 界面按 [reason] 显示本地化提示 */
class InvalidStorageMountPathException(
    val reason: Reason,
    input: String,
) : IllegalArgumentException("Invalid storage mount path ($reason): $input") {
    enum class Reason {
        NOT_ABSOLUTE,

        /** 不在 /storage 下 */
        OUTSIDE_STORAGE,
    }
}
