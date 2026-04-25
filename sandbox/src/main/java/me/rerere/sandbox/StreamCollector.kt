package me.rerere.sandbox

import java.io.InputStream

internal class StreamCollector(
    private val inputStream: InputStream,
) {
    private val buffer = StringBuilder()
    private val thread = Thread {
        inputStream.bufferedReader().use { reader ->
            val chunk = CharArray(BUFFER_SIZE)
            while (true) {
                val read = reader.read(chunk)
                if (read < 0) break
                buffer.append(chunk, 0, read)
            }
        }
    }

    fun start() {
        thread.start()
    }

    fun join() {
        thread.join()
    }

    fun output(): String = buffer.toString()

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
