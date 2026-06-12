package me.rerere.rikkahub.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class NsdIpv4SelectionTest {

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun `picks the ipv4 address among ipv6 loopback and link-local candidates`() {
        val selected = selectIpv4Address(
            listOf(
                addr("::1"),
                addr("fe80::1"),
                addr("127.0.0.1"),
                addr("169.254.10.1"),
                addr("192.168.1.5"),
            )
        )
        assertEquals(addr("192.168.1.5"), selected)
    }

    @Test
    fun `preserves candidate order when several ipv4 addresses are usable`() {
        val selected = selectIpv4Address(
            listOf(
                addr("192.168.1.5"),
                addr("10.0.0.2"),
            )
        )
        assertEquals(addr("192.168.1.5"), selected)
    }

    @Test
    fun `skips loopback ipv4`() {
        assertNull(selectIpv4Address(listOf(addr("127.0.0.1"))))
    }

    @Test
    fun `skips link-local ipv4`() {
        assertNull(selectIpv4Address(listOf(addr("169.254.10.1"))))
    }

    @Test
    fun `returns null when only ipv6 addresses are present`() {
        assertNull(selectIpv4Address(listOf(addr("::1"), addr("2001:db8::1"))))
    }

    @Test
    fun `returns null for an empty candidate list`() {
        assertNull(selectIpv4Address(emptyList()))
    }
}
