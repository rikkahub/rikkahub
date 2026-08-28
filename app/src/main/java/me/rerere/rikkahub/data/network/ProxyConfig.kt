package me.rerere.rikkahub.data.network

import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Authenticator as OkHttpAuthenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

fun String.toProxyOrNull(): Proxy? {
    val value = trim()
    if (value.isEmpty()) return null

    val normalizedValue = if ("://" in value) value else "http://$value"
    val uri = runCatching { URI(normalizedValue) }.getOrNull() ?: return null
    val type = when (uri.scheme?.lowercase()) {
        "http", "https" -> Proxy.Type.HTTP
        "socks5" -> Proxy.Type.SOCKS
        else -> return null
    }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeIf { it in 1..65535 } ?: return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    if (uri.path?.let { it.isNotEmpty() && it != "/" } == true) return null

    return Proxy(type, InetSocketAddress.createUnresolved(host, port))
}

class SettingsProxyAuthenticator(
    private val settingsStore: SettingsStore,
) : OkHttpAuthenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val setting = settingsStore.settingsFlow.value.networkSetting
        val configuredProxy = setting.proxyUrl.toProxyOrNull() ?: return null
        if (configuredProxy.type() != Proxy.Type.HTTP || route?.proxy != configuredProxy) return null
        if (setting.proxyUsername.isEmpty()) return null
        if (response.request.header("Proxy-Authorization") != null) return null

        return response.request.newBuilder()
            .header(
                "Proxy-Authorization",
                Credentials.basic(setting.proxyUsername, setting.proxyPassword, Charsets.UTF_8),
            )
            .build()
    }
}

class SettingsSocks5Authenticator(
    private val settingsStore: SettingsStore,
) : java.net.Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val setting = settingsStore.settingsFlow.value.networkSetting
        val configuredProxy = setting.proxyUrl.toProxyOrNull() ?: return null
        val proxyAddress = configuredProxy.address() as? InetSocketAddress ?: return null
        if (configuredProxy.type() != Proxy.Type.SOCKS) return null
        if (setting.proxyUsername.isEmpty()) return null
        if (!requestingProtocol.equals("SOCKS5", ignoreCase = true)) return null
        if (requestingPort != proxyAddress.port) return null

        val requestedHost = requestingHost?.removeSurrounding("[", "]")
        val configuredHost = proxyAddress.hostString.removeSurrounding("[", "]")
        if (!requestedHost.equals(configuredHost, ignoreCase = true)) return null

        return PasswordAuthentication(setting.proxyUsername, setting.proxyPassword.toCharArray())
    }
}

class SettingsProxySelector(
    private val settingsStore: SettingsStore,
    private val systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        settingsStore.settingsFlow.value.networkSetting.proxyUrl.toProxyOrNull()?.let { proxy ->
            return listOf(proxy)
        }
        return runCatching { systemProxySelector?.select(uri) }
            .getOrNull()
            .orEmpty()
            .ifEmpty { listOf(Proxy.NO_PROXY) }
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, exception: IOException) {
        if (settingsStore.settingsFlow.value.networkSetting.proxyUrl.toProxyOrNull() == null) {
            systemProxySelector?.connectFailed(uri, socketAddress, exception)
        }
    }
}
