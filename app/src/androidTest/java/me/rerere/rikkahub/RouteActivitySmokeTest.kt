package me.rerere.rikkahub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<RouteActivity>()

    @Test
    fun launch_showsChatComposer() {
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.chat_input_placeholder)
        ).assertIsDisplayed()
    }

    @Test
    fun drawer_settingsNavigation_opensSettingsPage() {
        composeRule.onNodeWithContentDescription("Messages").performClick()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.settings)
        ).performClick()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.setting_page_display_setting)
        ).assertIsDisplayed()
    }
}
