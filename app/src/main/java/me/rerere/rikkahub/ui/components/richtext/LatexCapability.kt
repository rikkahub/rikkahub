package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 检测工作区是否安装了 LaTeX 图表渲染所需的工具链。
 * 首次检测后缓存到 SharedPreferences，避免重复 which 调用。
 */
object LatexCapability : KoinComponent {

    private const val PREFS_NAME = "latex_capability"
    private const val KEY_CHECKED = "checked"
    private const val KEY_AVAILABLE = "available"
    private const val KEY_LAST_CHECK = "last_check"

    /**
     * false 结果的缓存有效期（毫秒）。超过后即使缓存了 false 也会重新检测——
     * 修复"装包前检测失败被永久缓存，装完包后永远报 LaTeX 未安装"的问题。
     * true 结果永久缓存（工具链不会无故消失）。
     */
    private const val FALSE_TTL_MS = 60_000L

    private val repo: WorkspaceRepository by inject()

    /** 所需工具清单（缺一不可）。注意：不能把 "kpsewhich tikz.sty" 当单个工具名传给 which，
     *  那会被解释成检查 kpsewhich/tikz/sty 三个命令，tikz/sty 非可执行文件导致检测失真。
     *  正确做法：which 查可执行文件，kpsewhich 查宏包文件。 */
    private val REQUIRED_TOOLS = listOf(
        "pdflatex",
        "pdftocairo",
    )

    suspend fun isAvailable(context: Context): Boolean {
        val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 缓存命中判定：
        //  - 缓存的 true：永久有效（工具链装好后不会消失）
        //  - 缓存的 false：仅 FALSE_TTL_MS 内有效，过期后自动重检（用户装完包无需"关再开"开关）
        val cachedAvailable = prefs.getBoolean(KEY_AVAILABLE, false)
        if (prefs.getBoolean(KEY_CHECKED, false)) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
            val ttlOk = if (cachedAvailable) Long.MAX_VALUE else FALSE_TTL_MS
            if (System.currentTimeMillis() - lastCheck < ttlOk) {
                return cachedAvailable
            }
        }

        val available = checkWorkspace()
        prefs.edit()
            .putBoolean(KEY_CHECKED, true)
            .putBoolean(KEY_AVAILABLE, available)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
        return available
    }

    /** 强制重新检测（用户装完 LaTeX 后调用） */
    suspend fun recheck(context: Context): Boolean {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CHECKED, false).apply()
        return isAvailable(context)
    }

    private suspend fun checkWorkspace(): Boolean {
        return try {
            val workspace = repo.listFlow().first().firstOrNull {
                it.shellStatus == WorkspaceShellStatus.READY.name
            } ?: return false

            // which 查可执行文件
            val binariesOk = REQUIRED_TOOLS.all { tool ->
                val result = repo.executeCommand(
                    id = workspace.id,
                    command = "which " + tool,
                    cwd = "",
                    timeoutMillis = 3_000
                )
                result.stdout.isNotBlank()
            }
            // kpsewhich 查宏包文件（tikz.sty 必须存在）
            val tikzOk = repo.executeCommand(
                id = workspace.id,
                command = "kpsewhich tikz.sty",
                cwd = "",
                timeoutMillis = 3_000
            ).stdout.isNotBlank()
            binariesOk && tikzOk
        } catch (_: Exception) {
            false
        }
    }
}
