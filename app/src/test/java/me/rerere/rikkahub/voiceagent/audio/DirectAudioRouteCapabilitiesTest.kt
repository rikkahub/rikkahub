package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class DirectAudioRouteCapabilitiesTest {
    @Test
    fun `missing audio manager disables Bluetooth capture before permission probe`() {
        var permissionChecks = 0

        val available = directBluetoothCaptureAvailable(audioManagerAvailable = false) {
            permissionChecks += 1
            true
        }

        assertFalse(available)
        assertEquals(0, permissionChecks)
    }

    @Test
    fun `Bluetooth permission denial performs no routing mutation`() {
        val operations = FakeBluetoothCaptureOperations().apply { permissionGranted = false }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        assertNull(capability.acquire())

        assertEquals(1, operations.permissionChecks)
        assertTrue(operations.mutations.isEmpty())
    }

    @Test
    fun `partial SCO setup retires once and stop continues after disable failure`() {
        val operations = FakeBluetoothCaptureOperations().apply {
            throwWhenEnablingSco = true
            throwWhenDisablingSco = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        val lease = requireNotNull(capability.acquire())
        lease.retire()
        lease.retire()
        capability.close()

        assertEquals(1, operations.startScoCalls)
        assertEquals(listOf(true, false), operations.scoEnabledValues)
        assertEquals(1, operations.stopScoCalls)
        assertEquals(1, operations.closeCalls)
    }

    @Test
    fun `accepted recognition stops exact pair and proxy closes once`() {
        val operations = FakeBluetoothCaptureOperations().apply {
            deliverHeadsetDuringRequest = true
            recognitionAccepted = true
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)

        val lease = requireNotNull(capability.acquire())
        lease.retire()
        lease.retire()
        capability.close()
        capability.close()

        val pair = operations.headset to operations.device
        assertEquals(listOf(pair), operations.recognitionStarts)
        assertEquals(listOf(pair), operations.recognitionStops)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        assertEquals(1, operations.closeCalls)
    }

    @Test
    fun `callback delivered after capability close only closes proxy`() {
        val operations = FakeBluetoothCaptureOperations()
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        lease.retire()

        capability.beginClose()
        capability.close()
        val mutationsBeforeCallback = operations.mutations.toList()
        operations.deliverHeadset()

        assertEquals(mutationsBeforeCallback + "close-proxy", operations.mutations)
        assertEquals(listOf(operations.headset), operations.closedHeadsets)
        assertTrue(operations.recognitionStarts.isEmpty())
        assertEquals(1, operations.startScoCalls)
    }

    @Test
    fun `queued recognition callback observes close before Android mutation`() {
        val operations = FakeBluetoothCaptureOperations().apply {
            dispatchImmediately = false
        }
        val capability = SystemDirectBluetoothCaptureCapability(operations)
        val lease = requireNotNull(capability.acquire())
        operations.deliverHeadset()
        assertEquals(1, operations.pendingDispatchCount)

        capability.beginClose()
        lease.retire()
        capability.close()
        val mutationsAfterClose = operations.mutations.toList()
        operations.drainDispatches()

        assertTrue(operations.recognitionStarts.isEmpty())
        assertEquals(mutationsAfterClose, operations.mutations)
    }

    @Test
    fun `accepted communication device clears once while rejected device never clears`() {
        val acceptedOperations = FakeCaptureDeviceOperations().apply {
            communicationDeviceAccepted = true
        }
        val accepted = SystemDirectCaptureDeviceCapability(acceptedOperations)
        val acceptedLease = requireNotNull(accepted.configure(uninitializedAudioRecord()))

        acceptedLease.retire()
        acceptedLease.retire()

        assertEquals(listOf(acceptedOperations.device), acceptedOperations.preferredDevices)
        assertEquals(listOf(acceptedOperations.device), acceptedOperations.communicationDevices)
        assertEquals(1, acceptedOperations.clearCommunicationDeviceCalls)

        val rejectedOperations = FakeCaptureDeviceOperations().apply {
            communicationDeviceAccepted = false
        }
        val rejected = SystemDirectCaptureDeviceCapability(rejectedOperations)

        assertNull(rejected.configure(uninitializedAudioRecord()))
        assertEquals(listOf(rejectedOperations.device), rejectedOperations.preferredDevices)
        assertEquals(listOf(rejectedOperations.device), rejectedOperations.communicationDevices)
        assertEquals(0, rejectedOperations.clearCommunicationDeviceCalls)
    }

    @Test
    fun `capture device permission denial skips enumeration and recorder mutation`() {
        val operations = FakeCaptureDeviceOperations().apply { permissionGranted = false }
        val capability = SystemDirectCaptureDeviceCapability(operations)

        assertNull(capability.configure(uninitializedAudioRecord()))

        assertEquals(1, operations.permissionChecks)
        assertEquals(0, operations.captureDeviceQueries)
        assertTrue(operations.preferredDevices.isEmpty())
        assertTrue(operations.communicationDevices.isEmpty())
    }
}

internal class FakeBluetoothCaptureOperations : DirectBluetoothCaptureOperations {
    val headset: DirectBluetoothHeadset = FakeBluetoothHeadset
    val device: DirectBluetoothDevice = FakeBluetoothDevice("headset:00:11")
    var permissionGranted = true
    var deliverHeadsetDuringRequest = false
    var recognitionAccepted = false
    var throwWhenEnablingSco = false
    var throwWhenDisablingSco = false
    var dispatchImmediately = true
    var permissionProbeFailure: Throwable? = null
    var awaitFailure: Throwable? = null
    var waitUntilStopped = false
    val awaitEntered = CountDownLatch(1)
    val awaitStopObserved = CountDownLatch(1)
    val releaseAwait = CountDownLatch(1)
    var permissionChecks = 0
    var startScoCalls = 0
    val scoEnabledValues = mutableListOf<Boolean>()
    var stopScoCalls = 0
    val recognitionStarts = mutableListOf<Pair<DirectBluetoothHeadset, DirectBluetoothDevice>>()
    val recognitionStops = mutableListOf<Pair<DirectBluetoothHeadset, DirectBluetoothDevice>>()
    val closedHeadsets = mutableListOf<DirectBluetoothHeadset>()
    var closeCalls = 0
    val mutations = mutableListOf<String>()
    private var listener: DirectBluetoothHeadsetListener? = null
    private val pendingDispatches = ArrayDeque<() -> Unit>()

    val pendingDispatchCount: Int
        get() = pendingDispatches.size

    override fun hasConnectPermission(): Boolean {
        permissionChecks += 1
        permissionProbeFailure?.let { throw it }
        return permissionGranted
    }

    override fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener): Boolean {
        this.listener = listener
        mutations += "request-proxy"
        if (deliverHeadsetDuringRequest) listener.onConnected(headset)
        return true
    }

    override fun awaitHeadset(
        current: () -> DirectBluetoothHeadset?,
        shouldStop: () -> Boolean,
    ): DirectBluetoothHeadset? {
        awaitEntered.countDown()
        awaitFailure?.let { throw it }
        if (waitUntilStopped) {
            while (!shouldStop() && !releaseAwait.await(10, TimeUnit.MILLISECONDS)) {
                // Poll the real capability's terminal predicate deterministically.
            }
            if (shouldStop()) awaitStopObserved.countDown()
        }
        return current().takeUnless { shouldStop() }
    }

    override fun closeHeadsetProxy(headset: DirectBluetoothHeadset) {
        closedHeadsets += headset
        mutations += "close-proxy"
    }

    override fun connectedDevices(headset: DirectBluetoothHeadset): List<DirectBluetoothDevice> = listOf(device)

    override fun startVoiceRecognition(
        headset: DirectBluetoothHeadset,
        device: DirectBluetoothDevice,
    ): Boolean {
        recognitionStarts += headset to device
        mutations += "start-recognition"
        return recognitionAccepted
    }

    override fun stopVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice) {
        recognitionStops += headset to device
        mutations += "stop-recognition"
    }

    override fun startBluetoothSco() {
        startScoCalls += 1
        mutations += "start-sco"
    }

    override fun setBluetoothScoEnabled(enabled: Boolean) {
        scoEnabledValues += enabled
        mutations += "sco-enabled:$enabled"
        if (enabled && throwWhenEnablingSco) error("SCO enable failed")
        if (!enabled && throwWhenDisablingSco) error("SCO disable failed")
    }

    override fun stopBluetoothSco() {
        stopScoCalls += 1
        mutations += "stop-sco"
    }

    override fun dispatch(block: () -> Unit) {
        if (dispatchImmediately) block() else pendingDispatches.addLast(block)
    }

    override fun close() {
        closeCalls += 1
    }

    fun deliverHeadset() {
        requireNotNull(listener).onConnected(headset)
    }

    fun drainDispatches() {
        while (pendingDispatches.isNotEmpty()) pendingDispatches.removeFirst().invoke()
    }
}

internal class FakeCaptureDeviceOperations : DirectCaptureDeviceOperations {
    val device = DirectAudioCaptureDevice(
        routeDevice = VoiceAudioRouteDevice(
            id = 7,
            type = VoiceAudioRouteDeviceType.BluetoothSco,
            name = "headset microphone",
        ),
        safeLabel = "7:BluetoothSco:headset microphone",
        handle = FakeCaptureDeviceHandle,
    )
    var permissionGranted = true
    var communicationDeviceAccepted = false
    var permissionProbeFailure: Throwable? = null
    var permissionChecks = 0
    var captureDeviceQueries = 0
    val preferredDevices = mutableListOf<DirectAudioCaptureDevice>()
    val communicationDevices = mutableListOf<DirectAudioCaptureDevice>()
    var clearCommunicationDeviceCalls = 0

    override fun hasConnectPermission(): Boolean {
        permissionChecks += 1
        permissionProbeFailure?.let { throw it }
        return permissionGranted
    }

    override fun captureDevices(): List<DirectAudioCaptureDevice> {
        captureDeviceQueries += 1
        return listOf(device)
    }

    override fun setPreferredDevice(recorder: AudioRecord, device: DirectAudioCaptureDevice): Boolean {
        preferredDevices += device
        return true
    }

    override fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean {
        communicationDevices += device
        return communicationDeviceAccepted
    }

    override fun clearCommunicationDevice() {
        clearCommunicationDeviceCalls += 1
    }
}

private data object FakeBluetoothHeadset : DirectBluetoothHeadset

private data class FakeBluetoothDevice(
    override val safeLabel: String,
) : DirectBluetoothDevice

private data object FakeCaptureDeviceHandle : DirectAudioCaptureDeviceHandle

private fun uninitializedAudioRecord(): AudioRecord {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    return (unsafeField.get(null) as Unsafe).allocateInstance(AudioRecord::class.java) as AudioRecord
}
