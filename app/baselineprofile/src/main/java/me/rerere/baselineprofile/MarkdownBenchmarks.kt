package me.rerere.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 聊天里 Markdown 渲染的帧耗时：流式输出一条回复，以及回复完成后来回滑动消息列表。
 *
 * 和 [BaselineProfileGenerator] 一样要先 `git apply app/baselineprofile/mock-provider.patch`。
 * 每轮先清空应用数据，从全新的对话开始；全量 AOT 编译，不受 baseline profile 覆盖范围的影响，
 * 方便对比不同的 Markdown 实现。
 * ```
 * ./gradlew :app:baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.MarkdownBenchmarks
 * ```
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MarkdownBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val server = MockOpenAIServer(reply = MockOpenAIServer.REPLY + "\n\n" + MATH_HEAVY_REPLY)
    private var sentCount = 0

    @Before
    fun startServer() = server.start()

    @After
    fun stopServer() = server.stop()

    @Test
    fun streamReply() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = { openFreshChat() },
        measureBlock = { sendAndWaitForReply() },
    )

    @Test
    fun scrollConversation() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = {
            openFreshChat()
            sendAndWaitForReply()
        },
        measureBlock = {
            val list = checkNotNull(device.findObject(By.scrollable(true))) { "message list not found" }
            list.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                list.fling(Direction.UP)
                device.waitForIdle()
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
        },
    )

    private val targetPackage: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: throw Exception("targetAppId not passed as instrumentation runner arg")

    /** 清空数据后冷启动，落在一个新对话上 */
    private fun MacrobenchmarkScope.openFreshChat() {
        pressHome()
        device.executeShellCommand("pm clear $packageName")
        startActivityAndWait()
        checkNotNull(device.wait(Until.findObject(By.res("chat_input")), 10_000)) { "chat input not found" }
    }

    private fun MacrobenchmarkScope.sendAndWaitForReply() {
        val input = checkNotNull(device.findObject(By.res("chat_input"))) { "chat input not found" }
        input.click()
        input.text = "用 Markdown 回复，带上公式、表格和代码"
        checkNotNull(device.wait(Until.findObject(By.res("chat_send_button")), 3_000)) {
            "send button not found"
        }.click()
        sentCount++
        checkNotNull(
            device.wait(Until.findObject(By.textContains("${MockOpenAIServer.REPLY_MARKER} $sentCount")), 60_000)
        ) {
            "reply $sentCount not rendered, check that mock-provider.patch is applied"
        }
        device.waitForIdle()
    }

    private companion object {
        const val ITERATIONS = 5

        /** 公式密集的一段：行内公式、会折行的长公式和多行块级公式 */
        val MATH_HEAVY_REPLY = """
            ## 公式

            设 ${'$'}f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n${'$'}，当 ${'$'}|x - a| < R${'$'} 时级数收敛，
            其中 ${'$'}R = \frac{1}{\limsup_{n \to \infty} \sqrt[n]{|a_n|}}${'$'}。高斯积分 ${'$'}\int_{-\infty}^{\infty} e^{-x^2} dx = \sqrt{\pi}${'$'}，
            欧拉公式 ${'$'}e^{i\pi} + 1 = 0${'$'}，二项式定理 ${'$'}(a+b)^n = \sum_{k=0}^{n} \binom{n}{k} a^{n-k} b^k${'$'}。

            - 矩阵 ${'$'}A = \begin{pmatrix} a & b \\ c & d \end{pmatrix}${'$'} 的行列式是 ${'$'}\det A = ad - bc${'$'}
            - 柯西-施瓦茨不等式 ${'$'}\left( \sum_{i=1}^n a_i b_i \right)^2 \le \left( \sum_{i=1}^n a_i^2 \right) \left( \sum_{i=1}^n b_i^2 \right)${'$'}
            - 斯特林公式 ${'$'}n! \sim \sqrt{2 \pi n} \left( \frac{n}{e} \right)^n${'$'}

            ${'$'}${'$'}
            \begin{aligned}
            \nabla \cdot \mathbf{E} &= \frac{\rho}{\varepsilon_0} \\
            \nabla \cdot \mathbf{B} &= 0 \\
            \nabla \times \mathbf{E} &= -\frac{\partial \mathbf{B}}{\partial t} \\
            \nabla \times \mathbf{B} &= \mu_0 \mathbf{J} + \mu_0 \varepsilon_0 \frac{\partial \mathbf{E}}{\partial t}
            \end{aligned}
            ${'$'}${'$'}

            ${'$'}${'$'}
            \hat{f}(\xi) = \int_{-\infty}^{\infty} f(x)\, e^{-2\pi i x \xi} \, dx
            ${'$'}${'$'}

            | 公式 | 值 |
            | --- | --- |
            | ${'$'}\sum_{n=1}^{\infty} \frac{1}{n^2}${'$'} | ${'$'}\frac{\pi^2}{6}${'$'} |
            | ${'$'}\lim_{x \to 0} \frac{\sin x}{x}${'$'} | ${'$'}1${'$'} |
        """.trimIndent()
    }
}
