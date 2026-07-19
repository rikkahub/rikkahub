package me.rerere.rikkahub.voiceagent.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class AndroidDirectBluetoothCaptureOperations(
    private val context: Context,
    private val audioManager: AudioManager?,
) : DirectBluetoothCaptureOperations<BluetoothHeadset, BluetoothDevice> {
    override fun createCallbackDispatcher(): DirectBluetoothCallbackDispatcher =
        AndroidDirectBluetoothCallbackDispatcher()

    override fun hasConnectPermission(): Boolean = directBluetoothCaptureAvailable(audioManager != null) {
        hasBluetoothConnectPermission(context)
    }

    @SuppressLint("MissingPermission")
    override fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener<BluetoothHeadset>): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val androidListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET && proxy is BluetoothHeadset) {
                    listener.onConnected(proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) listener.onDisconnected()
            }
        }
        return adapter.getProfileProxy(context, androidListener, BluetoothProfile.HEADSET)
    }

    override fun closeHeadsetProxy(headset: BluetoothHeadset) {
        context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.closeProfileProxy(BluetoothProfile.HEADSET, headset)
    }

    @SuppressLint("MissingPermission")
    override fun connectedDevices(headset: BluetoothHeadset): List<BluetoothDevice> =
        headset.connectedDevices

    @SuppressLint("MissingPermission")
    override fun safeLabel(device: BluetoothDevice): String =
        "${device.name ?: "unnamed"}:${device.address ?: "unknown"}"

    @SuppressLint("MissingPermission")
    override fun startVoiceRecognition(headset: BluetoothHeadset, device: BluetoothDevice): Boolean =
        headset.startVoiceRecognition(device)

    @SuppressLint("MissingPermission")
    override fun stopVoiceRecognition(headset: BluetoothHeadset, device: BluetoothDevice) {
        headset.stopVoiceRecognition(device)
    }

    @Suppress("DEPRECATION")
    override fun startBluetoothSco() {
        audioManager?.startBluetoothSco()
    }

    @Suppress("DEPRECATION")
    override fun setBluetoothScoEnabled(enabled: Boolean) {
        audioManager?.isBluetoothScoOn = enabled
    }

    @Suppress("DEPRECATION")
    override fun stopBluetoothSco() {
        audioManager?.stopBluetoothSco()
    }
}

private class AndroidDirectBluetoothCallbackDispatcher : DirectBluetoothCallbackDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun dispatch(block: () -> Unit) {
        scope.launch { block() }
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
