package me.rerere.rikkahub.ui.components.richtext

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Issue551LatexClippingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overflowingInlineMathShouldBecomeScrollableBlockMath() {
        val content =
            "$\\frac{123456789}{987654321}+\\frac{123456789}{987654321}+" +
                "\\frac{123456789}{987654321}+\\frac{123456789}{987654321}$"

        composeRule.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    Box(modifier = Modifier.width(160.dp)) {
                        MarkdownBlock(content = content)
                    }
                }
            }
        }

        val horizontalRange = composeRule.onAllNodes(hasScrollAction())
            .fetchSemanticsNodes()
            .firstNotNullOfOrNull { node ->
                runCatching {
                    node.config[SemanticsProperties.HorizontalScrollAxisRange]
                }.getOrNull()
            }

        assertNotNull("Expected promoted block math to expose horizontal scroll", horizontalRange)
        assertTrue(
            "Expected horizontal scroll range > 0",
            horizontalRange!!.maxValue() > 0f
        )
    }
}
