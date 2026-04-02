package me.rerere.rikkahub.data.event

import android.os.Handler
import android.os.Looper

class ChatComposerBridge {
    interface Delegate {
        fun replaceDraftText(text: String)
        fun appendDraftText(text: String)
        fun submitDraft(answer: Boolean, overrideText: String? = null)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var delegate: Delegate? = null

    @Volatile
    private var draftTextSnapshot: String = ""

    fun register(delegate: Delegate) {
        this.delegate = delegate
    }

    fun unregister(delegate: Delegate) {
        if (this.delegate === delegate) {
            this.delegate = null
        }
    }

    fun updateDraftTextSnapshot(text: String) {
        draftTextSnapshot = text
    }

    fun getDraftText(): String = draftTextSnapshot

    fun setDraftText(text: String) {
        draftTextSnapshot = text
        postToMain {
            delegate?.replaceDraftText(text)
        }
    }

    fun appendDraftText(text: String) {
        if (text.isEmpty()) return
        draftTextSnapshot += text
        postToMain {
            delegate?.appendDraftText(text)
        }
    }

    fun sendCurrentDraft(answer: Boolean = true) {
        postToMain {
            delegate?.submitDraft(answer = answer)
        }
    }

    fun sendText(text: String, answer: Boolean = true) {
        draftTextSnapshot = text
        postToMain {
            delegate?.submitDraft(
                answer = answer,
                overrideText = text,
            )
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
