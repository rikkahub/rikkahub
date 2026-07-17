package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioManager
import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidDirectAudioRouteControllerTest {
    @Test
    fun `focused capabilities acquire and retire in policy order`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()

        val lease = controller.acquireCapture()

        assertEquals(
            listOf("focus-acquire", "mode-acquire", "bluetooth-acquire"),
            fixture.events,
        )

        lease.configureRecorder(uninitializedAudioRecord())
        lease.retire()
        lease.retire()
        controller.close()
        controller.close()

        assertEquals(
            listOf(
                "focus-acquire",
                "mode-acquire",
                "bluetooth-acquire",
                "device-configure",
                "device-retire",
                "bluetooth-retire",
                "mode-retire",
                "focus-retire",
                "capabilities-close",
            ),
            fixture.events,
        )
    }

    @Test
    fun `missing optional resources preserve direct fallback acquisition`() {
        val fixture = DirectAudioCapabilitiesFixture().apply {
            focus.acquisitionAvailable = false
            mode.acquisitionAvailable = false
            bluetooth.acquisitionAvailable = false
            device.configurationAvailable = false
        }
        val controller = fixture.controller()

        val lease = controller.acquireCapture()
        lease.configureRecorder(uninitializedAudioRecord())
        lease.retire()
        controller.close()

        assertEquals(
            listOf(
                "focus-acquire",
                "mode-acquire",
                "bluetooth-acquire",
                "device-configure",
                "capabilities-close",
            ),
            fixture.events,
        )
    }

    @Test
    fun `communication and Bluetooth failures remain best effort`() {
        val fixture = DirectAudioCapabilitiesFixture().apply {
            mode.acquireFailure = IllegalStateException("mode unavailable")
            bluetooth.acquireFailure = IllegalStateException("Bluetooth unavailable")
        }
        val controller = fixture.controller()

        val lease = controller.acquireCapture()
        lease.retire()
        controller.close()

        assertEquals(
            listOf(
                "focus-acquire",
                "mode-acquire",
                "bluetooth-acquire",
                "focus-retire",
                "capabilities-close",
            ),
            fixture.events,
        )
    }

    @Test
    fun `focus acquisition failure remains fatal without later capability mutation`() {
        val fixture = DirectAudioCapabilitiesFixture().apply {
            focus.acquireFailure = IllegalStateException("delayed focus is fatal")
        }
        val controller = fixture.controller()

        val failure = assertThrows(IllegalStateException::class.java) {
            controller.acquireCapture()
        }

        assertEquals("delayed focus is fatal", failure.message)
        assertEquals(listOf("focus-acquire"), fixture.events)
        controller.close()
        assertEquals(listOf("focus-acquire", "capabilities-close"), fixture.events)
    }

    @Test
    fun `focus callback preserves fatal and recoverable policy`() {
        val audioErrors = mutableListOf<String>()
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller(audioErrors::add)
        controller.acquireCapture()

        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(listOf("Audio focus lost: ${AudioManager.AUDIOFOCUS_LOSS}"), audioErrors)
        controller.close()
    }

    @Test
    fun `stale lease cannot retire or configure the current route`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()
        val stale = controller.acquireCapture()
        val current = controller.acquireCapture()
        val eventsBeforeStaleUse = fixture.events.toList()

        stale.retire()
        assertThrows(IllegalStateException::class.java) {
            stale.configureRecorder(uninitializedAudioRecord())
        }

        assertEquals(eventsBeforeStaleUse, fixture.events)

        current.configureRecorder(uninitializedAudioRecord())
        current.retire()
        controller.close()

        assertEquals(1, fixture.device.configureCalls)
        assertEquals(1, fixture.device.retireCalls)
        assertEquals(1, fixture.bluetooth.retireCalls)
        assertEquals(1, fixture.mode.retireCalls)
        assertEquals(1, fixture.focus.retireCalls)
    }

    @Test
    fun `new acquisition waits for physical retirement to finish`() {
        val retirementEntered = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val fixture = DirectAudioCapabilitiesFixture().apply {
            bluetooth.beforeRetire = {
                retirementEntered.countDown()
                releaseRetirement.await(5, TimeUnit.SECONDS)
            }
        }
        val controller = fixture.controller()
        val oldLease = controller.acquireCapture()
        val oldRetirement = thread(name = "old-direct-route-retirement") {
            oldLease.retire()
        }
        assertTrue(retirementEntered.await(5, TimeUnit.SECONDS))
        val newAcquisitionCompleted = CountDownLatch(1)
        val newLease = AtomicReference<VoiceAudioCaptureRouteLease?>()
        val newAcquisition = thread(name = "new-direct-route-acquisition") {
            newLease.set(controller.acquireCapture())
            newAcquisitionCompleted.countDown()
        }

        try {
            assertFalse(newAcquisitionCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseRetirement.countDown()
            oldRetirement.join(5_000)
            newAcquisition.join(5_000)
        }

        assertFalse(oldRetirement.isAlive)
        assertFalse(newAcquisition.isAlive)
        assertTrue(newAcquisitionCompleted.await(0, TimeUnit.MILLISECONDS))
        assertEquals(2, fixture.mode.acquireCalls)
        assertEquals(2, fixture.bluetooth.acquireCalls)

        requireNotNull(newLease.get()).retire()
        controller.close()
    }

    @Test
    fun `retirement failures do not skip remaining resources`() {
        val fixture = DirectAudioCapabilitiesFixture().apply {
            device.retireFailure = IllegalStateException("device")
            bluetooth.retireFailure = IllegalStateException("bluetooth")
            mode.retireFailure = IllegalStateException("mode")
            focus.retireFailure = IllegalStateException("focus")
        }
        val controller = fixture.controller()
        val lease = controller.acquireCapture()
        lease.configureRecorder(uninitializedAudioRecord())

        lease.retire()
        controller.close()

        assertEquals(1, fixture.device.retireCalls)
        assertEquals(1, fixture.bluetooth.retireCalls)
        assertEquals(1, fixture.mode.retireCalls)
        assertEquals(1, fixture.focus.retireCalls)
        assertEquals(1, fixture.closeCalls)
    }

    @Test
    fun `focus acquired concurrently with close retires immediately and skips later acquisition`() {
        val focusEntered = CountDownLatch(1)
        val releaseFocus = CountDownLatch(1)
        val fixture = DirectAudioCapabilitiesFixture().apply {
            focus.beforeAcquire = {
                focusEntered.countDown()
                releaseFocus.await(5, TimeUnit.SECONDS)
            }
        }
        val controller = fixture.controller()
        val acquisitionFailure = AtomicReference<Throwable?>()
        val acquisition = thread(name = "direct-focus-acquisition") {
            runCatching { controller.acquireCapture() }
                .onFailure(acquisitionFailure::set)
        }
        assertTrue(focusEntered.await(5, TimeUnit.SECONDS))

        controller.close()
        releaseFocus.countDown()
        acquisition.join(5_000)

        assertFalse(acquisition.isAlive)
        assertEquals(null, acquisitionFailure.get())
        assertEquals(1, fixture.focus.retireCalls)
        assertEquals(0, fixture.mode.acquireCalls)
        assertEquals(0, fixture.bluetooth.acquireCalls)
    }

    @Test
    fun `closed controller rejects acquisition without capability mutation`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()
        controller.close()
        val eventsAfterClose = fixture.events.toList()

        assertThrows(IllegalStateException::class.java) {
            controller.acquireCapture()
        }
        controller.close()

        assertEquals(eventsAfterClose, fixture.events)
    }
}

private class DirectAudioCapabilitiesFixture {
    val events = mutableListOf<String>()
    val focus = FakeDirectAudioFocusCapability(events)
    val mode = FakeDirectCommunicationModeCapability(events)
    val bluetooth = FakeDirectBluetoothCaptureCapability(events)
    val device = FakeDirectCaptureDeviceCapability(events)
    var closeCalls = 0

    private val capabilities = DirectAudioRouteCapabilities(
        focus = focus,
        communicationMode = mode,
        bluetoothCapture = bluetooth,
        captureDevice = device,
        close = {
            closeCalls += 1
            events += "capabilities-close"
        },
    )

    fun controller(onAudioError: (String) -> Unit = {}) =
        AndroidDirectAudioRouteController(capabilities = capabilities, onAudioError = onAudioError)
}

private class FakeDirectAudioFocusCapability(
    private val events: MutableList<String>,
) : DirectAudioFocusCapability {
    var acquisitionAvailable = true
    var acquireFailure: Throwable? = null
    var retireFailure: Throwable? = null
    var beforeAcquire: () -> Unit = {}
    var acquireCalls = 0
    var retireCalls = 0
    private var onFocusChange: ((Int) -> Unit)? = null

    override fun acquire(onFocusChange: (Int) -> Unit): DirectAudioResourceLease? {
        acquireCalls += 1
        events += "focus-acquire"
        beforeAcquire()
        acquireFailure?.let { throw it }
        this.onFocusChange = onFocusChange
        if (!acquisitionAvailable) return null
        return DirectAudioResourceLease {
            retireCalls += 1
            events += "focus-retire"
            retireFailure?.let { throw it }
        }
    }

    fun dispatch(change: Int) {
        requireNotNull(onFocusChange).invoke(change)
    }
}

private class FakeDirectCommunicationModeCapability(
    private val events: MutableList<String>,
) : DirectCommunicationModeCapability {
    var acquisitionAvailable = true
    var acquireFailure: Throwable? = null
    var retireFailure: Throwable? = null
    var acquireCalls = 0
    var retireCalls = 0

    override fun acquire(): DirectAudioResourceLease? {
        acquireCalls += 1
        events += "mode-acquire"
        acquireFailure?.let { throw it }
        if (!acquisitionAvailable) return null
        return DirectAudioResourceLease {
            retireCalls += 1
            events += "mode-retire"
            retireFailure?.let { throw it }
        }
    }
}

private class FakeDirectBluetoothCaptureCapability(
    private val events: MutableList<String>,
) : DirectBluetoothCaptureCapability {
    var acquisitionAvailable = true
    var acquireFailure: Throwable? = null
    var retireFailure: Throwable? = null
    var beforeRetire: () -> Unit = {}
    var acquireCalls = 0
    var retireCalls = 0

    override fun acquire(): DirectAudioResourceLease? {
        acquireCalls += 1
        events += "bluetooth-acquire"
        acquireFailure?.let { throw it }
        if (!acquisitionAvailable) return null
        return DirectAudioResourceLease {
            beforeRetire()
            retireCalls += 1
            events += "bluetooth-retire"
            retireFailure?.let { throw it }
        }
    }
}

private class FakeDirectCaptureDeviceCapability(
    private val events: MutableList<String>,
) : DirectCaptureDeviceCapability {
    var configurationAvailable = true
    var retireFailure: Throwable? = null
    var configureCalls = 0
    var retireCalls = 0

    override fun configure(recorder: AudioRecord): DirectAudioResourceLease? {
        configureCalls += 1
        events += "device-configure"
        if (!configurationAvailable) return null
        return DirectAudioResourceLease {
            retireCalls += 1
            events += "device-retire"
            retireFailure?.let { throw it }
        }
    }
}

private fun uninitializedAudioRecord(): AudioRecord {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    return (unsafeField.get(null) as Unsafe).allocateInstance(AudioRecord::class.java) as AudioRecord
}
