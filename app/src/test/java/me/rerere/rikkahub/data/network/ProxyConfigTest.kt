package me.rerere.rikkahub.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class ProxyConfigTest {
    @Test
    fun `parses http proxy URL`() {
        val proxy = "http://127.0.0.1:7890".toProxyOrNull()!!
        val address = proxy.address() as InetSocketAddress

        assertEquals(Proxy.Type.HTTP, proxy.type())
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
    }

    @Test
    fun `parses socks proxy URL`() {
        val proxy = "socks5://localhost:1080".toProxyOrNull()!!

        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `maps https proxy URL to http proxy type`() {
        val proxy = "https://localhost:8443".toProxyOrNull()!!

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `defaults host and port input to http proxy`() {
        val proxy = "localhost:8080".toProxyOrNull()!!

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `rejects invalid proxy URL`() {
        assertNull("ftp://localhost:21".toProxyOrNull())
        assertNull("socks://localhost:1080".toProxyOrNull())
        assertNull("http://localhost".toProxyOrNull())
        assertNull("http://user:password@localhost:8080".toProxyOrNull())
        assertNull("http://localhost:8080/path".toProxyOrNull())
    }
}
