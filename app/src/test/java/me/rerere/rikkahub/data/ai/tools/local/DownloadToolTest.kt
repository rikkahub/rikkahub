package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Test

class DownloadToolTest {

    // Success path & filename sanitization both require a real DownloadManager and
    // android.net.Uri.parse — instrumented test required for those branches.

    @Test(expected = IllegalStateException::class)
    fun `download_file throws when url is missing`() {
        // Tool calls error("url is required") -> IllegalStateException
        val tool = downloadTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }
}
