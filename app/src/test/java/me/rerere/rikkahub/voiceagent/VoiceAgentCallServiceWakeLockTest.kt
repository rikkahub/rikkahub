package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallServiceWakeLockTest {
    @Test
    fun `wake lock manager acquires partial wake lock on start and releases on cleanup`() {
        var acquireCalls = 0
        var releaseCalls = 0
        val fakeWakeLock = object : VoiceAgentWakeLock {
            override fun acquire() { acquireCalls++ }
            override fun release() { releaseCalls++ }
            override val isHeld: Boolean get() = acquireCalls > releaseCalls
        }

        val controller = VoiceAgentWakeLockController(wakeLockProvider = { fakeWakeLock })
        controller.acquireLock()
        assertTrue("WakeLock must be acquired on start", controller.isHeld)
        assertEquals(1, acquireCalls)

        controller.releaseLock()
        assertFalse("WakeLock must be released on cleanup", controller.isHeld)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun `repeated acquireLock calls do not leak or reacquire already held lock`() {
        var acquireCalls = 0
        var releaseCalls = 0
        val fakeWakeLock = object : VoiceAgentWakeLock {
            override fun acquire() { acquireCalls++ }
            override fun release() { releaseCalls++ }
            override val isHeld: Boolean get() = acquireCalls > releaseCalls
        }

        val controller = VoiceAgentWakeLockController(wakeLockProvider = { fakeWakeLock })
        controller.acquireLock()
        controller.acquireLock()
        assertEquals("Duplicate acquire should not call underlying acquire multiple times", 1, acquireCalls)

        controller.releaseLock()
        assertEquals(1, releaseCalls)
        assertFalse(controller.isHeld)
    }

    @Test
    fun `failed acquire exception is handled safely without throwing uncaught leak`() {
        val failingWakeLock = object : VoiceAgentWakeLock {
            override fun acquire() { throw SecurityException("WakeLock permission denied") }
            override fun release() = Unit
            override val isHeld: Boolean get() = false
        }

        val controller = VoiceAgentWakeLockController(wakeLockProvider = { failingWakeLock })
        runCatching { controller.acquireLock() }
        assertFalse("Controller must not report held after failed acquire", controller.isHeld)
    }

    @Test
    fun `production service host owns wake lock wiring for foreground start and every terminal callback`() {
        var acquireCalls = 0
        var releaseCalls = 0
        val fakeWakeLock = object : VoiceAgentWakeLock {
            override fun acquire() { acquireCalls++ }
            override fun release() { releaseCalls++ }
            override val isHeld: Boolean get() = acquireCalls > releaseCalls
        }
        val controller = VoiceAgentWakeLockController(wakeLockProvider = { fakeWakeLock })
        val delegated = mutableListOf<String>()
        val host = VoiceAgentCallServiceHost(
            wakeLockController = controller,
            cancelNotificationAction = { delegated += "cancelNotification" },
            startForegroundAction = { _, transport, _ -> delegated += "startForeground:${transport.wireName}" },
            endCompletedAction = { delegated += "endCompleted" },
            stopForegroundAction = { delegated += "stopForeground" },
            stopSelfAction = { delegated += "stopSelf" },
            reportFailureAction = { delegated += "reportFailure" },
            destroyBaseServiceAction = { delegated += "destroyBaseService" },
        )

        host.startForeground("conv-1", VoiceAgentTransport.LiveKitExperimental, VoiceAgentUiState())
        assertTrue("Lock must be held after startForeground", controller.isHeld)
        assertEquals(1, acquireCalls)
        assertEquals("startForeground:livekit_experimental", delegated.last())

        host.reportFailure(RuntimeException("non-terminal diagnostic"))
        assertTrue("Reporting alone must not release an active call's lock", controller.isHeld)
        assertEquals(0, releaseCalls)

        host.endCompleted(null)
        assertFalse("Lock must be released after endCompleted", controller.isHeld)
        assertEquals(1, releaseCalls)
        host.stopSelf()
        assertEquals("endCompleted followed by stopSelf must not double-release", 1, releaseCalls)

        host.startForeground("conv-2", VoiceAgentTransport.LiveKitExperimental, VoiceAgentUiState())
        host.stopSelf()
        assertFalse("Autonomous stop must release the lock", controller.isHeld)
        assertEquals(2, releaseCalls)

        host.startForeground("conv-3", VoiceAgentTransport.LiveKitExperimental, VoiceAgentUiState())
        host.destroyBaseService()
        assertFalse("Lock must be released after destroyBaseService", controller.isHeld)
        assertEquals(3, releaseCalls)

        host.destroyBaseService()
        assertEquals("Repeated terminal cleanup must be idempotent", 3, releaseCalls)
    }
}
