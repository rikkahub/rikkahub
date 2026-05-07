package me.rerere.rikkahub.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference

private const val TAG = "NetworkChangeMonitor"

/**
 * Phase-17 stability fix — when the app is backgrounded (e.g. user opens Termux's
 * interactive terminal for `htop`) and Android changes the active network OR puts the
 * app's network into a restricted state, OkHttp's connection pool can hold onto stale
 * sockets and the JVM's DNS negative-cache can persist a transient `UnknownHostException`.
 *
 * On return to the app the next LLM request fails with `Unable to resolve host "..."`
 * even though the network is back and a fresh `nslookup` would succeed.
 *
 * Standard fix: register a default-network callback. On every network state transition
 * (onAvailable, onCapabilitiesChanged with INTERNET capability), call
 * `connectionPool.evictAll()` on every registered OkHttp client so the next request
 * opens a fresh socket and the DNS resolver re-runs from scratch.
 *
 * Registry pattern: anyone holding a long-lived OkHttp client (LLM provider singleton,
 * MCP manager, Telegram bot client, skill URL importer) can call [register] to opt in.
 * Weak refs — the monitor doesn't keep clients alive past their natural lifecycle.
 */
object NetworkChangeMonitor {

    @Volatile private var started: Boolean = false
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
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
        Log.d(TAG, "registered OkHttp client (now ${clients.size} active)")
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
                    Log.i(TAG, "default network available: $network — evicting OkHttp connection pools to force DNS re-resolution")
                    evictAll()
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Internet-capability flip is the symptom of switching wifi → cell or
                    // vice-versa, OR the network being restricted while backgrounded.
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        Log.d(TAG, "network gained INTERNET capability — evicting pools")
                        evictAll()
                    }
                }
            }
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, cb)
                callback = cb
                started = true
                Log.i(TAG, "registered default network callback for ${clients.size} OkHttp client(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "registerNetworkCallback failed", t)
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
