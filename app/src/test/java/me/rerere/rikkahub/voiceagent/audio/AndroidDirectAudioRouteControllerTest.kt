package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioManager
import android.media.AudioRecord
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
            ),
            fixture.events,
        )

        controller.close()
        controller.close()
        assertEquals("capabilities-close", fixture.events.last())
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
    fun `Bluetooth permission probe failure is best effort and capture still owns prior resources`() {
        val permissionFailure = IllegalStateException("Bluetooth permission lookup failed")
        val operations = FakeBluetoothCaptureOperations().apply {
            permissionProbeFailure = permissionFailure
        }
        val bluetooth = SystemDirectBluetoothCaptureCapability(operations)
        val fixture = DirectAudioCapabilitiesFixture(
            bluetoothOverride = bluetooth,
            closeOverride = bluetooth::close,
        )
        val controller = fixture.controller()

        val lease = controller.acquireCapture()

        assertEquals(0, fixture.mode.retireCalls)
        assertEquals(0, fixture.focus.retireCalls)
        assertTrue(operations.mutations.isEmpty())

        lease.retire()
        controller.close()

        assertEquals(1, fixture.mode.retireCalls)
        assertEquals(1, fixture.focus.retireCalls)
    }

    @Test
    fun `recorder permission probe failure is best effort without device mutation`() {
        val permissionFailure = IllegalStateException("Bluetooth permission lookup failed")
        val operations = FakeCaptureDeviceOperations().apply {
            permissionProbeFailure = permissionFailure
        }
        val fixture = DirectAudioCapabilitiesFixture(
            captureDeviceOverride = SystemDirectCaptureDeviceCapability(operations),
        )
        val controller = fixture.controller()
        val lease = controller.acquireCapture()

        lease.configureRecorder(uninitializedAudioRecord())

        assertEquals(0, operations.captureDeviceQueries)
        assertTrue(operations.preferredDevices.isEmpty())
        assertTrue(operations.communicationDevices.isEmpty())
        lease.retire()
        controller.close()
    }

    @Test
    fun `focus acquisition failure remains fatal without later capability mutation`() {
        val audioErrors = mutableListOf<String>()
        val fixture = DirectAudioCapabilitiesFixture().apply {
            focus.acquireFailure = IllegalStateException("delayed focus is fatal")
        }
        val controller = fixture.controller(audioErrors::add)

        val failure = assertThrows(IllegalStateException::class.java) {
            controller.acquireCapture()
        }

        assertEquals("delayed focus is fatal", failure.message)
        assertEquals(listOf("focus-acquire"), fixture.events)
        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(audioErrors.isEmpty())
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
    fun `retired focus callback cannot affect newer capture while current callback still applies`() {
        val audioErrors = mutableListOf<String>()
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller(audioErrors::add)
        val oldCapture = controller.acquireCapture()
        oldCapture.retire()
        val currentCapture = controller.acquireCapture()

        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS, acquisitionIndex = 0)
        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS, acquisitionIndex = 1)

        assertEquals(listOf("Audio focus lost: ${AudioManager.AUDIOFOCUS_LOSS}"), audioErrors)
        currentCapture.retire()
        controller.close()
    }

    @Test
    fun `focus retirement joins admitted callback and suppresses callback after retirement wins`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val retirementCompleted = CountDownLatch(1)
        val audioErrors = Collections.synchronizedList(mutableListOf<String>())
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller { error ->
            callbackEntered.countDown()
            releaseCallback.await(5, TimeUnit.SECONDS)
            audioErrors += error
        }
        val capture = controller.acquireCapture()
        val callback = thread(name = "direct-focus-callback") {
            fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS)
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val retirement = thread(name = "direct-focus-retirement") {
            capture.retire()
            retirementCompleted.countDown()
        }

        try {
            assertTrue(awaitThreadState(retirement, Thread.State.BLOCKED))
            assertFalse(retirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseCallback.countDown()
            callback.join(5_000)
            retirement.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(retirement.isAlive)
        assertTrue(retirementCompleted.await(0, TimeUnit.MILLISECONDS))
        assertEquals(listOf("Audio focus lost: ${AudioManager.AUDIOFOCUS_LOSS}"), audioErrors)

        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, audioErrors.size)
        controller.close()
    }

    @Test
    fun `missing focus lease leaves its retained callback disabled`() {
        val audioErrors = mutableListOf<String>()
        val fixture = DirectAudioCapabilitiesFixture().apply {
            focus.acquisitionAvailable = false
        }
        val controller = fixture.controller(audioErrors::add)
        val capture = controller.acquireCapture()

        fixture.focus.dispatch(AudioManager.AUDIOFOCUS_LOSS)

        assertTrue(audioErrors.isEmpty())
        capture.retire()
        controller.close()
    }

    @Test
    fun `two capture cycles independently acquire configure and retire every resource`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()

        repeat(2) {
            controller.acquireCapture().also { capture ->
                capture.configureRecorder(uninitializedAudioRecord())
                capture.retire()
            }
        }

        assertEquals(2, fixture.focus.acquireCalls)
        assertEquals(2, fixture.mode.acquireCalls)
        assertEquals(2, fixture.bluetooth.acquireCalls)
        assertEquals(2, fixture.device.configureCalls)
        assertEquals(2, fixture.device.retireCalls)
        assertEquals(2, fixture.bluetooth.retireCalls)
        assertEquals(2, fixture.mode.retireCalls)
        assertEquals(2, fixture.focus.retireCalls)
        controller.close()
    }

    @Test
    fun `same capture owns every recorder-specific device lease`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()
        val lease = controller.acquireCapture()

        lease.configureRecorder(uninitializedAudioRecord())
        lease.configureRecorder(uninitializedAudioRecord())
        lease.retire()

        assertEquals(2, fixture.device.configureCalls)
        assertEquals(2, fixture.device.retireCalls)
        controller.close()
    }

    @Test
    fun `completed capture retirement rejects later recorder configuration without mutation`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()
        val lease = controller.acquireCapture()

        lease.retire()
        val eventsAfterRetirement = fixture.events.toList()
        lease.configureRecorder(uninitializedAudioRecord())
        lease.retire()

        assertEquals(0, fixture.device.configureCalls)
        assertEquals(0, fixture.device.retireCalls)
        assertEquals(eventsAfterRetirement, fixture.events)
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
    fun `close waits for an admitted acquisition and active lease keeps exact ownership`() {
        val focusEntered = CountDownLatch(1)
        val releaseFocus = CountDownLatch(1)
        val fixture = DirectAudioCapabilitiesFixture().apply {
            focus.beforeAcquire = {
                focusEntered.countDown()
                releaseFocus.await(5, TimeUnit.SECONDS)
            }
        }
        val controller = fixture.controller()
        var acquired: VoiceAudioCaptureRouteLease? = null
        val acquisition = thread(name = "direct-focus-acquisition") {
            acquired = controller.acquireCapture()
        }
        assertTrue(focusEntered.await(5, TimeUnit.SECONDS))
        val closeCompleted = CountDownLatch(1)
        val close = thread(name = "direct-close-during-focus") {
            controller.close()
            closeCompleted.countDown()
        }

        try {
            assertTrue(awaitThreadState(close, Thread.State.BLOCKED))
            assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFocus.countDown()
            acquisition.join(5_000)
            close.join(5_000)
        }

        assertFalse(acquisition.isAlive)
        assertFalse(close.isAlive)
        assertTrue(closeCompleted.await(0, TimeUnit.MILLISECONDS))
        assertEquals(0, fixture.focus.retireCalls)
        requireNotNull(acquired).retire()
        assertEquals(1, fixture.focus.retireCalls)
    }

    @Test
    fun `retire joins recorder configuration and retires late device before returning`() {
        val deviceEntered = CountDownLatch(1)
        val releaseDevice = CountDownLatch(1)
        val retirementCompleted = CountDownLatch(1)
        val fixture = DirectAudioCapabilitiesFixture().apply {
            device.beforeConfigure = {
                deviceEntered.countDown()
                releaseDevice.await(5, TimeUnit.SECONDS)
            }
            device.recordConfigured = true
        }
        val controller = fixture.controller()
        val lease = controller.acquireCapture()
        val configuration = thread(name = "direct-device-configuration") {
            lease.configureRecorder(uninitializedAudioRecord())
        }
        assertTrue(deviceEntered.await(5, TimeUnit.SECONDS))
        val retirement = thread(name = "direct-retire-during-device") {
            lease.retire()
            retirementCompleted.countDown()
        }

        try {
            assertTrue(awaitThreadState(retirement, Thread.State.BLOCKED))
            assertFalse(retirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseDevice.countDown()
            configuration.join(5_000)
            retirement.join(5_000)
        }

        assertFalse(configuration.isAlive)
        assertFalse(retirement.isAlive)
        assertTrue(retirementCompleted.await(0, TimeUnit.MILLISECONDS))
        assertEquals(1, fixture.device.retireCalls)
        assertEquals(1, fixture.bluetooth.retireCalls)
        assertEquals(1, fixture.mode.retireCalls)
        assertEquals(1, fixture.focus.retireCalls)
        assertTrue(fixture.events.indexOf("device-configured") < fixture.events.indexOf("device-retire"))
        controller.close()
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

    @Test
    fun `controller close leaves active capture resources to exact lease owner`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()
        val lease = controller.acquireCapture()
        lease.configureRecorder(uninitializedAudioRecord())

        controller.close()
        val eventsAfterClose = fixture.events.toList()
        assertEquals(listOf(0, 0, 0, 0), resourceRetireCounts(fixture))

        lease.retire()
        lease.retire()

        assertEquals(eventsAfterClose + listOf("device-retire", "bluetooth-retire", "mode-retire", "focus-retire"), fixture.events)
        assertEquals(listOf(1, 1, 1, 1), resourceRetireCounts(fixture))
    }

    @Test
    fun `concurrent retire calls publish one reverse order cleanup`() {
        val fixture = DirectAudioCapabilitiesFixture()
        val controller = fixture.controller()
        val lease = controller.acquireCapture()
        lease.configureRecorder(uninitializedAudioRecord())
        val completed = AtomicInteger()

        val racers = List(3) {
            thread(name = "direct-capture-retire-$it") {
                lease.retire()
                completed.incrementAndGet()
            }
        }
        racers.forEach { it.join(5_000) }

        assertEquals(3, completed.get())
        assertEquals(listOf(1, 1, 1, 1), resourceRetireCounts(fixture))
        controller.close()
    }
}

private fun resourceRetireCounts(fixture: DirectAudioCapabilitiesFixture): List<Int> = listOf(
    fixture.device.retireCalls,
    fixture.bluetooth.retireCalls,
    fixture.mode.retireCalls,
    fixture.focus.retireCalls,
)

private class DirectAudioCapabilitiesFixture(
    bluetoothOverride: DirectBluetoothCaptureCapability? = null,
    captureDeviceOverride: DirectCaptureDeviceCapability? = null,
    closeOverride: (() -> Unit)? = null,
) {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val focus = FakeDirectAudioFocusCapability(events)
    val mode = FakeDirectCommunicationModeCapability(events)
    val bluetooth = FakeDirectBluetoothCaptureCapability(events)
    val device = FakeDirectCaptureDeviceCapability(events)
    var closeCalls = 0

    private val capabilities = DirectAudioRouteCapabilities(
        focus = focus,
        communicationMode = mode,
        bluetoothCapture = bluetoothOverride ?: bluetooth,
        captureDevice = captureDeviceOverride ?: device,
        close = {
            closeCalls += 1
            events += "capabilities-close"
            closeOverride?.invoke()
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
    private val focusCallbacks = mutableListOf<(Int) -> Unit>()

    override fun acquire(onFocusChange: (Int) -> Unit): DirectAudioResourceLease? {
        acquireCalls += 1
        events += "focus-acquire"
        beforeAcquire()
        focusCallbacks += onFocusChange
        acquireFailure?.let { throw it }
        if (!acquisitionAvailable) return null
        return DirectAudioResourceLease {
            retireCalls += 1
            events += "focus-retire"
            retireFailure?.let { throw it }
        }
    }

    fun dispatch(change: Int, acquisitionIndex: Int = focusCallbacks.lastIndex) {
        focusCallbacks[acquisitionIndex].invoke(change)
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
    var acquireCalls = 0
    var retireCalls = 0

    override fun acquire(): DirectAudioResourceLease? {
        acquireCalls += 1
        events += "bluetooth-acquire"
        acquireFailure?.let { throw it }
        if (!acquisitionAvailable) return null
        return DirectAudioResourceLease {
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
    var beforeConfigure: () -> Unit = {}
    var recordConfigured = false
    var configureCalls = 0
    var retireCalls = 0

    override fun configure(recorder: AudioRecord): DirectAudioResourceLease? {
        configureCalls += 1
        events += "device-configure"
        beforeConfigure()
        if (!configurationAvailable) return null
        if (recordConfigured) events += "device-configured"
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

private fun awaitThreadState(
    thread: Thread,
    expectedState: Thread.State,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (System.nanoTime() < deadlineNanos) {
        if (thread.state == expectedState) return true
        Thread.yield()
    }
    return thread.state == expectedState
}
