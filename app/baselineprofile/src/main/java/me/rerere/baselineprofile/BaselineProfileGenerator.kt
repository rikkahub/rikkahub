package me.rerere.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test class generates a basic startup baseline profile for the target package.
 *
 * We recommend you start with this but add important user flows to the profile to improve their performance.
 * Refer to the [baseline profile documentation](https://d.android.com/topic/performance/baselineprofiles)
 * for more information.
 *
 * You can run the generator with the "Generate Baseline Profile" run configuration in Android Studio or
 * the equivalent `generateBaselineProfile` gradle task:
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 *
 * 生成前先 `git apply app/baselineprofile/mock-provider.patch`，让全新安装的 app 默认模型指向 [MockOpenAIServer]；
 * 生成完 `git apply -R app/baselineprofile/mock-provider.patch` 撤掉，只提交生成的 profile 文件。
 * The run configuration runs the Gradle task and applies filtering to run only the generators.
 *
 * Check [documentation](https://d.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args)
 * for more information about available instrumentation arguments.
 *
 * After you run the generator, you can verify the improvements running the [StartupBenchmarks] benchmark.
 *
 * When using this class to generate a baseline profile, only API 33+ or rooted API 28+ are supported.
 *
 * The minimum required version of androidx.benchmark to generate a baseline profile is 1.2.0.
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        // 需要先应用 app/baselineprofile/mock-provider.patch，app 的默认模型才会指向这个假接口
        val server = MockOpenAIServer().apply { start() }
        try {
            collect()
        } finally {
            server.stop()
        }
    }

    private fun collect() {
        var sentCount = 0
        // The application id for the running build variant is read from the instrumentation arguments.
        rule.collect(
            packageName = InstrumentationRegistry.getArguments().getString("targetAppId")
                ?: throw Exception("targetAppId not passed as instrumentation runner arg"),

            // See: https://d.android.com/topic/performance/baselineprofiles/dex-layout-optimizations
            includeInStartupProfile = true
        ) {
            // This block defines the app's critical user journey. Here we are interested in
            // optimizing for app startup. But you can also navigate and scroll through your most important UI.

            // Start default activity for your app
            pressHome()
            startActivityAndWait()

            // 等待聊天输入框出现（testTag 通过 testTagsAsResourceId 暴露为 resource-id）
            val input = checkNotNull(device.wait(Until.findObject(By.res("chat_input")), 5_000)) {
                "chat input not found"
            }

            // 点击输入框并输入一条 Markdown 文本（覆盖 Markdown / 代码块渲染路径）
            input.click()
            input.text = """
                # Hello RikkaHub

                这是一段 **Markdown** 文本，包含 *斜体*、`行内代码` 和列表：

                - 第一项
                - 第二项
                - [链接](https://github.com)

                > 引用块示例

                ```kotlin
                fun main() {
                    println("Hello, world!")
                }
                ```
            """.trimIndent()
            device.waitForIdle()

            // 发送后等 mock 接口的回复流式渲染完，覆盖生成、思考块、Markdown、表格、公式和代码高亮路径
            checkNotNull(device.wait(Until.findObject(By.res("chat_send_button")), 3_000)) {
                "send button not found"
            }.click()
            sentCount++
            checkNotNull(
                device.wait(Until.findObject(By.textContains("${MockOpenAIServer.REPLY_MARKER} $sentCount")), 20_000)
            ) {
                "reply $sentCount not rendered, check that mock-provider.patch is applied"
            }
            device.waitForIdle()

            // 在消息列表上来回滑动，覆盖长列表滚动
            device.findObject(By.scrollable(true))?.let { list ->
                list.fling(Direction.UP)
                device.waitForIdle()
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
        }
    }
}
