package me.rerere.rikkahub.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the single-instance lifecycle of the embedded web server.
 *
 * The bug this guards against (issue #16): callers check "is a server already
 * running?" and then create one, but in [WebServerManager] those two steps
 * straddled an `appScope.launch` boundary — the `server != null` read happened
 * synchronously while the assignment happened later inside the coroutine. Two
 * concurrent `start()` calls (or `restart()`'s stop+start) both observed
 * `server == null`, both created a CIO server, and the first one was overwritten
 * and leaked with its bound socket still open.
 *
 * Making the slot a separate primitive lets the check-and-claim and the release
 * be serialized by one [Mutex] so they are atomic relative to each other. The
 * factory only runs when no server is currently live; the created handle is
 * stored under the same lock that the guard read, so the TOCTOU window is gone.
 *
 * [T] is the server handle type (the production type is Ktor's
 * `EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>`),
 * kept generic so this primitive carries no Ktor dependency and can be exercised
 * directly by a regression test with a counting factory.
 */
class WebServerLifecycle<T : Any> {
    private val mutex = Mutex()
    private var server: T? = null

    /**
     * Atomically claims the single server slot and runs [factory] iff no server
     * is currently live. Returns the created handle, or `null` if one was already
     * running (in which case [factory] is NOT invoked).
     *
     * The factory runs while the lock is held so the "no server live" check and
     * the slot assignment cannot be interleaved by a concurrent [start] or [stop].
     */
    suspend fun start(factory: suspend () -> T): T? = mutex.withLock {
        if (server != null) return@withLock null
        factory().also { server = it }
    }

    /**
     * Atomically releases the slot, running [onStop] with the live handle (if any)
     * before clearing it. Serialized against [start] by the same lock, so a
     * stop+start sequence (restart) cannot race the guard.
     */
    suspend fun stop(onStop: suspend (T) -> Unit) = mutex.withLock {
        server?.let { onStop(it) }
        server = null
    }
}
