package me.rerere.rikkahub.service

import me.rerere.workspace.WorkspaceBackgroundCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundShellLogTailTest {
    private class FakeCommand(private val log: ByteArray) : WorkspaceBackgroundCommand {
        override val logPath = "/tmp/fake.log"
        override fun waitFor() = 0
        override fun outputSize() = log.size.toLong()
        override fun readOutput(offset: Long, maxBytes: Int) =
            log.copyOfRange(offset.toInt(), offset.toInt() + maxBytes)

        override fun kill() = Unit
    }

    private fun tail(log: ByteArray, maxBytes: Long) = readTailLines(FakeCommand(log), maxBytes)

    @Test
    fun `whole log fits and trailing blank lines are dropped`() {
        assertEquals(listOf("one", "two"), tail("one\ntwo\n\n".toByteArray(), 1024))
    }

    @Test
    fun `partial first line is dropped when reading from the middle`() {
        assertEquals(listOf("second", "third"), tail("first line\nsecond\nthird\n".toByteArray(), 16))
    }

    @Test
    fun `half written character at the end of a running command is held back`() {
        val bytes = "ok\n你".toByteArray()
        assertEquals(listOf("ok"), tail(bytes.copyOf(bytes.size - 1), 1024))
    }

    @Test
    fun `empty log has no lines`() {
        assertEquals(emptyList<String>(), tail(ByteArray(0), 1024))
    }
}
