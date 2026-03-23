package me.rerere.sandbox

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxSessionTest {

    /** Collect output events until [exitCode] resolves, then return accumulated list. */
    private suspend fun SandboxSession.collectAll(): List<SandboxOutput> = coroutineScope {
        val events = mutableListOf<SandboxOutput>()
        val job = launch { output.collect { events.add(it) } }
        exitCode.await()
        job.cancelAndJoin()
        events
    }

    @Test
    fun `stdout is emitted`() = runBlocking {
        val session = SandboxSession(ProcessBuilder("sh", "-c", "echo hello").start())
        val events = withTimeout(5_000) { session.collectAll() }

        val stdout = events.filterIsInstance<SandboxOutput.Stdout>().joinToString("") { it.text }
        assertTrue(stdout.contains("hello"), "expected 'hello' in stdout, got: $stdout")
        assertEquals(0, session.exitCode.await())
    }

    @Test
    fun `stderr is emitted`() = runBlocking {
        val session = SandboxSession(ProcessBuilder("sh", "-c", "echo error >&2").start())
        val events = withTimeout(5_000) { session.collectAll() }

        val stderr = events.filterIsInstance<SandboxOutput.Stderr>().joinToString("") { it.text }
        assertTrue(stderr.contains("error"), "expected 'error' in stderr, got: $stderr")
    }

    @Test
    fun `exit code is propagated`() = runBlocking {
        val session = SandboxSession(ProcessBuilder("sh", "-c", "exit 42").start())
        withTimeout(5_000) { session.collectAll() }
        assertEquals(42, session.exitCode.await())
    }

    @Test
    fun `stdin write is received by process`() = runBlocking {
        val session = SandboxSession(ProcessBuilder("sh", "-c", "read line; echo got:\$line").start())
        session.writeLine("world")
        val events = withTimeout(5_000) { session.collectAll() }

        val stdout = events.filterIsInstance<SandboxOutput.Stdout>().joinToString("") { it.text }
        assertTrue(stdout.contains("got:world"), "expected 'got:world' in stdout, got: $stdout")
    }

    @Test
    fun `kill terminates the process`() = runBlocking {
        val session = SandboxSession(ProcessBuilder("sh", "-c", "sleep 60").start())
        assertTrue(session.isAlive)
        session.kill()
        withTimeout(3_000) { session.exitCode.await() }
        assertFalse(session.isAlive)
    }

    @Test
    fun `multiple collectors receive same events`() = runBlocking {
        val session = SandboxSession(ProcessBuilder("sh", "-c", "echo multi").start())
        val list1 = mutableListOf<SandboxOutput>()
        val list2 = mutableListOf<SandboxOutput>()
        val j1 = launch { session.output.collect { list1.add(it) } }
        val j2 = launch { session.output.collect { list2.add(it) } }
        withTimeout(5_000) { session.exitCode.await() }
        j1.cancelAndJoin()
        j2.cancelAndJoin()

        val text1 = list1.filterIsInstance<SandboxOutput.Stdout>().joinToString("") { it.text }
        val text2 = list2.filterIsInstance<SandboxOutput.Stdout>().joinToString("") { it.text }
        assertTrue(text1.contains("multi"), "collector1 missed output: $text1")
        assertTrue(text2.contains("multi"), "collector2 missed output: $text2")
    }
}
