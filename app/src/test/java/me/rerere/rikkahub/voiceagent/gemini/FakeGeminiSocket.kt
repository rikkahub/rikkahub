package me.rerere.rikkahub.voiceagent.gemini

class FakeGeminiSocket : GeminiSocket {
    data class OpenedSession(
        val onMessage: (String) -> Unit,
        val onClosed: (Int, String) -> Unit,
        val onFailure: (Throwable) -> Unit,
    )

    var openedUrl: String? = null
        private set
    var openedToken: String? = null
        private set
    val openedSessions = mutableListOf<OpenedSession>()
    val sentMessages = mutableListOf<String>()
    val sendResults = ArrayDeque<Boolean>()
    var beforeOpen: (() -> Unit)? = null
    var beforeSend: ((String) -> Unit)? = null
    var closeCount = 0
        private set

    private var onMessage: ((String) -> Unit)? = null
    private var onClosed: ((Int, String) -> Unit)? = null
    private var onFailure: ((Throwable) -> Unit)? = null

    override fun open(
        url: String,
        token: String,
        onMessage: (String) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        beforeOpen?.invoke()
        openedUrl = url
        openedToken = token
        openedSessions += OpenedSession(onMessage, onClosed, onFailure)
        this.onMessage = onMessage
        this.onClosed = onClosed
        this.onFailure = onFailure
    }

    override fun send(text: String): Boolean {
        beforeSend?.invoke(text)
        sentMessages += text
        return sendResults.removeFirstOrNull() ?: true
    }

    override fun close() {
        closeCount++
    }

    fun receive(text: String) {
        onMessage?.invoke(text)
    }

    fun receiveFromSession(index: Int, text: String) {
        openedSessions[index].onMessage(text)
    }

    fun closeFromServer(code: Int = 1000, reason: String = "done") {
        onClosed?.invoke(code, reason)
    }

    fun fail(error: Throwable) {
        onFailure?.invoke(error)
    }
}
