package me.rerere.rikkahub.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsdLanNetworkSelectionTest {

    @Test
    fun `skips the active vpn network and picks the underlying wifi network`() {
        // A VPN network's capabilities include the underlying transport (WIFI) plus VPN,
        // so excluding VPN must win over matching WIFI.
        val transports = mapOf(
            "vpn-over-wifi" to LanTransports(wifi = true, ethernet = false, vpn = true),
            "wifi" to LanTransports(wifi = true, ethernet = false, vpn = false),
        )
        val selected = selectLanNetwork(listOf("vpn-over-wifi", "wifi")) { transports[it] }
        assertEquals("wifi", selected)
    }

    @Test
    fun `returns null when only cellular networks are present`() {
        val selected = selectLanNetwork(listOf("cellular")) {
            LanTransports(wifi = false, ethernet = false, vpn = false)
        }
        assertNull(selected)
    }

    @Test
    fun `accepts an ethernet network`() {
        val selected = selectLanNetwork(listOf("ethernet")) {
            LanTransports(wifi = false, ethernet = true, vpn = false)
        }
        assertEquals("ethernet", selected)
    }

    @Test
    fun `prefers the first qualifying candidate so the active network wins`() {
        val transports = mapOf(
            "active-wifi" to LanTransports(wifi = true, ethernet = false, vpn = false),
            "other-wifi" to LanTransports(wifi = true, ethernet = false, vpn = false),
        )
        val selected = selectLanNetwork(listOf("active-wifi", "other-wifi")) { transports[it] }
        assertEquals("active-wifi", selected)
    }

    @Test
    fun `skips candidates whose capabilities are unavailable`() {
        val transports = mapOf(
            "wifi" to LanTransports(wifi = true, ethernet = false, vpn = false),
        )
        val selected = selectLanNetwork(listOf("vanished", "wifi")) { transports[it] }
        assertEquals("wifi", selected)
    }

    @Test
    fun `returns null for an empty candidate list`() {
        assertNull(selectLanNetwork(emptyList<String>()) { null })
    }
}
