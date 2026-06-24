package me.rerere.rikkahub.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private const val TAG = "NsdServiceRegistrar"
private const val DEFAULT_SERVICE_TYPE = "_http._tcp.local."
const val DEFAULT_SERVICE_NAME = "poci"

data class RegisteredServiceInfo(
    val serviceName: String,
    val hostname: String,
    val port: Int,
    val address: InetAddress
)

class NsdServiceRegistrar(
    private val context: Context
) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun register(
        port: Int,
        serviceName: String = DEFAULT_SERVICE_NAME,
        serviceType: String = DEFAULT_SERVICE_TYPE,
        onRegistered: ((RegisteredServiceInfo) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (jmdns != null) {
            unregister()
        }

        try {
            // Acquire multicast lock for mDNS
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("jmdns-lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val address = getLocalIpAddress()
            if (address == null) {
                Log.e(TAG, "Failed to get local IP address")
                return@withContext
            }

            Log.i(TAG, "Creating JmDNS with hostname=$serviceName, address=$address")

            // Create JmDNS instance with custom hostname
            // This will register hostname.local -> IP address
            val mdns = JmDNS.create(address, serviceName)
            jmdns = mdns

            // Register HTTP service
            val serviceInfo = ServiceInfo.create(
                serviceType,
                serviceName,
                port,
                "Poci Web Server"
            )
            mdns.registerService(serviceInfo)

            Log.i(
                TAG,
                "Service registered: $serviceName.$serviceType port=$port, hostname=$serviceName.local"
            )

            onRegistered?.invoke(
                RegisteredServiceInfo(
                    serviceName = serviceName,
                    hostname = "$serviceName.local",
                    port = port,
                    address = address
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
            cleanup()
        }
    }

    suspend fun unregister() = withContext(Dispatchers.IO) {
        cleanup()
    }

    private fun cleanup() {
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        }.onFailure {
            Log.w(TAG, "Failed to close JmDNS", it)
        }
        jmdns = null

        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }.onFailure {
            Log.w(TAG, "Failed to release multicast lock", it)
        }
        multicastLock = null

        Log.i(TAG, "Service unregistered")
    }

    private fun getLocalIpAddress(): InetAddress? {
        return try {
            val connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            // The active network must not be trusted blindly: with a VPN up it is the tun
            // network, whose address LAN peers cannot reach, while the underlying Wi-Fi
            // network is still connected and listed. Prefer the active network only when it
            // is a non-VPN Wi-Fi/Ethernet network; otherwise scan the others. Cellular-only
            // yields null so register() aborts, matching the old WifiManager-based behavior.
            @Suppress("DEPRECATION") // allNetworks: the NetworkCallback replacement is async; this synchronous one-shot lookup needs a snapshot
            val candidates = (listOfNotNull(connectivityManager.activeNetwork) +
                connectivityManager.allNetworks).distinct()
            val lanNetwork = selectLanNetwork(candidates) { network ->
                connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                    LanTransports(
                        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                        vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    )
                }
            } ?: return null
            val linkProperties = connectivityManager.getLinkProperties(lanNetwork) ?: return null
            selectIpv4Address(linkProperties.linkAddresses.map { it.address })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
            null
        }
    }
}

internal data class LanTransports(
    val wifi: Boolean,
    val ethernet: Boolean,
    val vpn: Boolean
)

/**
 * Picks the first candidate network reachable by LAN peers: Wi-Fi or Ethernet transport,
 * and never a VPN — a VPN network's capabilities include the underlying transport, so a
 * VPN-over-Wi-Fi network matches WIFI yet its tun address is useless for mDNS.
 */
internal fun <T : Any> selectLanNetwork(
    candidates: List<T>,
    transportsOf: (T) -> LanTransports?
): T? = candidates.firstOrNull { candidate ->
    val transports = transportsOf(candidate) ?: return@firstOrNull false
    (transports.wifi || transports.ethernet) && !transports.vpn
}

/**
 * Picks the first IPv4 address peers on the LAN can actually reach: loopback and
 * link-local (169.254/16) addresses are excluded because JmDNS binds a single address
 * that must be routable from other devices on the network.
 */
internal fun selectIpv4Address(candidates: List<InetAddress>): Inet4Address? =
    candidates
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
