package me.rerere.rikkahub.service.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the register/unregister/trip contract that ChatService relies on for kill-switch
 * teardown. The leak fix (ChatService stores the register handle and unregisters it in
 * cleanup) is only correct if unregister(handle) actually drops the handler so a recreated
 * service's stale revoke action no longer fires on trip().
 */
class AutomationKillSwitchTest {

    @Test
    fun `trip fires every registered handler`() {
        val killSwitch = AutomationKillSwitch()
        var a = 0
        var b = 0
        killSwitch.register { a++ }
        killSwitch.register { b++ }

        killSwitch.trip()

        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun `unregister with the returned handle drops that handler from future trips`() {
        val killSwitch = AutomationKillSwitch()
        var live = 0
        var stale = 0
        killSwitch.register { live++ }
        val staleHandle = killSwitch.register { stale++ }

        killSwitch.unregister(staleHandle)
        killSwitch.trip()

        assertEquals("live handler still fires", 1, live)
        assertEquals("unregistered handler must not fire", 0, stale)
    }

    @Test
    fun `trip on an empty dispatch is a no-op`() {
        AutomationKillSwitch().trip()
    }

    @Test
    fun `unregister with an unknown handle is harmless`() {
        val killSwitch = AutomationKillSwitch()
        var fired = 0
        killSwitch.register { fired++ }

        killSwitch.unregister(Any())
        killSwitch.trip()

        assertEquals(1, fired)
    }
}
