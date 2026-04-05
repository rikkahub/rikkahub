package me.rerere.rikkahub.data.ai.mcp.transport

import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_MCP_STDIO_DEFAULT_MAX_BYTES
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_MCP_STDIO_DEFAULT_WAIT_TIME_MS
import me.rerere.rikkahub.data.ai.tools.termux.TermuxMcpStdioServerManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxMcpStdioStream

@OptIn(ExperimentalEncodingApi::class)
class TermuxStdioClientTransport(
    private val sessionManager: TermuxMcpStdioServerManager,
    private val command: String,
    private val args: List<String>,
    private val workdir: String,
    private val environment: Map<String, String>,
) : AbstractTransport() {
    private val initialized = AtomicBoolean(false)
    private val sessionClosed = AtomicBoolean(false)
    private val closeEmitted = AtomicBoolean(false)

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var delegate: StdioClientTransport? = null

    override suspend fun start() {
        check(initialized.compareAndSet(false, true)) {
            "TermuxStdioClientTransport already started! If using Client class, note that connect() calls start() automatically."
        }

        try {
            val response = sessionManager.startSession(
                command = command,
                args = args,
                workdir = workdir,
                environment = environment,
            )
            val createdSessionId = response.sessionId
                ?: error(response.error ?: "Local MCP stdio bridge did not return a session id.")
            sessionId = createdSessionId

            val transport = StdioClientTransport(
                input = TermuxStdioInputStream(
                    sessionManager = sessionManager,
                    sessionId = createdSessionId,
                    stream = TermuxMcpStdioStream.Stdout,
                ).asSource().buffered(),
                output = TermuxStdioOutputStream(
                    sessionManager = sessionManager,
                    sessionId = createdSessionId,
                ).asSink().buffered(),
                error = TermuxStdioInputStream(
                    sessionManager = sessionManager,
                    sessionId = createdSessionId,
                    stream = TermuxMcpStdioStream.Stderr,
                ).asSource().buffered(),
            )
            transport.onMessage { message ->
                _onMessage(message)
            }
            transport.onError { error ->
                _onError(error)
            }
            transport.onClose {
                closeSessionSilently()
                emitCloseOnce()
            }
            delegate = transport
            transport.start()
        } catch (e: Throwable) {
            initialized.set(false)
            closeSessionSilently()
            throw e
        }
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        check(initialized.get()) { "TermuxStdioClientTransport is not initialized!" }
        delegate?.send(message, options) ?: error("TermuxStdioClientTransport is closed!")
    }

    override suspend fun close() {
        if (!initialized.get()) return
        try {
            delegate?.close()
        } finally {
            delegate = null
            closeSessionSilently()
            emitCloseOnce()
            initialized.set(false)
        }
    }

    private fun closeSessionSilently() {
        val id = sessionId ?: return
        if (!sessionClosed.compareAndSet(false, true)) return
        sessionId = null
        runCatching {
            runBlocking {
                sessionManager.closeSession(id)
            }
        }
    }

    private fun emitCloseOnce() {
        if (closeEmitted.compareAndSet(false, true)) {
            closeSessionSilently()
            _onClose()
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class TermuxStdioInputStream(
    private val sessionManager: TermuxMcpStdioServerManager,
    private val sessionId: String,
    private val stream: TermuxMcpStdioStream,
) : InputStream() {
    private var buffer = ByteArray(0)
    private var offset = 0
    private var eof = false
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (closed || eof) return -1
        if (len == 0) return 0

        while (offset >= buffer.size) {
            val response = runBlocking {
                sessionManager.readStream(
                    sessionId = sessionId,
                    stream = stream,
                    waitTimeMs = TERMUX_MCP_STDIO_DEFAULT_WAIT_TIME_MS,
                    maxBytes = len.coerceAtLeast(TERMUX_MCP_STDIO_DEFAULT_MAX_BYTES),
                )
            }
            response.error?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
            val nextChunk = response.dataBase64.takeIf { it.isNotBlank() }?.let { Base64.decode(it) } ?: ByteArray(0)
            if (nextChunk.isNotEmpty()) {
                buffer = nextChunk
                offset = 0
                break
            }
            if (response.eof) {
                eof = true
                return -1
            }
        }

        val toCopy = minOf(len, buffer.size - offset)
        System.arraycopy(buffer, offset, b, off, toCopy)
        offset += toCopy
        if (offset >= buffer.size) {
            buffer = ByteArray(0)
            offset = 0
        }
        return toCopy
    }

    override fun close() {
        closed = true
        eof = true
        buffer = ByteArray(0)
        offset = 0
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class TermuxStdioOutputStream(
    private val sessionManager: TermuxMcpStdioServerManager,
    private val sessionId: String,
) : OutputStream() {
    private var closed = false

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (closed) throw IOException("Output stream is closed")
        if (len == 0) return
        val payload = b.copyOfRange(off, off + len)
        val response = runBlocking {
            sessionManager.writeStdin(
                sessionId = sessionId,
                dataBase64 = Base64.encode(payload),
            )
        }
        response.error?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
        if (!response.success) {
            throw IOException("Failed to write to local MCP stdio session")
        }
    }

    override fun close() {
        closed = true
    }
}
