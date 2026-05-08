package me.rerere.rikkahub.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference

private const val TAG = "NetworkChangeMonitor"

/**
 * Phase-17 stability fix — when the app is backgrounded (e.g. user opens Termux's
 * interactive terminal for `htop`) and Android changes the active default network OR
 * puts the app's network into a restricted state, OkHttp's connection pool can hold
 * onto stale sockets and the JVM's DNS negative-cache can persist a transient
 * `UnknownHostException`.
 *
 * On return to the app the next LLM request fails with `Unable to resolve host "..."`
 * even though the network is back and a fresh `nslookup` would succeed.
 *
 * Fix: register a **default-network** callback (not the more general
 * `registerNetworkCallback`, which also fires for non-default networks like a
 * simultaneously-connected cellular when on Wi-Fi). Track the last default-network
 * handle and only evict OkHttp connection pools when the handle actually changes
 * (real Wi-Fi ↔ cell handoff, or recovery after a `onLost`). Capability ticks like
 * signal-strength changes and validation-bit toggles do NOT trigger eviction; before
 * the fix those caused multiple-times-per-minute eviction storms that killed every
 * keep-alive socket and forced fresh TCP+TLS handshakes for every subsequent request.
 *
 * Registry pattern: anyone holding a long-lived OkHttp client (LLM provider singleton,
 * MCP manager, Telegram bot client, skill URL importer) can call [register] to opt in.
 * Weak refs — the monitor doesn't keep clients alive past their natural lifecycle.
 */
object NetworkChangeMonitor {

    @Volatile private var started: Boolean = false
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Handle of the last default network we evicted for. `Network.getNetworkHandle()`
     * returns a stable id for a given network instance. We compare it across
     * `onAvailable` callbacks so a re-announcement of the same default network (which
     * happens transiently when the OS reissues default after a settled-down period)
     * doesn't fire eviction. Cleared on `onLost` so the next `onAvailable` always
     * evicts even if the same physical network comes back with the same handle.
     */
    @Volatile private var lastDefaultHandle: Long? = null

    private val clients: MutableList<WeakReference<OkHttpClient>> = mutableListOf()

    /** Register a client for eviction on network change. Safe to call any time. */
    @Synchronized
    fun register(client: OkHttpClient) {
        // De-dupe + prune dead refs in one pass.
        val iter = clients.iterator()
        while (iter.hasNext()) {
            val c = iter.next().get()
            when {
                c == null -> iter.remove()
                c === client -> return  // already registered
                else -> Unit
            }
        }
        clients.add(WeakReference(client))
        // android.util.Log isn't mocked in JVM unit tests; SkillUrlImporter etc.
        // construct OkHttp clients during test init and the log call would crash.
        runCatching { Log.d(TAG, "registered OkHttp client (now ${clients.size} active)") }
    }

    /**
     * Bootstrap from the application context. Registers the default-network callback
     * once. Any [client]s passed are auto-registered. Idempotent — extra calls just add
     * more clients to the registry.
     */
    fun start(context: Context, vararg client: OkHttpClient) {
        for (c in client) register(c)
        if (started) return
        synchronized(this) {
            if (started) return
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val handle = network.networkHandle
                    val prev = lastDefaultHandle
                    if (handle != prev) {
                        Log.i(TAG, "default network changed ($prev -> $handle), evicting OkHttp pools to force DNS re-resolution")
                        lastDefaultHandle = handle
                        evictAll()
                    }
                    // Same handle re-announcement: settled-down rebroadcast, no-op.
                }
                override fun onLost(network: Network) {
                    // Clear so the next onAvailable evicts unconditionally, even when
                    // the same physical network reappears with the same handle id (the
                    // post-Termux-backgrounding case the monitor exists to fix).
                    if (lastDefaultHandle == network.networkHandle) {
                        lastDefaultHandle = null
                    }
                }
                // Intentionally NO override of onCapabilitiesChanged. Capability ticks
                // (signal strength, validation, RSSI) used to trigger eviction storms
                // that killed every keep-alive socket multiple times per minute. The
                // post-Termux DNS regression we're guarding against is a network-
                // handoff symptom, which onAvailable + onLost catch correctly.
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                callback = cb
                started = true
                Log.i(TAG, "registered default network callback for ${clients.size} OkHttp client(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "registerDefaultNetworkCallback failed", t)
            }
        }
    }

    @Synchronized
    private fun evictAll() {
        val iter = clients.iterator()
        while (iter.hasNext()) {
            val c = iter.next().get()
            if (c == null) {
                iter.remove()
                continue
            }
            runCatching { c.connectionPool.evictAll() }
                .onFailure { Log.w(TAG, "connectionPool.evictAll failed", it) }
        }
    }
}
