package me.rerere.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
            device.wait(Until.hasObject(By.res("chat_input")), 5_000)

            // 点击输入框并输入一条 Markdown 文本（覆盖 Markdown / 代码块渲染路径）
            val input = device.findObject(By.res("chat_input"))
            if (input != null) {
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

                // 等待发送按钮并点击
                device.wait(Until.hasObject(By.res("chat_send_button")), 3_000)
                device.findObject(By.res("chat_send_button"))?.click()
                device.waitForIdle()
            }
        }
    }
}
