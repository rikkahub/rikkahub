package me.rerere.rikkahub.web

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "NsdServiceRegistrar"
private const val DEFAULT_SERVICE_TYPE = "_http._tcp."
const val DEFAULT_SERVICE_NAME = "ktor-android"

class NsdServiceRegistrar(
    context: Context
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(
        port: Int,
        serviceName: String = DEFAULT_SERVICE_NAME,
        serviceType: String = DEFAULT_SERVICE_TYPE,
        onRegistered: ((NsdServiceInfo) -> Unit)? = null
    ) {
        val manager = nsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager not available, skip register")
            return
        }
        if (registrationListener != null) {
            unregister()
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName} type=${info.serviceType} port=${info.port}")
                onRegistered?.invoke(info)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: $errorCode")
            }
        }

        registrationListener = listener
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        runCatching {
            manager.unregisterService(listener)
        }.onFailure {
            Log.w(TAG, "Unregister service failed", it)
        }
        registrationListener = null
    }

}
