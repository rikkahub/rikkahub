package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDirectAudioRouteControllerTest {
    @Test
    fun `granted focus is abandoned once while failed focus is never abandoned`() {
        val granted = FakeDirectAudioRoutePlatform()
        val grantedController = controller(granted)

        val grantedLease = grantedController.acquireCapture()
        grantedLease.retire()
        grantedLease.retire()
        grantedController.close()
        grantedController.close()

        assertEquals(1, granted.focusRequests)
        assertEquals(listOf(granted.focusHandle), granted.abandonedFocus)

        val failed = FakeDirectAudioRoutePlatform().apply {
            focusResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        val failedController = controller(failed)

        val failedLease = failedController.acquireCapture()
        failedLease.retire()
        failedController.close()

        assertEquals(1, failed.focusRequests)
        assertTrue(failed.abandonedFocus.isEmpty())
    }

    @Test
    fun `focus granted concurrently with close is immediately abandoned without later mutation`() {
        val platform = FakeDirectAudioRoutePlatform()
        val focusRequested = CountDownLatch(1)
        val releaseFocus = CountDownLatch(1)
        platform.onRequestFocus = {
            focusRequested.countDown()
            releaseFocus.await(5, TimeUnit.SECONDS)
        }
        val controller = controller(platform)
        val acquisitionFailure = AtomicReference<Throwable?>()
        val acquisition = thread(name = "direct-focus-request") {
            runCatching { controller.acquireCapture() }
                .onFailure(acquisitionFailure::set)
        }
        assertTrue(focusRequested.await(5, TimeUnit.SECONDS))

        controller.close()
        val mutationsAtClose = platform.androidMutations.toList()
        releaseFocus.countDown()
        acquisition.join(5_000)

        assertFalse(acquisition.isAlive)
        assertEquals(null, acquisitionFailure.get())
        assertEquals(listOf(platform.focusHandle), platform.abandonedFocus)
        assertEquals(mutationsAtClose + "abandonFocus", platform.androidMutations)
        assertEquals(0, platform.setCommunicationModeCalls)
        assertEquals(0, platform.startScoCalls)
    }

    @Test
    fun `accepted communication device clears once while rejected device never clears`() {
        val accepted = FakeDirectAudioRoutePlatform().apply {
            communicationDeviceAccepted = true
        }
        val acceptedRecorder = FakeDirectAudioRecorder()
        val acceptedController = controller(accepted)
        val acceptedLease = acceptedController.acquireCapture() as DirectVoiceAudioCaptureRouteLease
        acceptedLease.configureRecorder(acceptedRecorder)

        acceptedLease.retire()
        acceptedLease.retire()
        acceptedController.close()

        assertEquals(listOf(accepted.captureDevice), acceptedRecorder.preferredDevices)
        assertEquals(1, accepted.setCommunicationDeviceCalls)
        assertEquals(1, accepted.clearCommunicationDeviceCalls)

        val rejected = FakeDirectAudioRoutePlatform().apply {
            communicationDeviceAccepted = false
        }
        val rejectedController = controller(rejected)
        val rejectedLease = rejectedController.acquireCapture() as DirectVoiceAudioCaptureRouteLease
        rejectedLease.configureRecorder(FakeDirectAudioRecorder())

        rejectedLease.retire()
        rejectedController.close()

        assertEquals(1, rejected.setCommunicationDeviceCalls)
        assertEquals(0, rejected.clearCommunicationDeviceCalls)
    }

    @Test
    fun `partial and repeated SCO acquisition is stopped exactly once`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            throwWhenEnablingSco = true
        }
        val controller = controller(platform)

        val lease = controller.acquireCapture()
        lease.retire()
        lease.retire()
        controller.close()

        assertEquals(1, platform.startScoCalls)
        assertEquals(listOf(true, false), platform.scoEnabledValues)
        assertEquals(1, platform.stopScoCalls)
    }

    @Test
    fun `stale lease retirement does not clear a newer capture route`() {
        val platform = FakeDirectAudioRoutePlatform()
        val controller = controller(platform)
        val staleLease = controller.acquireCapture()
        val currentLease = controller.acquireCapture()

        staleLease.retire()

        assertEquals(0, platform.restoreAudioModeCalls)
        assertEquals(0, platform.stopScoCalls)

        currentLease.retire()

        assertEquals(1, platform.restoreAudioModeCalls)
        assertEquals(1, platform.stopScoCalls)
    }

    @Test
    fun `stale lease configuration cannot mutate current capture route`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            communicationDeviceAccepted = true
        }
        val controller = controller(platform)
        val staleLease = controller.acquireCapture() as DirectVoiceAudioCaptureRouteLease
        controller.acquireCapture()
        val recorder = FakeDirectAudioRecorder()
        val mutationsBeforeConfigure = platform.androidMutations.toList()

        assertThrows(IllegalStateException::class.java) {
            staleLease.configureRecorder(recorder)
        }

        assertTrue(recorder.preferredDevices.isEmpty())
        assertEquals(0, platform.setCommunicationDeviceCalls)
        assertEquals(mutationsBeforeConfigure, platform.androidMutations)
    }

    @Test
    fun `controller close makes late active lease retirement inert`() {
        val platform = FakeDirectAudioRoutePlatform()
        val controller = controller(platform)
        val lease = controller.acquireCapture()
        controller.close()
        val mutationsAfterClose = platform.androidMutations.toList()

        lease.retire()
        lease.retire()

        assertEquals(mutationsAfterClose, platform.androidMutations)
    }

    @Test
    fun `acquisition failure after partial setup rolls route back`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            throwWhenCheckingBluetoothPermission = true
        }
        val controller = controller(platform)

        assertThrows(IllegalStateException::class.java) {
            controller.acquireCapture()
        }

        assertEquals(1, platform.focusRequests)
        assertEquals(1, platform.setCommunicationModeCalls)
        assertEquals(1, platform.restoreAudioModeCalls)
        assertEquals(0, platform.startScoCalls)
        controller.close()
        assertEquals(listOf(platform.focusHandle), platform.abandonedFocus)
    }

    @Test
    fun `SCO stop is attempted when disabling SCO fails`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            throwWhenDisablingSco = true
        }
        val controller = controller(platform)

        val lease = controller.acquireCapture()
        lease.retire()
        controller.close()

        assertEquals(listOf(true, false), platform.scoEnabledValues)
        assertEquals(1, platform.stopScoCalls)
    }

    @Test
    fun `accepted voice recognition stops once with the exact acquired pair`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            deliverHeadsetDuringRequest = true
            voiceRecognitionAccepted = true
        }
        val controller = controller(platform)

        val lease = controller.acquireCapture()
        lease.retire()
        lease.retire()
        controller.close()

        assertEquals(listOf(platform.headset to platform.bluetoothDevice), platform.voiceRecognitionStarts)
        assertEquals(listOf(platform.headset to platform.bluetoothDevice), platform.voiceRecognitionStops)
    }

    @Test
    fun `headset delivered after close is closed and later disconnect does not mutate`() {
        val platform = FakeDirectAudioRoutePlatform()
        val controller = controller(platform)
        controller.acquireCapture()
        controller.close()
        val beforeDelivery = platform.androidMutations.toList()

        platform.deliverHeadset()

        assertEquals(listOf(platform.headset), platform.closedHeadsets)
        assertEquals(beforeDelivery + "closeHeadset", platform.androidMutations)
        val afterDelivery = platform.androidMutations.toList()

        platform.disconnectHeadset()

        assertEquals(afterDelivery, platform.androidMutations)
        assertTrue(platform.voiceRecognitionStarts.isEmpty())
    }

    @Test
    fun `queued headset voice recognition does not mutate Android after close`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            dispatchImmediately = false
        }
        val controller = controller(platform)
        controller.acquireCapture()

        platform.deliverHeadset()
        assertEquals(1, platform.pendingDispatchCount)
        controller.close()
        val mutationsAfterClose = platform.androidMutations.toList()

        platform.drainDispatches()

        assertTrue(platform.voiceRecognitionStarts.isEmpty())
        assertEquals(mutationsAfterClose, platform.androidMutations)
    }

    @Test
    fun `repeated cleanup releases every acquired resource exactly once`() {
        val platform = FakeDirectAudioRoutePlatform().apply {
            communicationDeviceAccepted = true
            deliverHeadsetDuringRequest = true
            voiceRecognitionAccepted = true
        }
        val controller = controller(platform)
        val lease = controller.acquireCapture() as DirectVoiceAudioCaptureRouteLease
        lease.configureRecorder(FakeDirectAudioRecorder())

        lease.retire()
        lease.retire()
        controller.close()
        val mutationsAfterClose = platform.androidMutations.toList()
        controller.close()

        assertEquals(1, platform.focusRequests)
        assertEquals(1, platform.abandonedFocus.size)
        assertEquals(1, platform.setCommunicationModeCalls)
        assertEquals(1, platform.restoreAudioModeCalls)
        assertEquals(1, platform.setCommunicationDeviceCalls)
        assertEquals(1, platform.clearCommunicationDeviceCalls)
        assertEquals(1, platform.startScoCalls)
        assertEquals(1, platform.stopScoCalls)
        assertEquals(1, platform.voiceRecognitionStarts.size)
        assertEquals(1, platform.voiceRecognitionStops.size)
        assertEquals(1, platform.requestHeadsetProxyCalls)
        assertEquals(1, platform.closedHeadsets.size)
        assertEquals(1, platform.closeCalls)
        assertEquals(mutationsAfterClose, platform.androidMutations)
    }

    @Test
    fun `closed controller rejects acquisition and performs no later mutation`() {
        val platform = FakeDirectAudioRoutePlatform()
        val controller = controller(platform)
        controller.close()
        val mutationsAfterClose = platform.androidMutations.toList()

        assertThrows(IllegalStateException::class.java) {
            controller.acquireCapture()
        }
        controller.close()

        assertEquals(mutationsAfterClose, platform.androidMutations)
    }

    private fun controller(platform: FakeDirectAudioRoutePlatform) =
        AndroidDirectAudioRouteController(platform = platform, onAudioError = {})
}

private class FakeDirectAudioRecorder(
    private val accepted: Boolean = true,
) : DirectAudioRecorder {
    val preferredDevices = mutableListOf<DirectAudioCaptureDevice>()

    override fun setPreferredDevice(device: DirectAudioCaptureDevice): Boolean {
        preferredDevices += device
        return accepted
    }
}

private class FakeDirectAudioRoutePlatform : DirectAudioRoutePlatform {
    override val available = true
    val focusHandle: DirectAudioFocusHandle = FakeDirectAudioFocusHandle
    val captureDevice = DirectAudioCaptureDevice(
        routeDevice = VoiceAudioRouteDevice(
            id = 7,
            type = VoiceAudioRouteDeviceType.BluetoothSco,
            name = "headset microphone",
        ),
        safeLabel = "7:BluetoothSco:headset microphone",
        handle = FakeDirectAudioCaptureDeviceHandle,
    )
    val headset: DirectBluetoothHeadset = FakeDirectBluetoothHeadset
    val bluetoothDevice: DirectBluetoothDevice = FakeDirectBluetoothDevice("headset:00:11")
    val androidMutations = mutableListOf<String>()
    var focusResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    var onRequestFocus: () -> Unit = {}
    var communicationDeviceAccepted = false
    var throwWhenEnablingSco = false
    var throwWhenDisablingSco = false
    var throwWhenCheckingBluetoothPermission = false
    var deliverHeadsetDuringRequest = false
    var voiceRecognitionAccepted = false
    var dispatchImmediately = true
    var focusRequests = 0
    val abandonedFocus = mutableListOf<DirectAudioFocusHandle>()
    var setCommunicationModeCalls = 0
    var restoreAudioModeCalls = 0
    var setCommunicationDeviceCalls = 0
    var clearCommunicationDeviceCalls = 0
    var startScoCalls = 0
    val scoEnabledValues = mutableListOf<Boolean>()
    var stopScoCalls = 0
    var requestHeadsetProxyCalls = 0
    val closedHeadsets = mutableListOf<DirectBluetoothHeadset>()
    val voiceRecognitionStarts = mutableListOf<Pair<DirectBluetoothHeadset, DirectBluetoothDevice>>()
    val voiceRecognitionStops = mutableListOf<Pair<DirectBluetoothHeadset, DirectBluetoothDevice>>()
    var closeCalls = 0
    private var headsetListener: DirectBluetoothHeadsetListener? = null
    private var communicationMode = false
    private val pendingDispatches = ArrayDeque<() -> Unit>()

    val pendingDispatchCount: Int
        get() = pendingDispatches.size

    override fun requestAudioFocus(onFocusChange: (Int) -> Unit): DirectAudioFocusAcquisition {
        focusRequests += 1
        androidMutations += "requestFocus"
        onRequestFocus()
        return DirectAudioFocusAcquisition(
            result = focusResult,
            handle = focusHandle.takeIf { focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        )
    }

    override fun abandonAudioFocus(handle: DirectAudioFocusHandle) {
        abandonedFocus += handle
        androidMutations += "abandonFocus"
    }

    override fun enterCommunicationMode(): Int? {
        if (communicationMode) return null
        communicationMode = true
        setCommunicationModeCalls += 1
        androidMutations += "setMode"
        return 3
    }

    override fun restoreAudioMode(mode: Int) {
        communicationMode = false
        restoreAudioModeCalls += 1
        androidMutations += "restoreMode:$mode"
    }

    override fun hasBluetoothConnectPermission(): Boolean {
        if (throwWhenCheckingBluetoothPermission) error("Bluetooth permission lookup failed")
        return true
    }

    override fun captureDevices(): List<DirectAudioCaptureDevice> = listOf(captureDevice)

    override fun recorder(recorder: android.media.AudioRecord): DirectAudioRecorder =
        error("Tests use the production DirectAudioRecorder overload")

    override fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean {
        setCommunicationDeviceCalls += 1
        androidMutations += "setCommunicationDevice"
        return communicationDeviceAccepted
    }

    override fun clearCommunicationDevice() {
        clearCommunicationDeviceCalls += 1
        androidMutations += "clearCommunicationDevice"
    }

    override fun startBluetoothSco() {
        startScoCalls += 1
        androidMutations += "startSco"
    }

    override fun setBluetoothScoEnabled(enabled: Boolean) {
        scoEnabledValues += enabled
        androidMutations += "scoEnabled:$enabled"
        if (enabled && throwWhenEnablingSco) error("SCO enable failed")
        if (!enabled && throwWhenDisablingSco) error("SCO disable failed")
    }

    override fun stopBluetoothSco() {
        stopScoCalls += 1
        androidMutations += "stopSco"
    }

    override fun requestBluetoothHeadsetProxy(listener: DirectBluetoothHeadsetListener): Boolean {
        requestHeadsetProxyCalls += 1
        androidMutations += "requestHeadset"
        headsetListener = listener
        if (deliverHeadsetDuringRequest) listener.onConnected(headset)
        return true
    }

    override fun awaitBluetoothHeadset(current: () -> DirectBluetoothHeadset?): DirectBluetoothHeadset? = current()

    override fun closeBluetoothHeadsetProxy(headset: DirectBluetoothHeadset) {
        closedHeadsets += headset
        androidMutations += "closeHeadset"
    }

    override fun connectedBluetoothDevices(headset: DirectBluetoothHeadset): List<DirectBluetoothDevice> =
        listOf(bluetoothDevice)

    override fun startVoiceRecognition(
        headset: DirectBluetoothHeadset,
        device: DirectBluetoothDevice,
    ): Boolean {
        voiceRecognitionStarts += headset to device
        androidMutations += "startVoiceRecognition"
        return voiceRecognitionAccepted
    }

    override fun stopVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice) {
        voiceRecognitionStops += headset to device
        androidMutations += "stopVoiceRecognition"
    }

    override fun dispatch(block: () -> Unit) {
        if (dispatchImmediately) block() else pendingDispatches.addLast(block)
    }

    override fun close() {
        closeCalls += 1
    }

    fun deliverHeadset() {
        requireNotNull(headsetListener).onConnected(headset)
    }

    fun disconnectHeadset() {
        requireNotNull(headsetListener).onDisconnected()
    }

    fun drainDispatches() {
        while (pendingDispatches.isNotEmpty()) {
            pendingDispatches.removeFirst().invoke()
        }
    }
}

private data object FakeDirectAudioFocusHandle : DirectAudioFocusHandle

private data object FakeDirectAudioCaptureDeviceHandle : DirectAudioCaptureDeviceHandle

private data object FakeDirectBluetoothHeadset : DirectBluetoothHeadset

private data class FakeDirectBluetoothDevice(
    override val safeLabel: String,
) : DirectBluetoothDevice
