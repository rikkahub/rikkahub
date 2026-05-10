package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `blank update api returns current version without network`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw AssertionError("Blank update API must not perform network requests")
            }
            .build()
        val checker = UpdateChecker(
            client = client,
            apiUrl = "",
            currentVersionName = "2.1.17",
        )

        val states = checker.checkUpdate().toList()

        assertEquals(UiState.Loading, states.first())
        val success = states.last()
        assertTrue(success is UiState.Success)
        assertEquals("2.1.17", (success as UiState.Success).data.version)
        assertTrue(success.data.downloads.isEmpty())
    }
}
