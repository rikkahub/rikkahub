package me.rerere.rikkahub.data.ai.tools.local

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

class CameraResultBuffer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Uri?>>()

    fun register(requestId: String): CompletableDeferred<Uri?> {
        val deferred = CompletableDeferred<Uri?>()
        pending[requestId] = deferred
        return deferred
    }

    fun complete(requestId: String, result: Uri?) {
        pending.remove(requestId)?.complete(result)
    }
}
