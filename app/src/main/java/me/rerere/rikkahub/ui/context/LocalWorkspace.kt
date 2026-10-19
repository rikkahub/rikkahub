package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前内容所属的工作区 ID, 用于把 Markdown 里引用的 Rootfs 绝对路径 (如 /workspace/out.png) 解析成宿主机文件
 */
val LocalWorkspaceId = compositionLocalOf<String?> { null }
