package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea

/**
 * 通过工作区 pdflatex 编译 LaTeX 图表代码，返回渲染后的 SVG 矢量文本。
 * 不负责缓存——缓存由调用方（[LatexRenderCache]）管理。
 */
object DiagramRenderer {

    /** CJK 统一表意文字（中文）检测：含这些字符时用 xelatex 编译并加载 ctex 宏包
     *  （ctex/fandol 为 OTF 字体，pdfTeX 不支持；需工作区安装 texlive-lang-chinese + texlive-xetex） */
    private val CJK_REGEX = Regex("[\u4e00-\u9fff\u3400-\u4dbf]")

    private const val TIMEOUT_COMPILE = 15_000L
    private const val TIMEOUT_CONVERT = 5_000L
    private const val TIMEOUT_CLEANUP = 1_000L
    private const val BASE_PREFIX = "_rikka_d"

    /** @throws IllegalStateException 工作区不可用或编译失败
     *  @param isDark 深色模式时注入 \\color{white}，从 LaTeX 源头控制颜色（避免 SVG 后处理替换的脆弱性） */
    suspend fun render(repo: WorkspaceRepository, latex: String, isDark: Boolean = false): String {
        val workspace = repo.listFlow().first().firstOrNull {
            it.shellStatus == WorkspaceShellStatus.READY.name
        } ?: throw IllegalStateException("没有可用的工作区")

        val id = workspace.id
        // 用随机后缀确保并发渲染不互相覆盖
        val base = "${BASE_PREFIX}_${kotlin.random.Random.nextLong().toString(36)}"

        try {
            repo.writeText(id, "$base.tex", buildPreamble(latex, isDark), overwrite = true)

            // 中文（CJK）检测：ctex + fandol 为 OTF 字体，pdfTeX 不支持，需切 xelatex
            val hasCjk = CJK_REGEX.containsMatchIn(latex)
            val engine = if (hasCjk) "xelatex" else "pdflatex"
            // xelatex 首次加载字体较慢，超时翻倍
            val compileTimeout = if (hasCjk) TIMEOUT_COMPILE * 2 else TIMEOUT_COMPILE

            repo.executeCommand(id,
                command = "$engine -interaction=nonstopmode $base.tex",
                cwd = "", timeoutMillis = compileTimeout
            )
            // fileSize 对不存在的文件抛 IllegalArgumentException（"File does not exist: ..."），
            // 用 runCatching 容错为 0，从而走下面读 .log 提取真实错误原因，而不是把底层异常透传给用户
            val pdfSize = runCatching { repo.fileSize(id, WorkspaceStorageArea.FILES, "$base.pdf") }.getOrDefault(0L)
            if (pdfSize <= 0L) {
                // 读 .log 提取缺失宏包名，给用户可操作的错误信息
                val reason = runCatching {
                    val log = repo.readText(id, "$base.log")
                    val m = Regex("""! LaTeX Error: File '([^']+\.sty)' not found""").find(log)
                        ?: Regex("""! Package ([a-zA-Z0-9]+) Error""").find(log)
                    m?.groupValues?.get(1)?.let { "缺少宏包: $it（可用 apt install texlive-* 安装）" }
                        ?: log.lineSequence().firstOrNull { it.trimStart().startsWith("!") }
                            ?.let { "LaTeX 编译失败: $it" }
                }.getOrNull()
                throw IllegalStateException(reason ?: "LaTeX 编译失败")
            }

            repo.executeCommand(id,
                command = "pdftocairo -svg $base.pdf $base.svg",
                cwd = "", timeoutMillis = TIMEOUT_CONVERT
            )

            val svg = repo.readText(id, "$base.svg")
            if (svg.isBlank()) throw IllegalStateException("SVG 转换为空")
            return svg
        } finally {
            runCatching {
                repo.executeCommand(id,
                    command = "rm -f $base.*",
                    cwd = "", timeoutMillis = TIMEOUT_CLEANUP
                )
            }
        }
    }

    /** 动态 preamble：基础包 + 从用户代码提取的额外 \usepackage / \usetikzlibrary。
     *  这样新增 tikz 宏包（pgfplots/chemfig 等）无需改代码——用户代码声明即加载。 */
    private fun buildPreamble(latex: String, isDark: Boolean): String {        // 提取用户代码里的额外宏包（排除已在基础 preamble 的）
        val basePackages = setOf(
            "fontenc", "lmodern", "tikz", "xcolor", "amsmath", "amssymb", "xy", "amscd",
        )
        val extraPackages = Regex("""\\usepackage(?:\[[^]]*\])?\{([^}]+)\}""")
            .findAll(latex)
            .flatMap { it.groupValues[1].split(",").map(String::trim) }
            .filter { it.isNotBlank() && it !in basePackages }
            .distinct()
            .toList()

        // 提取用户代码里的 tikz library（基础已有 cd/arrows.meta）
        val baseLibs = setOf("cd", "arrows.meta")
        val extraLibs = Regex("""\\usetikzlibrary\{([^}]+)\}""")
            .findAll(latex)
            .flatMap { it.groupValues[1].split(",").map(String::trim) }
            .filter { it.isNotBlank() && it !in baseLibs }
            .distinct()
            .toList()

        val libs = (baseLibs + extraLibs).joinToString(",")

        return buildString {
            // border 4pt（原 8pt）：减小上下左右留白，图更紧凑（8pt 在放大 1.8 倍后空隙达 28.8dp）
            appendLine("\\documentclass[border=4pt]{standalone}")
            appendLine("\\usepackage[T1]{fontenc}")
            appendLine("\\usepackage{lmodern}")
            appendLine("\\usepackage{tikz}")
            appendLine("\\usetikzlibrary{$libs}")
            appendLine("\\usepackage{xcolor}")
            appendLine(if (isDark) "\\color{white}" else "\\color{black}")
            appendLine("\\usepackage{amsmath,amssymb}")
            appendLine("\\usepackage[all]{xy}")
            appendLine("\\usepackage{amscd}")
            // 中文支持：用户代码含 CJK 字符时加载 ctex（需工作区安装 texlive-lang-chinese，
            // 否则 pdflatex 对 UTF-8 中文报错）
            if (CJK_REGEX.containsMatchIn(latex)) {
                appendLine("\\usepackage[UTF8]{ctex}")
            }
            extraPackages.forEach { appendLine("\\usepackage{$it}") }
            appendLine("""\tikzcdset{
  every arrow/.append style={/tikz/line width=0.35pt},
  every label/.append style={font=\footnotesize}
}""")
            appendLine("\\begin{document}")
            appendLine(latex)
            appendLine("\\end{document}")
        }
    }
}
