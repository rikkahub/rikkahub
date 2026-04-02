package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.UPDATE_CHECK_ENABLED
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `initial state should follow update check flag`() {
        val checker = UpdateChecker(OkHttpClient())
        val expectedState = if (UPDATE_CHECK_ENABLED) UiState.Loading else UiState.Idle

        assertEquals(UPDATE_CHECK_ENABLED, checker.isEnabled)
        assertSame(expectedState, checker.initialState)
    }

    @Test
    fun `check update should emit initial state first`() = runBlocking {
        val checker = UpdateChecker(OkHttpClient())
        val expectedState = if (UPDATE_CHECK_ENABLED) UiState.Loading else UiState.Idle

        assertSame(expectedState, checker.checkUpdate().first())
    }
}
